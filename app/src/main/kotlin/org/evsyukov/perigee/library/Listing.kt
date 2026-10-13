package org.evsyukov.perigee.library

import java.text.Collator
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.BookCollection
import org.evsyukov.perigee.data.BookSort
import org.evsyukov.perigee.data.CollectionBook
import org.evsyukov.perigee.data.ReadingStatus
import org.evsyukov.perigee.data.Shelf

/** An author, a series or a collection with the number of books under it. [name] is empty for books that name no author. */
data class Group(val key: String, val name: String, val count: Int)

/** What a shelf shows: books, or the groups to pick one from. */
sealed interface Listing {
    data class Books(val books: List<Book>) : Listing
    data class Groups(val groups: List<Group>) : Listing
}

val Shelf.grouped: Boolean get() = this == Shelf.Authors || this == Shelf.Series || this == Shelf.Collections

/**
 * The books or groups of a shelf. [group] is the key of the group that was
 * picked on a grouped shelf, null while the groups themselves are shown.
 * [query] narrows whatever is shown; books match by title, author or series.
 */
fun listing(
    books: List<Book>,
    shelf: Shelf,
    sort: BookSort,
    query: String = "",
    group: String? = null,
    collections: List<BookCollection> = emptyList(),
    members: List<CollectionBook> = emptyList(),
): Listing {
    val words = query.trim()
    if (shelf.grouped && group == null) {
        val groups = when (shelf) {
            Shelf.Authors -> books.flatMap { book -> book.authors.ifEmpty { listOf("") } }.counted()
            Shelf.Series -> books.filter { it.series.isNotEmpty() }.map { it.series }.counted()
            else -> {
                val present = books.mapTo(HashSet()) { it.id }
                val sizes = members.filter { it.bookId in present }.groupingBy { it.collectionId }.eachCount()
                collections.map { Group(it.id.toString(), it.name, sizes[it.id] ?: 0) }
            }
        }
        return Listing.Groups(groups.filter { it.name.contains(words, ignoreCase = true) })
    }
    val onShelf = when (shelf) {
        Shelf.All -> books
        Shelf.Reading -> books.filter { it.status == ReadingStatus.Reading }
        Shelf.Favorites -> books.filter { it.favorite }
        Shelf.ToRead -> books.filter { it.status == ReadingStatus.ToRead }
        Shelf.Read -> books.filter { it.status == ReadingStatus.Read }
        Shelf.Authors -> books.filter { if (group.isNullOrEmpty()) it.authors.isEmpty() else group in it.authors }
        Shelf.Series -> books.filter { it.series == group }
        Shelf.Collections -> {
            val inGroup = members.filter { it.collectionId.toString() == group }.mapTo(HashSet()) { it.bookId }
            books.filter { it.id in inGroup }
        }
    }
    val matching = if (words.isEmpty()) {
        onShelf
    } else {
        onShelf.filter { book ->
            book.title.contains(words, ignoreCase = true) || book.author.contains(words, ignoreCase = true) ||
                book.series.contains(words, ignoreCase = true)
        }
    }
    // A series is read in its own order whatever the reader sorts other shelves by.
    return Listing.Books(matching.sortedWith(if (shelf == Shelf.Series) inSeries else order(sort)))
}

private fun List<String>.counted(): List<Group> =
    groupingBy { it }.eachCount().map { (name, count) -> Group(name, name, count) }
        .sortedWith(compareBy<Group> { it.name.isEmpty() }.thenBy(collator) { it.name })

// Letters are compared as the reader's language orders them, not by their codes.
private val collator: Collator get() = Collator.getInstance().apply { strength = Collator.SECONDARY }

private val byTitle: Comparator<Book> get() = compareBy(collator) { it.title }

private val inSeries: Comparator<Book>
    get() = compareBy<Book> { it.seriesNumber.toFloatOrNull() ?: Float.MAX_VALUE }.then(byTitle)

private fun order(sort: BookSort): Comparator<Book> = when (sort) {
    BookSort.Recent -> compareByDescending<Book> { it.openedAt }.thenByDescending { it.addedAt }.then(byTitle)
    BookSort.Title -> byTitle
    // Books without an author go last, not first.
    BookSort.Author -> compareBy<Book> { it.author.isEmpty() }.thenBy(collator) { it.authorLine }.then(byTitle)
    BookSort.Added -> compareByDescending<Book> { it.addedAt }.then(byTitle)
}
