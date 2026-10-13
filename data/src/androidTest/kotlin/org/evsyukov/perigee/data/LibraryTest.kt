package org.evsyukov.perigee.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
