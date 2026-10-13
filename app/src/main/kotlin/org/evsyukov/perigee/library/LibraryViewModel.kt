package org.evsyukov.perigee.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
)

/** The library screen: shelves of books, the folders they come from, collections. */
class LibraryViewModel(
    application: Application,
    private val library: Library,
    private val preferences: LibraryPreferences,
    val covers: Covers,
    /** What is done once the folders were looked through, as asking the server about the books found. */
    private val afterScan: suspend () -> Unit = {},
    private val sourceOf: (Folder) -> BookSource,
) : AndroidViewModel(application) {

    @Suppress("unused") // The constructor the system makes the model with.
    constructor(application: Application) : this(
        application, Library.get(application), LibraryPreferences.get(application), Covers.get(application),
        { Server.matchNewBooks(application) },
        { folder -> TreeSource(application.contentResolver, Uri.parse(folder.uri)) },
    )

    private val scanner = LibraryScanner(application, library, covers)

    private data class Looking(val query: String = "", val group: Group? = null, val scanning: Boolean = false)
    private data class Stored(
        val books: List<Book>, val folders: List<Folder>,
        val collections: List<BookCollection>, val members: List<CollectionBook>,
    )

    private val looking = MutableStateFlow(Looking())
    private var scan: Job? = null
    private val requests = Mutex()

    val state: StateFlow<LibraryState> = combine(
        combine(library.books(), library.folders(), library.collections(), library.collectionBooks(), ::Stored),
        preferences.prefs,
        looking,
    ) { stored, prefs, looking ->
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
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState())

    init {
        // Folders change behind the app's back; every start looks through them again.
        rescan()
    }

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
        viewModelScope.launch { library.setStatus(book.id, if (book.status == status) ReadingStatus.None else status) }
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
                        for (folder in library.folders().first()) scanner.scan(folder, sourceOf(folder))
                    } finally {
                        looking.update { it.copy(scanning = false) }
                    }
                    afterScan()
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
