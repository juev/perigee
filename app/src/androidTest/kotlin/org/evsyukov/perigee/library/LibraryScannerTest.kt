package org.evsyukov.perigee.library

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.Folder
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.ReadingStatus
import org.evsyukov.perigee.reader.TestBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A folder of book files becomes books of the library: requirement R4. */
@RunWith(AndroidJUnit4::class)
class LibraryScannerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val library = Library.inMemory(context)
    private val covers = Covers(File(context.cacheDir, "scan-covers").apply { deleteRecursively() })
    private val notes = File(context.cacheDir, "scan-notes").apply { deleteRecursively() }
    private val scanner = LibraryScanner(context, library, covers, notes)
    private val source = TestFolder(File(context.cacheDir, "scan-folder"))
    private lateinit var folder: Folder

    @Before
    fun addFolder() = runBlocking {
        folder = library.addFolder(source.uriOf(source.dir), "Books")
    }

    private fun scan() = runBlocking { scanner.scan(folder, source) }

    private fun books() = runBlocking { library.books().first() }

    @Test
    fun booksAreFoundWithWhatTheySayInFoldersInsideToo() {
        TestBook.write(
            File(source.dir, "saga.fb2"), "Second Book", author = "Anna Smith", series = "Long Saga", seriesNumber = 2,
            cover = TestFolder.cover(Color.RED),
        )
        val inner = File(source.dir, "more/deeper").apply { mkdirs() }
        TestBook.write(File(inner, "plain.fb2"), "Plain Book")
        File(source.dir, "picture.jpg").writeBytes(ByteArray(64))
        File(source.dir, "empty.epub").writeBytes(ByteArray(0))

        assertEquals(ScanResult(found = 2, lost = 0, unreadable = 1), scan())

        val found = books().associateBy { it.title }
        assertEquals(setOf("Second Book", "Plain Book"), found.keys)
        val saga = found.getValue("Second Book")
        assertEquals(listOf("Anna Smith"), saga.authors)
        assertEquals("Long Saga", saga.series)
        assertEquals("2", saga.seriesNumber)
        assertEquals("fb2", saga.format)
        assertEquals(source.uriOf(File(source.dir, "saga.fb2")), saga.uri)
        assertEquals("nothing is copied until the book is opened", "", saga.path)
        assertEquals(ReadingStatus.None, saga.status)

        assertTrue(saga.hasCover)
        val picture = BitmapFactory.decodeFile(covers.file(saga.id).path)
        // JPEG does not keep a colour to the last bit.
        assertTrue("the cover is red: ${Integer.toHexString(picture.getPixel(5, 5))}", Color.red(picture.getPixel(5, 5)) > 240)
        assertFalse(found.getValue("Plain Book").hasCover)
        assertFalse(covers.file(found.getValue("Plain Book").id).exists())
    }

    @Test
    fun fileWithoutATitleIsNamedByItsFile() {
        File(source.dir, "Shopping list.txt").writeText("Bread, milk and a long book for the evening.\n".repeat(20))
        scan()
        assertEquals(listOf("Shopping list"), books().map { it.title })
    }

    @Test
    fun unchangedFilesAreNotReadAgain() {
        TestBook.write(File(source.dir, "a.fb2"), "First")
        TestBook.write(File(source.dir, "b.fb2"), "Second")
        assertEquals(2, scan()?.found)

        assertEquals(ScanResult(found = 0, lost = 0, unreadable = 0), scan())
        assertEquals(2, books().size)
    }

    @Test
    fun changedFileBecomesTheBookItNowIs() {
        val file = TestBook.write(File(source.dir, "a.fb2"), "First edition")
        scan()
        val old = books().single()

        TestBook.write(file, "Second edition, revised")
        assertEquals(ScanResult(found = 1, lost = 0, unreadable = 0), scan())

        val now = books().single()
        assertEquals("Second edition, revised", now.title)
        assertTrue("another text is another book", now.id != old.id)
    }

    @Test
    fun removedFileLeavesTheLibrary() {
        TestBook.write(File(source.dir, "stays.fb2"), "Stays")
        val leaves = TestBook.write(File(source.dir, "leaves.fb2"), "Leaves")
        scan()

        leaves.delete()
        assertEquals(ScanResult(found = 0, lost = 1, unreadable = 0), scan())
        assertEquals(listOf("Stays"), books().map { it.title })
    }

    @Test
    fun folderThatCannotBeReachedKeepsItsBooks() {
        TestBook.write(File(source.dir, "a.fb2"), "Still mine")
        scan()

        source.gone = true
        assertNull(scan())
        assertEquals(listOf("Still mine"), books().map { it.title })
    }

    /** What is left when the app dies while reading a file. */
    private fun diedReading(file: File) {
        notes.mkdirs()
        File(notes, "reading").writeText("${source.uriOf(file)} ${file.length()} ${file.lastModified()}")
    }

    @Test
    fun fileTheAppDiedOnOnceGetsAnotherTry() {
        // The app may as well have been swiped away in the middle of a look.
        diedReading(TestBook.write(File(source.dir, "unlucky.fb2"), "Unlucky"))
        assertEquals(ScanResult(found = 1, lost = 0, unreadable = 0), scan())
        assertEquals(listOf("Unlucky"), books().map { it.title })
    }

    @Test
    fun fileTheAppDiedOnTwiceIsPassedByUntilItChanges() {
        val good = TestBook.write(File(source.dir, "good.fb2"), "Good")
        val bad = TestBook.write(File(source.dir, "bad.fb2"), "Bad")
        diedReading(bad)
        scanner.run { runBlocking { scan(folder, BookSource { emptyList() }) } }
        diedReading(bad)

        assertEquals(ScanResult(found = 1, lost = 0, unreadable = 1), scan())
        assertEquals(listOf("Good"), books().map { it.title })
        assertEquals("and the next time as well", ScanResult(found = 0, lost = 0, unreadable = 1), scan())

        TestBook.write(bad, "Bad, repaired")
        assertEquals(1, scan()?.found)
        assertEquals(setOf("Good", "Bad, repaired"), books().map { it.title }.toSet())
        assertTrue(good.isFile)
    }

    @Test
    fun foundBookOpenedFromACopyIsTheSameBook() = runBlocking {
        val file = TestBook.write(File(source.dir, "a.fb2"), "One and the same")
        scanner.scan(folder, source)
        val found = library.books().first().single()

        val copy = File(context.cacheDir, "scan-copy.fb2").apply { writeBytes(file.readBytes()) }
        val opened = library.open(copy, title = "", author = "")

        assertEquals(found.id, opened.id)
        assertEquals("One and the same", opened.title)
        assertEquals(ReadingStatus.Reading, opened.status)
        assertEquals(1, library.books().first().size)
    }
}
