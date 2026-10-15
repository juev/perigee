package org.evsyukov.perigee.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var clock = 1000L
    private val library = Library.inMemory(context) { clock++ }

    private fun file(name: String, content: String) = File(context.cacheDir, name).apply { writeText(content) }

    @Test
    fun bookKeepsItsPositionAcrossOpenings() = runBlocking {
        val file = file("a.fb2", "first book")
        val book = library.open(file, "Title", "Author")
        assertNull("a new book has no position", book.xpointer)

        library.savePosition(book.id, "/body/p[5]", 0.4f)

        val again = library.open(file, "Title", "Author")
        assertEquals("/body/p[5]", again.xpointer)
        assertEquals(0.4f, again.progress)
    }

    @Test
    fun placesJumpedFromAreKeptAcrossOpeningsUpToTheLastFew() = runBlocking {
        val file = file("jumps.fb2", "jumping book")
        val book = library.open(file, "Title", "Author")
        assertEquals("a new book has nowhere to return to", emptyList<String>(), book.jumpedFrom)

        library.saveJumps(book.id, listOf("/body/p[1]", "/body/p[9]"))
        assertEquals(listOf("/body/p[1]", "/body/p[9]"), library.open(file, "Title", "Author").jumpedFrom)

        val many = (1..Library.MAX_JUMPS + 3).map { "/body/p[$it]" }
        library.saveJumps(book.id, many)
        assertEquals("the oldest are let go", many.takeLast(Library.MAX_JUMPS), library.open(file, "Title", "Author").jumpedFrom)

        library.saveJumps(book.id, emptyList())
        assertEquals(emptyList<String>(), library.open(file, "Title", "Author").jumpedFrom)
    }

    @Test
    fun sameFileInAnotherPlaceIsTheSameBook() = runBlocking {
        val book = library.open(file("b.fb2", "moved book"), "Title", "Author")
        library.savePosition(book.id, "/body/p[2]", 0.1f)

        val moved = library.open(file("b-copy.fb2", "moved book"), "Title", "Author")
        assertEquals(book.id, moved.id)
        assertEquals("/body/p[2]", moved.xpointer)
        assertTrue("the path follows the file", moved.path.endsWith("b-copy.fb2"))
    }

    @Test
    fun lastOpenedIsTheMostRecentBook() = runBlocking {
        val first = library.open(file("c1.fb2", "one"), "One", "")
        val second = library.open(file("c2.fb2", "two"), "Two", "")
        assertEquals(second.id, library.lastOpened()?.id)

        library.open(file("c1.fb2", "one"), "One", "")
        assertEquals(first.id, library.lastOpened()?.id)
    }

    @Test
    fun bookNeverOpenedIsNotTheLastOpened() = runBlocking {
        val folder = library.addFolder("content://tree/books", "Books")
        library.found(folder.id, found("id-1", "content://tree/books/one.fb2"))
        assertNull(library.lastOpened())
    }

    @Test
    fun openingABookMarksItAsBeingRead() = runBlocking {
        val file = file("h.fb2", "status")
        val book = library.open(file, "T", "A")
        assertEquals(ReadingStatus.Reading, book.status)

        library.setStatus(book.id, ReadingStatus.Read)
        assertEquals("a finished book stays finished", ReadingStatus.Read, library.open(file, "T", "A").status)

        library.setStatus(book.id, ReadingStatus.ToRead)
        assertEquals(ReadingStatus.Reading, library.open(file, "T", "A").status)
    }

    @Test
    fun bookThatDoesNotNameItselfKeepsTheNameItHad() = runBlocking {
        val folder = library.addFolder("content://tree/books", "Books")
        val file = file("0123abcd.fb2", "untitled book")
        val id = FileHash.partialMd5(file)
        library.found(folder.id, found(id, "content://tree/books/My notes.fb2", title = "My notes", authors = listOf("Me")))

        val opened = library.open(file, title = "", author = "")
        assertEquals("My notes", opened.title)
        assertEquals(listOf("Me"), opened.authors)

        val alone = library.open(file("lonely.fb2", "no title at all"), title = "", author = "")
        assertEquals("the file name stands in for a title", "lonely", alone.title)
    }

    @Test
    fun foundBooksAreListedWithWhatTheySay() = runBlocking {
        val folder = library.addFolder("content://tree/books", "Books")
        library.found(
            folder.id,
            found(
                "id-1", "content://tree/books/saga2.epub", title = "Second", authors = listOf("Smith, Anna", "John Doe"),
                series = "Saga", seriesNumber = "2", hasCover = true,
            ),
        )
        val book = library.books().first().single()
        assertEquals("Second", book.title)
        assertEquals(listOf("Smith, Anna", "John Doe"), book.authors)
        assertEquals("Smith, Anna, John Doe", book.authorLine)
        assertEquals("Saga", book.series)
        assertEquals("2", book.seriesNumber)
        assertEquals("epub", book.format)
        assertTrue(book.hasCover)
        assertEquals(ReadingStatus.None, book.status)
        assertEquals("", book.path)
        assertEquals(listOf(KnownFile("id-1", "content://tree/books/saga2.epub", 100, 5, "epub")), library.filesIn(folder.id))
    }

    @Test
    fun addingAFolderTwiceKeepsOne() = runBlocking {
        val first = library.addFolder("content://tree/books", "Books")
        val second = library.addFolder("content://tree/books", "Books again")
        assertEquals(first.id, second.id)
        assertEquals(1, library.folders().first().size)
    }

    @Test
    fun bookFoundAgainKeepsWhatTheReaderLeftInIt() = runBlocking {
        val folder = library.addFolder("content://tree/books", "Books")
        val file = file("i.fb2", "found and read")
        val id = FileHash.partialMd5(file)
        library.found(folder.id, found(id, "content://tree/books/i.fb2"))
        library.open(file, "Title", "Author")
        library.savePosition(id, "/body/p[7]", 0.5f)
        library.setFavorite(id, true)
        library.addBookmark(id, "/body/p[3]", "words", null)

        library.found(folder.id, found(id, "content://tree/books/renamed.fb2"))

        val book = library.books().first().single()
        assertEquals("content://tree/books/renamed.fb2", book.uri)
        assertEquals("/body/p[7]", book.xpointer)
        assertTrue(book.favorite)
        assertEquals(file.path, book.path)
        assertEquals(1, library.bookmarks(id).first().size)
    }

    @Test
    fun lostBookLeavesTheLibraryUnlessTheAppHasItsCopy() = runBlocking {
        val folder = library.addFolder("content://tree/books", "Books")
        val read = file("j.fb2", "read book")
        val readId = FileHash.partialMd5(read)
        library.found(folder.id, found(readId, "content://tree/books/read.fb2"))
        library.found(folder.id, found("unread", "content://tree/books/unread.fb2"))
        library.open(read, "Read", "")

        library.lost(folder.id, listOf("content://tree/books/read.fb2", "content://tree/books/unread.fb2"))

        val left = library.books().first().single()
        assertEquals(readId, left.id)
        assertNull(left.uri)
        assertTrue(library.filesIn(folder.id).isEmpty())
    }

    @Test
    fun removingAFolderTakesItsUnopenedBooksAway() = runBlocking {
        val kept = library.addFolder("content://tree/kept", "Kept")
        val removed = library.addFolder("content://tree/removed", "Removed")
        library.found(kept.id, found("a", "content://tree/kept/a.fb2"))
        library.found(removed.id, found("b", "content://tree/removed/b.fb2"))
        library.setFavorite("b", true)

        library.removeFolder(removed.id)
        assertEquals(listOf("a"), library.books().first().map { it.id })
        assertEquals(listOf("Kept"), library.folders().first().map { it.name })

        // The folder comes back: so does the book, as the reader left it.
        val again = library.addFolder("content://tree/removed", "Removed")
        library.found(again.id, found("b", "content://tree/removed/b.fb2"))
        assertTrue(library.books().first().first { it.id == "b" }.favorite)
    }

    @Test
    fun documentHoldsOneBookAtATime() = runBlocking {
        val folder = library.addFolder("content://tree/books", "Books")
        library.found(folder.id, found("old", "content://tree/books/book.fb2", title = "Old edition"))
        library.found(folder.id, found("new", "content://tree/books/book.fb2", title = "New edition"))

        assertEquals(listOf("New edition"), library.books().first().map { it.title })
        assertEquals(listOf("new"), library.filesIn(folder.id).map { it.id })
    }

    @Test
    fun collectionsHoldBooksUntilTheyAreRemoved() = runBlocking {
        val folder = library.addFolder("content://tree/books", "Books")
        library.found(folder.id, found("a", "content://tree/books/a.fb2"))
        library.found(folder.id, found("b", "content://tree/books/b.fb2"))

        assertNull("a collection needs a name", library.collection("   "))
        val travel = library.collection(" Travel ")!!
        assertEquals("Travel", travel.name)
        assertEquals("the same name is the same collection", travel.id, library.collection("Travel")!!.id)
        val work = library.collection("Work")!!

        library.addToCollection(travel.id, "a")
        library.addToCollection(travel.id, "a")
        library.addToCollection(travel.id, "b")
        library.addToCollection(work.id, "b")
        assertEquals(
            setOf(CollectionBook(travel.id, "a"), CollectionBook(travel.id, "b"), CollectionBook(work.id, "b")),
            library.collectionBooks().first().toSet(),
        )

        library.removeFromCollection(travel.id, "b")
        library.renameCollection(work.id, "Travel")
        assertEquals("a name in use is not taken over", listOf("Travel", "Work"), library.collections().first().map { it.name })
        library.renameCollection(work.id, "Office")
        assertEquals(listOf("Office", "Travel"), library.collections().first().map { it.name })

        library.removeCollection(travel.id)
        assertEquals(listOf(CollectionBook(work.id, "b")), library.collectionBooks().first())
        assertEquals("the books stay", 2, library.books().first().size)
    }

    @Test
    fun fileNamesTellTheFormat() {
        assertEquals("epub", Library.formatOf("Book.EPUB"))
        assertEquals("fb2.zip", Library.formatOf("war-and-peace.fb2.zip"))
        assertEquals("fb2", Library.formatOf("a.b.fb2"))
        assertEquals("", Library.formatOf("archive.zip"))
        assertEquals("", Library.formatOf("cover.jpg"))
        assertEquals("", Library.formatOf("epub"))
    }

    @Test
    fun fingerprintOfAnOpenFileIsThatOfTheFile() {
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        val file = File(context.cacheDir, "k.bin").apply { writeBytes(bytes) }
        val viaChannel = file.inputStream().use { FileHash.partialMd5(it.channel) }
        assertEquals(FileHash.partialMd5(file), viaChannel)
    }

    @Test
    fun coverIsKeptSmall() {
        val covers = Covers(File(context.cacheDir, "covers-test").apply { deleteRecursively() })
        val poster = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val bytes = ByteArrayOutputStream().also { poster.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

        assertTrue(covers.save("poster", bytes))
        val kept = BitmapFactory.decodeFile(covers.file("poster").path)
        assertEquals(Covers.HEIGHT, kept.height)
        assertEquals("the shape is kept", Covers.HEIGHT * 2 / 3, kept.width)

        assertFalse("not a picture", covers.save("noise", ByteArray(100) { it.toByte() }))
        assertFalse(covers.file("noise").exists())
    }

    private fun found(
        id: String, uri: String, title: String = "Title", authors: List<String> = listOf("Author"),
        series: String = "", seriesNumber: String = "", hasCover: Boolean = false,
    ) = FoundBook(
        id, uri, fileSize = 100, fileModified = 5, format = Library.formatOf(uri), title = title, authors = authors,
        language = "en", series = series, seriesNumber = seriesNumber, hasCover = hasCover,
    )

    @Test
    fun markingAPageTwiceKeepsOneBookmark() = runBlocking {
        val book = library.open(file("d.fb2", "bookmarks"), "T", "A")
        library.addBookmark(book.id, "/body/p[1]", "First words", "Chapter 1")
        library.addBookmark(book.id, "/body/p[1]", "First words", "Chapter 1")
        library.addBookmark(book.id, "/body/p[9]", "Other words", null)

        val bookmarks = library.bookmarks(book.id).first()
        assertEquals("newest first", listOf("/body/p[9]", "/body/p[1]"), bookmarks.map { it.xpointer })

        library.removeBookmark(bookmarks.first().id)
        assertEquals(listOf("/body/p[1]"), library.bookmarks(book.id).first().map { it.xpointer })
    }

    @Test
    fun quoteCanGetANoteAndAnotherColour() = runBlocking {
        val book = library.open(file("e.fb2", "quotes"), "T", "A")
        val id = library.addQuote(book.id, "/a", "/b", "quoted text", 0xFFFF00, "Chapter 1")

        library.updateQuote(id, "my note", 0x00FF00)
        val quote = library.quotes(book.id).first().single()
        assertEquals("my note", quote.note)
        assertEquals(0x00FF00, quote.color)
        assertEquals("quoted text", quote.text)
        assertTrue(quote.updatedAt > quote.createdAt)

        library.updateQuote(id, "  ", 0x00FF00)
        assertNull("an empty note is no note", library.quotes(book.id).first().single().note)

        library.removeQuote(id)
        assertTrue(library.quotes(book.id).first().isEmpty())
    }

    @Test
    fun marksOfOneBookAreNotShownInAnother() = runBlocking {
        val one = library.open(file("f1.fb2", "book one"), "One", "")
        val two = library.open(file("f2.fb2", "book two"), "Two", "")
        library.addBookmark(one.id, "/x", "text", null)
        library.addQuote(one.id, "/a", "/b", "text", 0xFFFF00, null)
        assertTrue(library.bookmarks(two.id).first().isEmpty())
        assertTrue(library.quotes(two.id).first().isEmpty())
    }

    @Test
    fun fingerprintSamplesTheFileAtGrowingOffsets() {
        val small = file("g1.bin", "abc")
        assertEquals("md5 of the whole content of a short file", "900150983cd24fb0d6963f7d28e17f72", FileHash.partialMd5(small))

        // 3 KB: the samples at 0 and at 1 KB are read, the third kilobyte is not.
        val bytes = ByteArray(3072) { (it % 251).toByte() }
        val big = File(context.cacheDir, "g2.bin").apply { writeBytes(bytes) }
        val tailChanged = File(context.cacheDir, "g3.bin").apply {
            writeBytes(bytes.copyOf().also { it[2500] = 1 })
        }
        val sampleChanged = File(context.cacheDir, "g4.bin").apply {
            writeBytes(bytes.copyOf().also { it[1500] = 1 })
        }
        assertEquals(FileHash.partialMd5(big), FileHash.partialMd5(tailChanged))
        assertNotEquals(FileHash.partialMd5(big), FileHash.partialMd5(sampleChanged))
    }
}
