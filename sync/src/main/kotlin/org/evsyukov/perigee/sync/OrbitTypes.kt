package org.evsyukov.perigee.sync

// What bookOrbit offers beyond a list of books, as the app shows it: the shelves of its home page, the
// reader's collections, books it suggests beside another, and how the catalogue is narrowed. These are
// bookOrbit's own and are not part of SyncBackend.

/** A shelf of the server's home page, under the name the server knows it by. */
enum class ShelfKind(val type: String) {
    ContinueReading("continue-reading"),
    WantToRead("want-to-read"),
    RecentlyAdded("recently-added"),
    UpNext("up-next-in-series"),
    Random("random"),
    ;

    companion object {
        /** The shelves shown, in the order shown. */
        val DEFAULT = listOf(ContinueReading, WantToRead, RecentlyAdded, UpNext, Random)
    }
}

/** What the server put on a shelf. */
sealed interface Shelf {
    /**
     * The books on the shelf that the app reads, in the server's order. [leftOut] are on it too, with no file
     * the app reads: the shelf is a sample the server chose, and it is not filled up from elsewhere.
     */
    data class Books(val books: List<RemoteBook>, val leftOut: Int = 0) : Shelf

    /** The server could not fill this shelf; the others may well be filled. */
    data object Failed : Shelf

    /** The server has no such shelf, as one of a version before it. */
    data object Unsupported : Shelf
}

/** How the catalogue is ordered: by one of the server's sort fields. */
enum class CatalogueSort(val field: String, val dir: String) {
    Title("title", "asc"),
    Author("author", "asc"),
    RecentlyAdded("addedAt", "desc"),
    Series("series", "asc"),
}

/**
 * What the catalogue is looked up by. The server matches each: a phrase anywhere, the reader's status, an
 * author by part of the name, a series by part of its name, a genre and a library by the whole name. An
 * empty field matches everything.
 */
data class CatalogueQuery(
    val text: String = "",
    /** In the server's word, as "want_to_read"; see [ReadStatuses]. */
    val status: String = "",
    val author: String = "",
    val series: String = "",
    val genre: String = "",
    val library: String = "",
    val sort: CatalogueSort = CatalogueSort.Title,
) {
    /** Whether anything but the phrase and the order narrows it. */
    val filtered: Boolean get() = listOf(status, author, series, genre, library).any { it.isNotBlank() }
}

/** A part of a list of books, and whether the server has more of it. */
data class BookPart(val books: List<RemoteBook>, val more: Boolean)

/** A collection of books on the server: the reader's own, or one another reader shares. */
data class RemoteCollection(
    val id: Long,
    val name: String,
    /** The server's name for the collection's icon; never empty, the server wants one. */
    val icon: String,
    val description: String,
    val isPublic: Boolean,
    /** Whether the reader made it; only theirs can be changed. */
    val isOwner: Boolean,
    /** How many books the reader sees in it. */
    val bookCount: Int,
)

/** Books the server lists beside another. */
enum class Related(val path: String) {
    /** By what they are about: the server needs a likeness worked out for the book, and has none on every server. */
    Similar("recommendations"),
    Author("author-books"),
    Series("series-books"),
}

/** The books the server listed beside another that the app may read; [leftOut] are audiobooks or comics. */
data class RelatedBooks(val books: List<RemoteBook>, val leftOut: Int = 0)

/** The reader's statuses of a book, in the server's words. */
object ReadStatuses {
    const val UNREAD = "unread"
    const val WANT_TO_READ = "want_to_read"
    const val READING = "reading"
    const val ON_HOLD = "on_hold"
    const val READ = "read"
    const val ABANDONED = "abandoned"

    /** Those the reader can choose: the ones of 3.3.0. Later servers have more, which are shown as they are and kept. */
    val CHOOSABLE = listOf(UNREAD, WANT_TO_READ, READING, ON_HOLD, READ, ABANDONED)
}
