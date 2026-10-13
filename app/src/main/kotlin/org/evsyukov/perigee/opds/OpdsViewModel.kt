package org.evsyukov.perigee.opds

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.data.Catalog
import org.evsyukov.perigee.data.Catalogs
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.FoundBook
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LibraryPreferences
import org.evsyukov.perigee.library.BookSink
import org.evsyukov.perigee.library.TreeSink
import org.evsyukov.perigee.library.localCopy
import org.evsyukov.perigee.library.readBook

/** Why a catalog did not answer as hoped. */
enum class Failure {
    /** The address is not one a catalog can be at. */
    BadAddress,

    /** The catalog wants a user name and password, or other ones. */
    Unauthorized,

    /** There is something at the address, but not a catalog. */
    NotACatalog,

    /** The server cannot be reached or refuses. */
    Unreachable,

    /** The stored password cannot be read any more; the catalog has to be added again. */
    PasswordLost,

    /** A downloaded file is not a book the app reads. */
    NotABook,
}

/** A page of a catalog as shown: every part of it fetched so far. */
data class Page(
    val url: String,
    val title: String,
    val entries: List<OpdsEntry>,
    /** The address of the part that follows, while there is one. */
    val next: String?,
    /** The page as it came, for the search it offers. */
    val feed: OpdsFeed,
)

/** How far a book is on its way into the library. */
sealed interface Download {
    data object Running : Download
    data class Done(val bookId: String) : Download
    data class Failed(val why: Failure) : Download
}

data class OpdsState(
    val catalogs: List<Catalog> = emptyList(),
    /** The catalog being looked through; null while the list of catalogs is shown. */
    val catalog: Catalog? = null,
    /** The pages gone through, the one shown last. */
    val pages: List<Page> = emptyList(),
    val loading: Boolean = false,
    val failure: Failure? = null,
    /** By the address of the file. */
    val downloads: Map<String, Download> = emptyMap(),
    /** A catalog is being checked before it is added. */
    val adding: Boolean = false,
    val addFailure: Failure? = null,
    /** Goes up each time a catalog is added, for the form to close itself. */
    val added: Int = 0,
    /** A download that waits for the reader to say where downloaded books should go. */
    val asking: Pair<OpdsEntry, OpdsLink>? = null,
) {
    val page: Page? get() = pages.lastOrNull()
}

