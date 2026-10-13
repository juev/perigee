package org.evsyukov.perigee.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.BookCollection
import org.evsyukov.perigee.data.BookSort
import org.evsyukov.perigee.data.CollectionBook
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.Folder
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LibraryPreferences
import org.evsyukov.perigee.data.ReadingStatus
import org.evsyukov.perigee.data.Shelf
import org.evsyukov.perigee.server.Server
import org.evsyukov.perigee.server.SyncWork
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.DeviceChannelException
import org.evsyukov.perigee.sync.IncompatibleServerException
import org.evsyukov.perigee.sync.NotAllowedException
import org.evsyukov.perigee.sync.SignedOutException
import org.evsyukov.perigee.sync.UploadOptions
import org.evsyukov.perigee.sync.UploadPlace
import org.evsyukov.perigee.sync.UploadRefusal
import org.evsyukov.perigee.sync.UploadRefusedException

/** Why a book did not get to the server. */
enum class SendFailure {
    TooLarge,

    /** The server, or the library chosen, does not take files of this format. */
    Format,

    /** The server keeps a file under the name this one would get. */
    NameTaken,

    /** The server does not find the file to be the book its name says. */
    NotABook,
    NoRoom,

    /** The library or folder chosen before is not there any more; the next try asks where to. */
    NoPlace,
    NotAllowed,

    /** Reading is not synced with the server, so it cannot be asked which files it has. */
    NoSync,
    SignedOut,

    /** The file of the book cannot be read here. */
    FileGone,
    Unreachable,
}

/** How far a book is on its way to the server. */
sealed interface Sending {
    /** [percent] of the file has gone. */
    data class Running(val percent: Int) : Sending
    data class Failed(val why: SendFailure) : Sending
}

/** A place on the server a book can be sent to, as the reader is offered it. */
data class SendPlace(val place: UploadPlace, val library: String, /** Empty when the library has one folder. */ val folder: String)

/** What the reader is asked before a book is sent. */
sealed interface SendQuestion {
    val book: Book

    /** The server has a book that goes by the same title and authors: send this one all the same? */
    data class Alike(override val book: Book) : SendQuestion

    /** Which of the server's libraries and folders to send books to; asked once. */
    data class Where(override val book: Book, val places: List<SendPlace>) : SendQuestion
}

data class LibraryState(
    /** False until the stored books have been read; nothing is shown before that. */
    val loaded: Boolean = false,
    val shelf: Shelf = Shelf.All,
    val sort: BookSort = BookSort.Recent,
    val query: String = "",
    /** The author, series or collection that was picked on a grouped shelf. */
    val group: Group? = null,
    val listing: Listing = Listing.Books(emptyList()),
    /** The library has no books at all, whatever the shelf. */
    val empty: Boolean = true,
    val folders: List<Folder> = emptyList(),
    val collections: List<BookCollection> = emptyList(),
    val members: List<CollectionBook> = emptyList(),
    val scanning: Boolean = false,
    /** Tree URI of the folder downloaded books are put into; null when they stay in the app's storage. */
    val downloadsFolder: String? = null,
    /** Whether the reader is signed in to a server; only then is a book said to be on it or not. */
    val signedIn: Boolean = false,
    /** What the server takes from the reader; null when signed in nowhere, or it could not be asked. */
    val upload: UploadOptions? = null,
    /** Books on their way to the server, and those that did not get there, by their ids. */
    val sending: Map<String, Sending> = emptyMap(),
    val question: SendQuestion? = null,
) {
    /** Whether the reader can send this book to the server: its file is not there, and the server takes such files from them. */
    fun canSend(book: Book): Boolean =
        book.serverFileId == null && sending[book.id] !is Sending.Running && upload?.librariesFor(book.format)?.isNotEmpty() == true
}

