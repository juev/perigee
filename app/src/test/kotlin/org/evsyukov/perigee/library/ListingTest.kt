package org.evsyukov.perigee.library

import java.util.Locale
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.BookCollection
import org.evsyukov.perigee.data.BookSort
import org.evsyukov.perigee.data.CollectionBook
import org.evsyukov.perigee.data.ReadingStatus
import org.evsyukov.perigee.data.Shelf
import org.junit.Assert.assertEquals
import org.junit.Test

class ListingTest {
    private fun book(
        id: String, title: String = id, authors: List<String> = emptyList(), series: String = "", number: String = "",
        openedAt: Long = 0, addedAt: Long = 0, favorite: Boolean = false, status: ReadingStatus = ReadingStatus.None,
    ) = Book(
        id, path = "", title, authors.joinToString("\n"), xpointer = null, progress = 0f, openedAt = openedAt,
        uri = "content://books/$id", series = series, seriesNumber = number, addedAt = addedAt, favorite = favorite,
        status = status,
    )

    private fun titles(listing: Listing) = (listing as Listing.Books).books.map { it.title }

    private fun groups(listing: Listing) = (listing as Listing.Groups).groups.map { it.name to it.count }

    @Test
    fun recentPutsWhatWasReadLastFirstThenWhatWasAddedLast() {
        val books = listOf(
            book("old read", openedAt = 10, addedAt = 1),
            book("unread old", addedAt = 2),
            book("new read", openedAt = 20, addedAt = 1),
            book("unread new", addedAt = 3),
        )
        assertEquals(
            listOf("new read", "old read", "unread new", "unread old"),
            titles(listing(books, Shelf.All, BookSort.Recent)),
        )
        assertEquals(
            listOf("unread new", "unread old", "new read", "old read"),
            titles(listing(books, Shelf.All, BookSort.Added)),
        )
    }

    @Test
    fun titlesAreOrderedAsWordsNotAsCodes() {
        val books = listOf(book("zebra"), book("Apple"), book("banana"), book("Ёж"), book("Яма"), book("Еда"))
        // The order is that of the reader's language; a desktop JVM knows the Russian one only when asked in Russian.
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ru"))
        try {
            // Capital letters do not come before small ones, and Ё stands with Е, not ahead of the alphabet.
            assertEquals(
                listOf("Apple", "banana", "zebra", "Еда", "Ёж", "Яма"),
                titles(listing(books, Shelf.All, BookSort.Title)),
            )
        } finally {
            Locale.setDefault(locale)
        }
    }

    @Test
    fun byAuthorPutsBooksWithoutOneLast() {
        val books = listOf(
            book("nameless"),
            book("second", authors = listOf("Brown")),
            book("b", authors = listOf("Adams")),
            book("a", authors = listOf("Adams")),
        )
        assertEquals(listOf("a", "b", "second", "nameless"), titles(listing(books, Shelf.All, BookSort.Author)))
    }

    @Test
    fun shelvesFollowWhatTheReaderSaidAboutABook() {
        val books = listOf(
            book("reading", status = ReadingStatus.Reading),
            book("planned", status = ReadingStatus.ToRead, favorite = true),
            book("done", status = ReadingStatus.Read),
            book("plain"),
        )
        assertEquals(listOf("reading"), titles(listing(books, Shelf.Reading, BookSort.Title)))
        assertEquals(listOf("planned"), titles(listing(books, Shelf.ToRead, BookSort.Title)))
        assertEquals(listOf("done"), titles(listing(books, Shelf.Read, BookSort.Title)))
        assertEquals(listOf("planned"), titles(listing(books, Shelf.Favorites, BookSort.Title)))
        assertEquals(4, titles(listing(books, Shelf.All, BookSort.Title)).size)
    }

    @Test
    fun searchLooksAtTitleAuthorAndSeries() {
        val books = listOf(
            book("Dune", authors = listOf("Frank Herbert"), series = "Dune Chronicles"),
            book("Emma", authors = listOf("Jane Austen")),
            book("Herbs of the field"),
        )
        assertEquals(listOf("Dune", "Herbs of the field"), titles(listing(books, Shelf.All, BookSort.Title, query = " herb ")))
        assertEquals(listOf("Dune"), titles(listing(books, Shelf.All, BookSort.Title, query = "chronicles")))
        assertEquals(listOf("Emma"), titles(listing(books, Shelf.All, BookSort.Title, query = "AUSTEN")))
        assertEquals(emptyList<String>(), titles(listing(books, Shelf.All, BookSort.Title, query = "tolstoy")))
    }

    @Test
    fun aBookIsUnderEachOfItsAuthors() {
        val books = listOf(
            book("together", authors = listOf("Ilf", "Petrov")),
            book("alone", authors = listOf("Petrov")),
            book("nameless"),
        )
        assertEquals(listOf("Ilf" to 1, "Petrov" to 2, "" to 1), groups(listing(books, Shelf.Authors, BookSort.Title)))
        assertEquals(listOf("alone", "together"), titles(listing(books, Shelf.Authors, BookSort.Title, group = "Petrov")))
        assertEquals(listOf("nameless"), titles(listing(books, Shelf.Authors, BookSort.Title, group = "")))
        assertEquals(listOf("Petrov" to 2), groups(listing(books, Shelf.Authors, BookSort.Title, query = "pet")))
    }

    @Test
    fun aSeriesIsReadInItsOwnOrder() {
        val books = listOf(
            book("Third", series = "Saga", number = "3", openedAt = 99),
            book("Tenth", series = "Saga", number = "10"),
            book("Interlude", series = "Saga", number = "2.5"),
            book("Second", series = "Saga", number = "2"),
            book("Unnumbered", series = "Saga"),
            book("Other", series = "Cycle", number = "1"),
            book("Single"),
        )
        assertEquals(listOf("Cycle" to 1, "Saga" to 5), groups(listing(books, Shelf.Series, BookSort.Recent)))
        assertEquals(
            listOf("Second", "Interlude", "Third", "Tenth", "Unnumbered"),
            titles(listing(books, Shelf.Series, BookSort.Recent, group = "Saga")),
        )
    }

    @Test
    fun collectionsCountTheBooksThatAreStillThere() {
        val books = listOf(book("a"), book("b"))
        val collections = listOf(BookCollection(1, "Travel", 0), BookCollection(2, "Empty", 0))
        val members = listOf(CollectionBook(1, "a"), CollectionBook(1, "b"), CollectionBook(1, "gone"))
        assertEquals(
            listOf("Travel" to 2, "Empty" to 0),
            groups(listing(books, Shelf.Collections, BookSort.Title, collections = collections, members = members)),
        )
        assertEquals(
            listOf("a", "b"),
            titles(listing(books, Shelf.Collections, BookSort.Title, group = "1", collections = collections, members = members)),
        )
        assertEquals(
            emptyList<String>(),
            titles(listing(books, Shelf.Collections, BookSort.Title, group = "2", collections = collections, members = members)),
        )
    }
}
