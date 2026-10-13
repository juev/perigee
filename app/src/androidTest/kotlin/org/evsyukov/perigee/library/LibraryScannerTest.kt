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
import org.evsyukov.perigee.data.Fb2Archive
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.Folder
import org.evsyukov.perigee.data.FoundBook
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LibraryPreferences
import org.evsyukov.perigee.data.LibraryPrefs
import org.evsyukov.perigee.data.ReadingStatus
import org.evsyukov.perigee.reader.TestBook
import org.junit.Assert.assertArrayEquals
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

    /** A book as an archive in the folder, and the bytes of the book inside. */
    private fun archived(name: String, title: String): Pair<File, ByteArray> {
        val plain = TestBook.write(File(context.cacheDir, "$name.fb2"), title)
        return TestFolder.zip(File(source.dir, "$name.fb2.zip"), plain) to plain.readBytes().also { plain.delete() }
    }

    private fun fingerprint(bytes: ByteArray): String =
        FileHash.partialMd5(File(context.cacheDir, "fingerprinted").apply { writeBytes(bytes) })

    @Test
    fun bookInAnArchiveIsTheBookInside() = runBlocking<Unit> {
        val (archive, inside) = archived("wrapped", "Wrapped Book")

        assertEquals(ScanResult(found = 1, lost = 0, unreadable = 0), scan())

        val book = books().single()
        assertEquals("Wrapped Book", book.title)
        assertEquals("known by the file inside, as a server would know it", fingerprint(inside), book.id)
        assertEquals("fb2", book.format)
        assertTrue("the file in the folder is left as it is", archive.isFile && Fb2Archive.isZip(archive))

        // What is read, and sent to a server, is the book unpacked.
        val copy = checkNotNull(localCopy(context, library, book))
        assertArrayEquals(inside, copy.readBytes())
        assertEquals(book.id, library.open(copy, "", "").id)

        // The same book beside its archive is the same book.
        File(source.dir, "plain.fb2").writeBytes(inside)
        scan()
        assertEquals(1, books().size)
    }

    @Test
    fun archiveOfMoreThanABookStaysAnArchive() = runBlocking<Unit> {
        val plain = TestBook.write(File(context.cacheDir, "first.fb2"), "With A Picture")
        val picture = File(context.cacheDir, "cover.jpg").apply { writeBytes(ByteArray(64)) }
        val archive = TestFolder.zip(File(source.dir, "two.fb2.zip"), plain, picture)

        scan()

        val book = books().single()
        assertEquals(FileHash.partialMd5(archive), book.id)
        assertEquals("fb2.zip", book.format)
    }

    @Test
    fun bookKnownByItsArchiveBecomesTheBookInsideWithWhatWasLeftInIt() = runBlocking<Unit> {
        val (archive, inside) = archived("old", "Read Before")
        // As a version that knew a book by its archive left it.
        val oldId = FileHash.partialMd5(archive)
        library.found(
            folder.id,
            FoundBook(oldId, source.uriOf(archive), archive.length(), archive.lastModified(), "fb2.zip", "Read Before", emptyList(), "en", "", "", false),
        )
        val kept = File(context.cacheDir, "$oldId.fb2.zip").apply { writeBytes(archive.readBytes()) }
        library.open(kept, "Read Before", "")
        library.savePosition(oldId, "/body/p[1]", 0.1f)
        library.savePosition(oldId, "/body/p[7]", 0.4f)
        library.addBookmark(oldId, "/body/p[3]", "marked words", null)

        scan()

        val book = books().single()
        assertEquals(fingerprint(inside), book.id)
        assertEquals("fb2", book.format)
        assertEquals("/body/p[7]", book.xpointer)
        assertEquals("marked words", library.bookmarks(book.id).first().single().text)
        assertNull(library.book(oldId))
        assertFalse("the archive the app kept for reading is not needed any more", kept.exists())
        assertArrayEquals(inside, checkNotNull(localCopy(context, library, book)).readBytes())
        assertEquals("and it is not read over again at every look", ScanResult(found = 0, lost = 0, unreadable = 0), scan())
    }

    @Test
    fun archiveTheAppKeptItselfIsUnpackedWhereItIs() = runBlocking<Unit> {
        val (archive, inside) = archived("kept", "Kept In The App")
        val kept = File(context.cacheDir, "kept-in-app.fb2.zip").apply { writeBytes(archive.readBytes()) }
        archive.delete()
        val old = library.open(kept, "Kept In The App", "")
        library.addBookmark(old.id, "/body/p[3]", "marked words", null)

        unpackKept(context, library)

        val book = books().single()
        assertEquals(fingerprint(inside), book.id)
        assertEquals("fb2", book.format)
        assertArrayEquals(inside, File(book.path).readBytes())
        assertFalse(kept.exists())
        assertEquals("marked words", library.bookmarks(book.id).first().single().text)
        assertEquals("the book the app starts on is still there", book.id, library.lastOpened()?.id)
    }

    @Test
    fun pickedOrDownloadedArchiveIsKeptAsTheBook() = runBlocking<Unit> {
        val (archive, inside) = archived("picked", "Picked By Hand")
        val picked = File(context.cacheDir, "picked.fb2.zip").apply { writeBytes(archive.readBytes()) }

        val book = unpacked(picked)

        assertEquals("picked.fb2", book.name)
        assertArrayEquals(inside, book.readBytes())
        assertFalse(picked.exists())
        val plain = File(context.cacheDir, "as-it-is.epub").apply { writeText("not an archive of a book") }
        assertEquals(plain, unpacked(plain))

        // Downloaded, and kept in the app.
        LibraryPreferences.get(context).update { LibraryPrefs(downloadsFolder = null, downloadsAsked = true) }
        val arrivals = Arrivals(context, library, covers, LibraryPreferences.get(context)) { BookSink { _, _ -> null } }
        val downloaded = File(context.cacheDir, "downloaded.fb2.zip").apply { writeBytes(archive.readBytes()) }
        archive.delete()
        val id = checkNotNull(arrivals.keep(downloaded, "fb2.zip"))
        assertEquals(fingerprint(inside), id)
        val kept = checkNotNull(library.book(id))
        assertEquals("fb2", kept.format)
        assertArrayEquals(inside, File(kept.path).readBytes())
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