/** The library screen: shelves of books, the folders they come from, collections. */
class LibraryViewModel(
    application: Application,
    private val library: Library,
    private val preferences: LibraryPreferences,
    val covers: Covers,
    /** What is done once the folders were looked through, as asking the server about the books found. */
    private val afterScan: suspend () -> Unit = {},
    /** What is done once the reader changed a status or a rating, as telling the server. */
    private val afterChange: suspend () -> Unit = {},
    /** The server books are sent to, and who is signed in to it; without them nothing is offered to be sent. */
    private val orbit: BookOrbit? = null,
    private val accounts: Accounts? = null,
    private val sourceOf: (Folder) -> BookSource,
) : AndroidViewModel(application) {

    @Suppress("unused") // The constructor the system makes the model with.
    constructor(application: Application) : this(
        application, Library.get(application), LibraryPreferences.get(application), Covers.get(application),
        { Server.matchNewBooks(application) },
        { SyncWork.soon(application) },
        Server.get(application), Accounts.get(application),
        { folder -> TreeSource(application.contentResolver, Uri.parse(folder.uri)) },
    )

    private val scanner = LibraryScanner(application, library, covers)

    private data class Looking(val query: String = "", val group: Group? = null, val scanning: Boolean = false)
    private data class Stored(
        val books: List<Book>, val folders: List<Folder>,
        val collections: List<BookCollection>, val members: List<CollectionBook>,
    )

    /** What is known of the server, and of the books on their way to it. */
    private data class Serving(
        val signedIn: Boolean = false, val upload: UploadOptions? = null,
        val sending: Map<String, Sending> = emptyMap(), val question: SendQuestion? = null,
    )

    private val looking = MutableStateFlow(Looking())
    private val serving = MutableStateFlow(Serving())
    private var scan: Job? = null
    private val requests = Mutex()

    val state: StateFlow<LibraryState> = combine(
        combine(library.books(), library.folders(), library.collections(), library.collectionBooks(), ::Stored),
        preferences.prefs,
        looking,
        serving,
    ) { stored, prefs, looking, serving ->
        // A group belongs to the shelf it was picked on; a collection may have been renamed since.
        val group = looking.group?.takeIf { prefs.shelf.grouped }?.let { picked ->
            if (prefs.shelf != Shelf.Collections) {
                picked
            } else {
                stored.collections.firstOrNull { it.id.toString() == picked.key }?.let { picked.copy(name = it.name) }
            }
        }
        LibraryState(
            loaded = true, shelf = prefs.shelf, sort = prefs.sort, query = looking.query, group = group,
            listing = withContext(Dispatchers.Default) {
                listing(stored.books, prefs.shelf, prefs.sort, looking.query, group?.key, stored.collections, stored.members)
            },
            empty = stored.books.isEmpty(), folders = stored.folders, collections = stored.collections,
            members = stored.members, scanning = looking.scanning, downloadsFolder = prefs.downloadsFolder,
            signedIn = serving.signedIn, upload = serving.upload, sending = serving.sending, question = serving.question,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState())

    init {
        // Folders change behind the app's back; every start looks through them again.
        rescan()
        if (accounts != null) {
            viewModelScope.launch {
                // A renewed session is the same reader on the same server; another one is asked anew.
                accounts.account().map { it?.let { account -> account.url to account.user } }.distinctUntilChanged().collect { who ->
                    serving.update { Serving(signedIn = who != null) }
                    if (who != null) askWhatServerTakes()
                }
            }
        }
    }

    /** Asks the server what it takes from the reader. Left unknown when it cannot be asked: nothing is offered to be sent then. */
    private suspend fun askWhatServerTakes(): UploadOptions? {
        val options = try {
            orbit?.uploadOptions()
        } catch (e: IOException) {
            null
        }
        serving.update { if (it.signedIn) it.copy(upload = options) else it }
        return options
    }

    /**
     * The reader asked for a book to be sent to the server. One that looks like
     * a book the server has is asked about first.
     */
    fun send(book: Book) {
        if (book.serverLikeId != null) serving.update { it.copy(question = SendQuestion.Alike(book)) } else sendSomewhere(book)
    }

    /** The reader would send the book though the server has one like it. */
    fun sendAnyway(book: Book) = sendSomewhere(book)

    /** The reader chose where books are sent; it is remembered, and not asked again. */
    fun sendTo(book: Book, place: UploadPlace) {
        viewModelScope.launch {
            accounts?.saveUploadPlace(place.libraryId, place.folderId)
            deliver(book, place)
        }
    }

    /** The reader thought better of sending the book. */
    fun dontSend() = serving.update { it.copy(question = null) }

    /** Finds where the book goes: the place chosen before, the only one there is, or the one the reader is asked for. */
    private fun sendSomewhere(book: Book) {
        serving.update { it.copy(question = null) }
        viewModelScope.launch {
            // Asked anew: libraries come and go on the server.
            val options = try {
                orbit?.uploadOptions()
            } catch (e: SignedOutException) {
                failed(book, SendFailure.SignedOut)
                return@launch
            } catch (e: IOException) {
                null
            }
            if (options == null) {
                failed(book, SendFailure.Unreachable)
                return@launch
            }
            serving.update { it.copy(upload = options) }
            val places = options.librariesFor(book.format).flatMap { library ->
                when (library.folders.size) {
                    0 -> listOf(SendPlace(UploadPlace(library.id), library.name, ""))
                    1 -> listOf(SendPlace(UploadPlace(library.id, library.folders[0].id), library.name, ""))
                    else -> library.folders.map { SendPlace(UploadPlace(library.id, it.id), library.name, it.name) }
                }
            }
            val account = accounts?.current()
            val chosen = places.firstOrNull {
                account != null && it.place.libraryId == account.uploadLibraryId && it.place.folderId == account.uploadFolderId
            }
            when {
                places.isEmpty() -> failed(book, if (options.allowed) SendFailure.Format else SendFailure.NotAllowed)
                chosen != null -> deliver(book, chosen.place)
                places.size == 1 -> deliver(book, places[0].place)
                else -> serving.update { it.copy(question = SendQuestion.Where(book, places)) }
            }
        }
    }

    private suspend fun deliver(book: Book, place: UploadPlace) {
        val orbit = orbit ?: return
        serving.update { it.copy(question = null, sending = it.sending + (book.id to Sending.Running(0))) }
        val file = fileOf(book)
        if (file == null) {
            failed(book, SendFailure.FileGone)
            return
        }
        val size = file.length().coerceAtLeast(1)
        try {
            orbit.upload(book, file, place) { sent ->
                val percent = (sent * 100 / size).toInt().coerceIn(0, 100)
                // Told from the thread the file goes from, and far more often than the number changes.
                serving.update { now ->
                    if ((now.sending[book.id] as? Sending.Running)?.percent == percent) now else now.copy(sending = now.sending + (book.id to Sending.Running(percent)))
                }
            }
            // The book itself now says that it is on the server.
            serving.update { it.copy(sending = it.sending - book.id) }
            afterChange()
        } catch (e: UploadRefusedException) {
            // A place that is gone is not gone to again without asking.
            if (e.why == UploadRefusal.NoPlace) accounts?.saveUploadPlace(null, null)
            failed(
                book,
                when (e.why) {
                    UploadRefusal.TooLarge -> SendFailure.TooLarge
                    UploadRefusal.Format -> SendFailure.Format
                    UploadRefusal.NameTaken -> SendFailure.NameTaken
                    UploadRefusal.NotABook -> SendFailure.NotABook
                    UploadRefusal.NoRoom -> SendFailure.NoRoom
                    UploadRefusal.NoPlace -> SendFailure.NoPlace
                },
            )
        } catch (e: NotAllowedException) {
            failed(book, SendFailure.NotAllowed)
        } catch (e: DeviceChannelException) {
            failed(book, SendFailure.NoSync)
        } catch (e: IncompatibleServerException) {
            failed(book, SendFailure.NoSync)
        } catch (e: SignedOutException) {
            failed(book, SendFailure.SignedOut)
        } catch (e: IOException) {
            failed(book, SendFailure.Unreachable)
        }
    }

    private fun failed(book: Book, why: SendFailure) =
        serving.update { it.copy(sending = it.sending + (book.id to Sending.Failed(why))) }

    fun setShelf(shelf: Shelf) {
        looking.update { it.copy(group = null) }
        viewModelScope.launch { preferences.update { it.copy(shelf = shelf) } }
    }

    fun setSort(sort: BookSort) {
        viewModelScope.launch { preferences.update { it.copy(sort = sort) } }
    }

    fun setQuery(query: String) = looking.update { it.copy(query = query) }

    /** Shows the books of an author, a series or a collection; null returns to the groups. */
    fun openGroup(group: Group?) = looking.update { it.copy(group = group, query = "") }

    fun setFavorite(book: Book, favorite: Boolean) {
        viewModelScope.launch { library.setFavorite(book.id, favorite) }
    }

    /** Choosing the status a book already has takes it off. */
    fun setStatus(book: Book, status: ReadingStatus) {
        viewModelScope.launch {
            library.setStatus(book.id, if (book.status == status) ReadingStatus.None else status)
            afterChange()
        }
    }

    /** One to five stars; choosing the rating a book already has takes it off. */
    fun setRating(book: Book, rating: Int) {
        viewModelScope.launch {
            library.setRating(book.id, if (book.rating == rating) null else rating)
            afterChange()
        }
    }

    /** Adds a folder the reader picked and looks through it. */
    fun addFolder(uri: String, name: String) = rescan { library.addFolder(uri, name) }

    fun removeFolder(folder: Folder) = rescan {
        library.removeFolder(folder.id)
        // Downloads go back to the app's own storage rather than to a folder that is no longer looked at.
        preferences.update { if (it.downloadsFolder == folder.uri) it.copy(downloadsFolder = null) else it }
    }

    /** Makes a folder the one downloaded books are put into; it is a folder of the library like the rest. */
    fun setDownloadsFolder(uri: String, name: String) = rescan {
        library.addFolder(uri, name)
        preferences.update { it.copy(downloadsFolder = uri, downloadsAsked = true) }
    }

    /** Looks through every folder for new, changed and removed books. */
    fun rescan() = rescan {}

    /**
     * One look at a time, and requests one after another: the look under way
     * is stopped first, so that it cannot put back the books of a folder that
     * [before] takes away, and [before] is done whatever is asked for next.
     */
    private fun rescan(before: suspend () -> Unit) {
        viewModelScope.launch {
            requests.withLock {
                scan?.cancelAndJoin()
                before()
                scan = viewModelScope.launch {
                    looking.update { it.copy(scanning = true) }
                    try {
                        describeKept(getApplication(), library, covers)
                        unpackKept(getApplication(), library)
                        for (folder in library.folders().first()) scanner.scan(folder, sourceOf(folder))
                    } finally {
                        looking.update { it.copy(scanning = false) }
                    }
                    afterScan()
                    // A server that could not be asked when the app started may answer now.
                    if (serving.value.signedIn && serving.value.upload == null) askWhatServerTakes()
                }
            }
        }
    }

    /** Makes a collection, with a book in it if one is given. */
    fun createCollection(name: String, book: Book? = null) {
        viewModelScope.launch {
            val collection = library.collection(name) ?: return@launch
            if (book != null) library.addToCollection(collection.id, book.id)
        }
    }

    fun setInCollection(collection: BookCollection, book: Book, member: Boolean) {
        viewModelScope.launch {
            if (member) library.addToCollection(collection.id, book.id) else library.removeFromCollection(collection.id, book.id)
        }
    }

    fun renameCollection(id: Long, name: String) {
        viewModelScope.launch { library.renameCollection(id, name) }
    }

    fun removeCollection(id: Long) {
        viewModelScope.launch {
            library.removeCollection(id)
            looking.update { if (it.group?.key == id.toString()) it.copy(group = null) else it }
        }
    }

    /** The file to open a book from, see [localCopy]. */
    suspend fun fileOf(book: Book): File? = localCopy(getApplication(), library, book)
}