/** The reader's OPDS catalogs: adding them, looking through one, downloading books into the library. */
class OpdsViewModel(
    application: Application,
    private val catalogs: Catalogs,
    private val library: Library,
    private val covers: Covers,
    private val preferences: LibraryPreferences,
    /** The folder downloads are put into, by its tree URI. */
    private val sinkOf: (String) -> BookSink,
) : AndroidViewModel(application) {

    @Suppress("unused") // The constructor the system makes the model with.
    constructor(application: Application) : this(
        application, Catalogs.get(application), Library.get(application), Covers.get(application),
        LibraryPreferences.get(application), { uri -> TreeSink(application.contentResolver, Uri.parse(uri)) },
    )

    private val _state = MutableStateFlow(OpdsState())
    val state: StateFlow<OpdsState> = _state.asStateFlow()

    private var client: OpdsClient? = null
    private var fetching: Job? = null
    private val pictures = LruCache<String, ImageBitmap>(64)

    init {
        viewModelScope.launch { catalogs.all().collect { stored -> _state.update { it.copy(catalogs = stored) } } }
    }

    /**
     * Checks that there is a catalog at the address and adds it, titled as it
     * titles itself. An address without "http" in front is taken as https.
     */
    fun add(address: String, user: String, password: String) {
        if (_state.value.adding) return
        val url = address.trim().let { if ("://" in it) it else "https://$it" }
        _state.update { it.copy(adding = true, addFailure = null) }
        viewModelScope.launch {
            val failure = try {
                val checked = OpdsClient(url, user.trim(), password)
                val feed = checked.feed()
                catalogs.add(url, feed.title.ifBlank { url.substringAfter("://").substringBefore('/') }, user.trim(), password)
                null
            } catch (e: IllegalArgumentException) {
                Failure.BadAddress
            } catch (e: IOException) {
                failureOf(e)
            }
            _state.update { it.copy(adding = false, addFailure = failure, added = if (failure == null) it.added + 1 else it.added) }
        }
    }

    /** The form was put away: what it said about the last try is no longer of use. */
    fun forgetAddFailure() = _state.update { it.copy(addFailure = null) }

    fun remove(catalog: Catalog) {
        viewModelScope.launch { catalogs.remove(catalog.id) }
    }

    /** Starts looking through a catalog from its first page. */
    fun open(catalog: Catalog) {
        fetching?.cancel()
        client = null
        _state.update { it.copy(catalog = catalog, pages = emptyList(), failure = null, loading = true) }
        fetching = viewModelScope.launch {
            val password = catalogs.password(catalog)
            if (password == null) {
                _state.update { it.copy(loading = false, failure = Failure.PasswordLost) }
                return@launch
            }
            client = OpdsClient(catalog.url, catalog.user, password)
            show(catalog.url, catalog.title)
        }
    }

    /** Goes into a section, or to the page of a book. */
    fun go(entry: OpdsEntry) {
        val url = entry.feed ?: return
        fetch { show(url, entry.title) }
    }

    /** Fetches the part of the page that follows, when the reader has reached its end. */
    fun more() {
        val page = _state.value.page ?: return
        val next = page.next ?: return
        val from = client ?: return
        if (_state.value.loading) return
        fetch {
            val feed = from.feed(next)
            _state.update { state ->
                // The reader may have left the page while its next part was on the way.
                if (state.page !== page) return@update state
                val grown = page.copy(entries = page.entries + feed.entries, next = feed.next.takeIf { it != next })
                state.copy(pages = state.pages.dropLast(1) + grown)
            }
        }
    }

    /** Looks a phrase up through the search the page shown offers. */
    fun search(query: String) {
        val page = _state.value.page ?: return
        val from = client ?: return
        if (query.isBlank()) return
        fetch {
            val found = from.search(page.feed, page.url, query) ?: return@fetch
            _state.update { it.copy(pages = it.pages + Page(page.url, query.trim(), found.entries, found.next, found)) }
        }
    }

    /** Tries the page that failed once more. */
    fun retry() {
        val catalog = _state.value.catalog ?: return
        val page = _state.value.page
        if (page == null) open(catalog) else _state.update { it.copy(failure = null) }
    }

    /**
     * One step back: to the page before, or out of the catalog to the list.
     * False when there is nowhere to step back to.
     */
    fun back(): Boolean {
        val state = _state.value
        if (state.catalog == null) return false
        fetching?.cancel()
        if (state.pages.size > 1) {
            _state.update { it.copy(pages = it.pages.dropLast(1), loading = false, failure = null) }
        } else {
            client = null
            _state.update { it.copy(catalog = null, pages = emptyList(), loading = false, failure = null, downloads = emptyMap()) }
        }
        return true
    }

    /**
     * Downloads a book and puts it into the library. The first time, the
     * reader is asked where downloaded books should go, and the download
     * waits for the answer.
     */
    fun download(entry: OpdsEntry, link: OpdsLink) {
        val from = client ?: return
        if (_state.value.downloads[link.url] is Download.Running) return
        viewModelScope.launch {
            if (!preferences.prefs.first().downloadsAsked) {
                _state.update { it.copy(asking = entry to link) }
                return@launch
            }
            mark(link, Download.Running)
            val app = getApplication<Application>()
            // Its own folder: two books may come under one name.
            val arrivals = File(app.cacheDir, "opds/${UUID.randomUUID()}")
            val result = try {
                val file = from.download(link, entry.title, arrivals)
                keep(file, link.format.ifEmpty { Library.formatOf(file.name) })
            } catch (e: IOException) {
                Download.Failed(failureOf(e))
            } finally {
                withContext(Dispatchers.IO) { arrivals.deleteRecursively() }
            }
            mark(link, result)
        }
    }

    /** The reader chose a folder for downloaded books; the download that waited for it goes on. */
    fun downloadInto(uri: String, name: String) = answer {
        library.addFolder(uri, name)
        preferences.update { it.copy(downloadsFolder = uri, downloadsAsked = true) }
    }

    /** The reader would rather keep downloaded books in the app's own storage. */
    fun downloadIntoApp() = answer { preferences.update { it.copy(downloadsAsked = true) } }

    /** The question was put away unanswered; it will be asked again. */
    fun dismissAsking() = _state.update { it.copy(asking = null) }

    private fun answer(remember: suspend () -> Unit) {
        val waiting = _state.value.asking
        _state.update { it.copy(asking = null) }
        viewModelScope.launch {
            remember()
            if (waiting != null) download(waiting.first, waiting.second)
        }
    }

    /** The file to read a downloaded book from; null when it cannot be got at any more. */
    suspend fun fileOf(bookId: String): File? = library.book(bookId)?.let { localCopy(getApplication(), library, it) }

    private suspend fun keep(file: File, format: String): Download = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val book = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            readBook(app, covers, it, file.name, format)
        } ?: return@withContext Download.Failed(Failure.NotABook)
        val folderUri = preferences.prefs.first().downloadsFolder
        if (folderUri != null) {
            // A book that lies in a folder already is not put there twice.
            if (library.book(book.id)?.uri != null) return@withContext Download.Done(book.id)
            val placed = sinkOf(folderUri).put(file.name, file)
            if (placed != null) {
                val folder = library.addFolder(folderUri, "")
                library.found(
                    folder.id,
                    FoundBook(
                        book.id, placed.uri, placed.size, placed.modified, format, book.title, book.authors,
                        book.language, book.series, book.seriesNumber, book.hasCover,
                    ),
                )
                return@withContext Download.Done(book.id)
            }
            // The folder cannot be written to: the book is kept in the app rather than lost.
        }
        val kept = File(File(app.filesDir, "books").apply { mkdirs() }, "${book.id}.${format.ifEmpty { "book" }}")
        if (!file.renameTo(kept)) {
            file.copyTo(kept, overwrite = true)
        }
        library.keep(kept, book)
        Download.Done(book.id)
    }

    private fun mark(link: OpdsLink, download: Download) =
        _state.update { it.copy(downloads = it.downloads + (link.url to download)) }

    /** The cover at an address, fetched once and kept while the catalog is looked through. */
    suspend fun cover(url: String): ImageBitmap? {
        pictures.get(url)?.let { return it }
        val from = client ?: return null
        return withContext(Dispatchers.IO) {
            from.picture(url)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, small(bytes)) }?.asImageBitmap()
        }?.also { pictures.put(url, it) }
    }

    /** Decoding options that keep a poster-sized cover from being held in memory whole. */
    private fun small(bytes: ByteArray): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= COVER_HEIGHT) sample *= 2
        return BitmapFactory.Options().apply { inSampleSize = sample }
    }

    private suspend fun show(url: String, title: String) {
        val from = client ?: return
        try {
            val feed = from.feed(url)
            val page = Page(url, feed.title.ifBlank { title }, feed.entries, feed.next, feed)
            _state.update { it.copy(pages = it.pages + page, loading = false, failure = null) }
        } catch (e: IOException) {
            _state.update { it.copy(loading = false, failure = failureOf(e)) }
        }
    }

    private fun fetch(block: suspend () -> Unit) {
        fetching?.cancel()
        _state.update { it.copy(loading = true, failure = null) }
        fetching = viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(loading = false) }
            } catch (e: IOException) {
                _state.update { it.copy(loading = false, failure = failureOf(e)) }
            }
        }
    }

    private fun failureOf(e: IOException): Failure = when (e) {
        is UnauthorizedException -> Failure.Unauthorized
        is NotACatalogException -> Failure.NotACatalog
        else -> Failure.Unreachable
    }

    private companion object {
        const val COVER_HEIGHT = 320
    }
}
