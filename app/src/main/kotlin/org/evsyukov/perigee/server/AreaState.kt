package org.evsyukov.perigee.server

import org.evsyukov.perigee.sync.CatalogueQuery
import org.evsyukov.perigee.sync.CatalogueSort
import org.evsyukov.perigee.sync.ReadStatuses
import org.evsyukov.perigee.sync.RemoteBook
import org.evsyukov.perigee.sync.RemoteCollection
import org.evsyukov.perigee.sync.Shelf
import org.evsyukov.perigee.sync.ShelfKind

/** The parts of the server's screen, each with its own place, query and pages kept while another is shown. */
enum class Section { Home, Catalogue, Collections }

/** How a shelf of the home page stands. */
sealed interface ShelfState {
    data object Loading : ShelfState

    /** [leftOut] of the shelf's books have no file the app reads and are not shown; they are not made up for. */
    data class Shown(val books: List<RemoteBook>, val leftOut: Int) : ShelfState

    data class Failed(val why: ServerFailure) : ShelfState

    /** The server has no such shelf; the rest of the screen does not depend on it. */
    data object Unsupported : ShelfState
}

internal fun shelfStateOf(shelf: Shelf): ShelfState = when (shelf) {
    is Shelf.Books -> ShelfState.Shown(shelf.books, shelf.leftOut)
    Shelf.Failed -> ShelfState.Failed(ServerFailure.Unreachable)
    Shelf.Unsupported -> ShelfState.Unsupported
}

/**
 * The catalogue's query for the books of a shelf, for a shelf that is one: the books the reader wants to
 * read, the books newest first. The others are samples the server chooses, with no list behind them.
 */
internal fun catalogueOf(kind: ShelfKind): CatalogueQuery? = when (kind) {
    ShelfKind.WantToRead -> CatalogueQuery(status = ReadStatuses.WANT_TO_READ)
    ShelfKind.RecentlyAdded -> CatalogueQuery(sort = CatalogueSort.RecentlyAdded)
    else -> null
}

/** The reader's collections and those shared with them, as last asked. */
data class CollectionsState(
    val list: List<RemoteCollection> = emptyList(),
    val loading: Boolean = false,
    /** Whether the server answered once; an empty list before that is not "no collections". */
    val loaded: Boolean = false,
    val failure: ServerFailure? = null,
)

/** A collection opened to see its books, with what they were looked up by. */
data class CollectionView(
    val collection: RemoteCollection,
    val query: String = "",
    val books: List<RemoteBook> = emptyList(),
    val more: Boolean = false,
    /** How many parts of the books came; the next one asked for is this one, counted from zero. */
    val pages: Int = 0,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val failure: ServerFailure? = null,
    /**
     * The books shown are from before a book went into the collection or out of it, and the parts shown are to
     * be asked for anew; trying again after a failure does that rather than go on from where they were.
     */
    val stale: Boolean = false,
)

/** How the books the server lists beside the open book stand. */
sealed interface RelatedState {
    data object Loading : RelatedState
    data class Shown(val books: List<RemoteBook>) : RelatedState
    data class Failed(val why: ServerFailure) : RelatedState
    data object Unsupported : RelatedState
}

/** What of a book the reader keeps on the server for themselves. */
enum class Personal { Status, Rating, Note, Collections }

/** A change of [what] of the server's book [bookId] the server did not take, and why. */
data class PersonalFailure(val bookId: Long, val what: Personal, val why: ServerFailure)

/** The reader's own collections while they choose which of them the open book is in. */
data class Membership(
    val bookId: Long,
    val owned: List<RemoteCollection> = emptyList(),
    val memberOf: Set<Long> = emptySet(),
    val loading: Boolean = true,
    val failure: ServerFailure? = null,
    /** The collections a change of which is on its way to the server. */
    val busy: Set<Long> = emptySet(),
    /** A new collection, to have the book in it, on its way to the server; why the server did not make it. */
    val creating: Boolean = false,
    val createFailure: ServerFailure? = null,
    /** How many collections were made from here: the name typed for one is kept until it is made. */
    val created: Int = 0,
)
