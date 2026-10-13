package org.evsyukov.perigee.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** An FB2 in a zip archive is the FB2: known by its fingerprint, and read unpacked. */
@RunWith(AndroidJUnit4::class)
class Fb2ArchiveTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "archive-test").apply {
        deleteRecursively()
        mkdirs()
    }
    private var clock = 1000L
    private val library = Library.inMemory(context) { clock++ }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File = File(dir, name).apply {
        ZipOutputStream(outputStream()).use { zip ->
            for ((entry, bytes) in entries) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun bytes(size: Int) = ByteArray(size) { (it * 31 + it / 977).toByte() }

    @Test
    fun fingerprintOfAStreamIsThatOfTheFile() {
        // Around every place a sample begins or ends, and a file that ends inside one.
        for (size in listOf(0, 1, 1023, 1024, 1025, 2047, 2048, 4096, 4100, 5120, 16_384, 17_000, 65_536, 70_000, 262_144, 300_000, 1_100_000)) {
            val file = File(dir, "plain-$size").apply { writeBytes(bytes(size)) }
            assertEquals("of $size bytes", FileHash.partialMd5(file), file.inputStream().use { FileHash.partialMd5(it) })
        }
    }

    @Test
    fun archiveOfOneBookIsKnownByTheBookInside() {
        val book = bytes(70_000)
        val plain = File(dir, "book.fb2").apply { writeBytes(book) }
        val archive = zip("book.fb2.zip", "Some Name.FB2" to book)

        assertEquals(FileHash.partialMd5(plain), archive.inputStream().use { Fb2Archive.fingerprint(it) })
        assertTrue(Fb2Archive.isZip(archive))
        assertFalse(Fb2Archive.isZip(plain))

        val target = File(dir, "unpacked.fb2")
        assertTrue(archive.inputStream().use { Fb2Archive.unpack(it, target) })
        assertArrayEquals(book, target.readBytes())
    }

    @Test
    fun archiveThatIsNotOneBookIsLeftAlone() {
        val two = zip("two.fb2.zip", "one.fb2" to bytes(100), "two.fb2" to bytes(200))
        val withPicture = zip("with.fb2.zip", "book.fb2" to bytes(100), "cover.jpg" to bytes(50))
        val other = zip("other.fb2.zip", "readme.txt" to bytes(100))
        val notZip = File(dir, "not.fb2.zip").apply { writeBytes(bytes(300)) }
        val empty = zip("empty.fb2.zip")
        val target = File(dir, "nothing.fb2")
        for (file in listOf(two, withPicture, other, notZip, empty)) {
            assertNull(file.name, file.inputStream().use { Fb2Archive.fingerprint(it) })
            assertFalse(file.name, file.inputStream().use { Fb2Archive.unpack(it, target) })
            assertFalse("nothing is left behind by ${file.name}", target.exists() || File(target.path + ".part").exists())
        }
        // A folder in the archive beside the book is no second file.
        val inFolder = zip("folder.fb2.zip", "books/" to ByteArray(0), "books/one.fb2" to bytes(100))
        assertEquals(FileHash.partialMd5(File(dir, "one").apply { writeBytes(bytes(100)) }), inFolder.inputStream().use { Fb2Archive.fingerprint(it) })
    }

    @Test
    fun whatTheReaderLeftInABookMovesWithItToItsNewFingerprint() = runBlocking<Unit> {
        val file = File(dir, "old.fb2.zip").apply { writeText("an archive as it was kept") }
        val old = library.open(file, "Archived", "An Author")
        library.savePosition(old.id, "/body/p[1]", 0.1f)
        library.savePosition(old.id, "/body/p[9]", 0.6f)
        library.setRating(old.id, 4)
        library.setFavorite(old.id, true)
        library.addBookmark(old.id, "/body/p[3]", "words", "One")
        library.addQuote(old.id, "/a", "/b", "quoted", 0xFFFF00, null, "a note")
        val shelf = checkNotNull(library.collection("Shelf"))
        library.addToCollection(shelf.id, old.id)
        library.addPageRead(old.id, 3, 20, 1_000, 30)
        library.onServer(old.id, 7, 70)

        assertEquals(file.path, library.reidentify(old.id, "new-id", "fb2"))

        assertNull(library.book(old.id))
        val now = checkNotNull(library.book("new-id"))
        assertEquals("Archived", now.title)
        assertEquals("fb2", now.format)
        assertEquals("/body/p[9]", now.xpointer)
        assertEquals(0.6f, now.progress)
        assertEquals(4, now.rating)
        assertTrue(now.favorite)
        assertEquals(ReadingStatus.Reading, now.status)
        assertEquals("the app has no copy of the new file yet", "", now.path)
        assertEquals("words", library.bookmarks("new-id").first().single().text)
        assertEquals("a note", library.quotes("new-id").first().single().note)
        assertEquals("new-id", library.collectionBooks().first().single().bookId)
        assertNull("the server knew the archive, if anything: it is asked about the book anew", now.serverFileId)
        assertEquals(0L, now.serverCheckedAt)
        assertTrue("the place is still to be told to a server", now.placeUnsent)
        assertTrue(now.marksUnsent)
    }

    @Test
    fun bookAlreadyReadUnderTheNewFingerprintKeepsItsOwnPlace() = runBlocking<Unit> {
        val archive = library.open(File(dir, "a.fb2.zip").apply { writeText("archive") }, "Archived", "")
        library.savePosition(archive.id, "/body/p[1]", 0.1f)
        library.savePosition(archive.id, "/body/p[2]", 0.2f)
        library.addBookmark(archive.id, "/body/p[2]", "from the archive", null)
        val plainFile = File(dir, "a.fb2").apply { writeText("the book itself") }
        val plain = library.open(plainFile, "Plain", "")
        library.savePosition(plain.id, "/body/p[1]", 0.1f)
        library.savePosition(plain.id, "/body/p[8]", 0.8f)

        library.reidentify(archive.id, plain.id, "fb2")

        val now = checkNotNull(library.book(plain.id))
        assertEquals("/body/p[8]", now.xpointer)
        assertEquals(plainFile.path, now.path)
        assertEquals("bookmarks are not lost either way", "from the archive", library.bookmarks(plain.id).first().single().text)
        assertEquals(1, library.books().first().size)
    }

    @Test
    fun bookNeverOpenedUnderTheNewFingerprintTakesTheOldOnesPlace() = runBlocking<Unit> {
        val archive = library.open(File(dir, "b.fb2.zip").apply { writeText("archive b") }, "Archived", "")
        library.savePosition(archive.id, "/body/p[1]", 0.1f)
        library.savePosition(archive.id, "/body/p[5]", 0.5f)
        val folder = library.addFolder("content://tree/books", "Books")
        library.found(folder.id, FoundBook("plain-id", "content://tree/books/b.fb2", 10, 10, "fb2", "Plain", emptyList(), "", "", "", false))

        library.reidentify(archive.id, "plain-id", "fb2")

        val now = checkNotNull(library.book("plain-id"))
        assertEquals("/body/p[5]", now.xpointer)
        assertEquals("it still lies where it was found", "content://tree/books/b.fb2", now.uri)
        assertEquals(1, library.books().first().size)
    }
}
