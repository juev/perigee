package org.evsyukov.perigee.library

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.engine.BookDescription
import org.evsyukov.perigee.reader.TestBook
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A book file picked or shared by another app is copied into the app's
 * storage and joins the library; what the other app does wrong is told, and
 * leaves nothing behind. The engine does not run on the JVM: a book here is
 * what its title says it is. Through the system, with the engine, the same is
 * checked on a device by SharedBooksTest.
 */
@RunWith(RobolectricTestRunner::class)
class BookImportTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val library = Library.inMemory(context)
    private val covers = Covers(File(context.cacheDir, "import-covers").apply { deleteRecursively() })
    private val dir = File(context.cacheDir, "import-books").apply { deleteRecursively() }
    private val sources = File(context.cacheDir, "import-sources").apply { deleteRecursively() }

    /** A file another app shares: the name it gives and what it gives to read. */
    private class Sent(val name: () -> String?, val open: () -> InputStream?)

    private val sent = HashMap<Uri, Sent>()
    private val opened = AtomicInteger()

    /** A picture for the books that have a cover. */
    private val picture = TestFolder.cover(0xFF336699.toInt())

    /** An FB2, plain or the one in an archive, is the book its title says, with a cover if it has one; anything else is no book. */
    private val describe: Describe = { _, file, name ->
        // Not closed: the stream shares the descriptor, which the caller closes.
        val bytes = FileInputStream(file.fileDescriptor).readBytes()
        val text = (if (name.endsWith(".zip")) ZipInputStream(ByteArrayInputStream(bytes)).use { it.nextEntry; it.readBytes() } else bytes).decodeToString()
        TITLE.find(text)?.let {
            BookDescription(it.groupValues[1], listOf("Test Author"), "en", "", "", "", picture.takeIf { "<coverpage>" in text })
        }
    }

    private fun importer(into: File = dir) = BookImport(
        context, library, covers, describe, into,
        nameOf = { sent.getValue(it).name() },
        open = { opened.incrementAndGet(); sent.getValue(it).open() },
    )

    private fun send(name: String?, open: () -> InputStream?) = send({ name }, open)

    private fun send(name: () -> String?, open: () -> InputStream?): Uri =
        Uri.parse("content://sender.example/files/${sent.size}").also { sent[it] = Sent(name, open) }

    private fun send(name: String?, file: File) = send(name) { file.inputStream() }

    private fun take(uri: Uri, importer: BookImport = importer()) = runBlocking { importer.take(uri) }

    private fun books() = runBlocking { library.books().first() }

    /** The files in the app's storage, by name. */
    private fun kept() = dir.list().orEmpty().sorted()

    private fun source(name: String) = File(sources, "${sent.size}/$name").apply { parentFile!!.mkdirs() }

    private fun fb2(title: String) = TestBook.write(source("$title.fb2"), title)

    @Test
    fun sharedBookIsCopiedUnderItsFingerprintAndTheFileSharedIsOnlyRead() {
        val original = fb2("Dune")
        val bytes = original.readBytes()
        val id = FileHash.partialMd5(original)

        val result = take(send("Dune.fb2", original))

        assertEquals(Imported.Kept(id, "Dune", File(dir, "$id.fb2").path), result)
        assertArrayEquals(bytes, File(dir, "$id.fb2").readBytes())
        assertArrayEquals(bytes, original.readBytes())
        assertEquals(listOf("$id.fb2"), kept())
        val book = books().single()
        assertEquals(listOf("Dune", "fb2", File(dir, "$id.fb2").path), listOf(book.title, book.format, book.path))
        assertEquals("joining the library is not opening", 0L, book.openedAt)
    }

    @Test
    fun twoBooksSharedUnderOneNameAreBothKept() {
        val first = fb2("First Of The Name")
        val second = fb2("Second Of The Name")

        val one = take(send("Book.fb2", first)) as Imported.Kept
        val two = take(send("Book.fb2", second)) as Imported.Kept

        assertNotEquals(one.path, two.path)
        assertArrayEquals(first.readBytes(), File(one.path).readBytes())
        assertArrayEquals(second.readBytes(), File(two.path).readBytes())
        assertEquals(setOf("First Of The Name", "Second Of The Name"), books().map { it.title }.toSet())
    }

    @Test
    fun bookSharedAgainIsReadFromItsCopyWithWhatTheReaderLeftInIt() = runBlocking<Unit> {
        val file = fb2("Shared Twice")
        val first = take(send("Shared Twice.fb2", file)) as Imported.Kept
        library.open(File(first.path), "", "")
        library.savePosition(first.id, "/body/p[7]", 0.4f)
        library.addBookmark(first.id, "/body/p[3]", "marked words", null)
        library.addQuote(first.id, "/body/p[4]", "/body/p[5]", "quoted words", 0xFFEE58, null)
        File(first.path).setLastModified(1_000_000L)

        val again = take(send("Renamed by the sender.fb2", file)) as Imported.Kept

        assertEquals(first.id, again.id)
        assertEquals(first.path, again.path)
        assertEquals("the copy that may be open is not written over", 1_000_000L, File(again.path).lastModified())
        assertEquals(listOf("${first.id}.fb2"), kept())
        val book = books().single()
        assertEquals("/body/p[7]", book.xpointer)
        assertEquals(0.4f, book.progress)
        assertTrue("still a book being read", book.openedAt > 0)
        assertEquals("marked words", library.bookmarks(book.id).first().single().text)
        assertEquals("quoted words", library.quotes(book.id).first().single().text)
    }

    @Test
    fun bookOpenedBeforeUnderItsOwnNameIsReadFromThatCopy() = runBlocking<Unit> {
        val old = TestBook.write(File(dir.apply { mkdirs() }, "Old Name.fb2"), "Opened Long Ago")
        val opened = library.open(old, "Opened Long Ago", "")
        library.savePosition(opened.id, "/body/p[2]", 0.2f)

        val again = take(send("Old Name.fb2", source("again.fb2").apply { writeBytes(old.readBytes()) })) as Imported.Kept

        assertEquals(opened.id, again.id)
        assertEquals(old.path, again.path)
        assertEquals(listOf("Old Name.fb2"), kept())
        assertEquals("/body/p[2]", books().single().xpointer)
    }

    @Test
    fun archiveIsKeptAsTheBookInside() {
        val plain = fb2("Zipped Book")
        val inside = plain.readBytes()
        val archive = TestFolder.zip(source("Zipped Book.fb2.zip"), plain)
        val id = FileHash.partialMd5(plain)

        val result = take(send("Zipped Book.fb2.zip", archive))

        assertEquals(Imported.Kept(id, "Zipped Book", File(dir, "$id.fb2").path), result)
        assertArrayEquals(inside, File(dir, "$id.fb2").readBytes())
        assertEquals(listOf("$id.fb2"), kept())
        assertEquals("fb2", books().single().format)
        assertTrue("the archive shared is left as it is", archive.isFile)
    }

    @Test
    fun fileOfAnotherKindIsNotRead() {
        val pdf = source("scan.pdf").apply { writeText("%PDF-1.4") }

        assertEquals(Imported.Failed("Scan.pdf", ImportFailure.NotABook), take(send("Scan.pdf", pdf)))
        assertEquals(Imported.Failed("notes", ImportFailure.NotABook), take(send("notes", pdf)))
        assertEquals(0, opened.get())
        assertEquals(emptyList<String>(), kept())
        assertTrue(books().isEmpty())
    }

    @Test
    fun fileTheAppGivesNoNameIsKnownByItsAddress() {
        val file = fb2("Unnamed Book")
        val uri = Uri.parse("content://sender.example/files/Unnamed%20Book.fb2")
        sent[uri] = Sent({ null }, { file.inputStream() })

        assertEquals("Unnamed Book", (take(uri) as Imported.Kept).title)
    }

    @Test
    fun nameTheAppGivesIsNoPathIntoTheAppsStorage() {
        val file = fb2("Climbing Book")

        val result = take(send("../../databases/reading.fb2", file)) as Imported.Kept

        assertEquals(File(dir, "${result.id}.fb2").path, result.path)
        assertEquals(listOf("${result.id}.fb2"), kept())
    }

    @Test
    fun damagedBookLeavesNothingBehind() {
        val damaged = source("broken.epub").apply { writeText("not a book at all") }

        assertEquals(Imported.Failed("Broken.epub", ImportFailure.Unreadable), take(send("Broken.epub", damaged)))
        assertEquals(emptyList<String>(), kept())
        assertTrue(books().isEmpty())
    }

    @Test
    fun whatTheSendingAppFailsToGiveIsToldApartAndLeavesNothingBehind() {
        val file = fb2("Never Kept")
        val halfWay = object : InputStream() {
            private val from = file.inputStream()
            private var given = 0

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (given > 0) throw IOException("the app went away")
                return from.read(b, off, minOf(len, 1024)).also { given += it }
            }
        }

        assertEquals(Imported.Failed("None.fb2", ImportFailure.NoFile), take(send("None.fb2") { null }))
        assertEquals(Imported.Failed("Gone.fb2", ImportFailure.NoFile), take(send("Gone.fb2") { throw FileNotFoundException() }))
        assertEquals(Imported.Failed("Denied.fb2", ImportFailure.Denied), take(send("Denied.fb2") { throw SecurityException() }))
        val unasked = send({ throw SecurityException() }, { file.inputStream() })
        assertEquals(Imported.Failed(unasked.lastPathSegment!!, ImportFailure.Denied), take(unasked))
        assertEquals(Imported.Failed("Half.fb2", ImportFailure.NotCopied), take(send("Half.fb2") { halfWay }))
        assertEquals(Imported.Failed("Odd.fb2", ImportFailure.NotCopied), take(send("Odd.fb2") { throw IllegalStateException("a provider of its own kind") }))
        assertEquals(emptyList<String>(), kept())
        assertTrue(books().isEmpty())
    }

    @Test
    fun storageThatCannotBeWrittenLeavesTheLibraryAsItWas() {
        val earlier = take(send("Earlier.fb2", fb2("Earlier"))) as Imported.Kept
        val blocked = File(context.cacheDir, "import-blocked").apply { deleteRecursively(); writeText("a file where the folder would be") }

        val result = take(send("Later.fb2", fb2("Later")), importer(File(blocked, "books")))

        assertEquals(Imported.Failed("Later.fb2", ImportFailure.NotCopied), result)
        assertEquals(listOf("Earlier"), books().map { it.title })
        assertEquals(listOf("${earlier.id}.fb2"), kept())
        assertTrue(File(blocked, "books").list().isNullOrEmpty())
    }

    @Test
    fun copyThatCannotBePutInPlaceLeavesNeitherItNorTheCoverReadFromIt() {
        val shown = TestBook.write(source("Shown.fb2"), "Shown", cover = picture)
        val shownId = (take(send("Shown.fb2", shown)) as Imported.Kept).id
        assertTrue("a cover read is kept with its book", covers.file(shownId).isFile)
        val plain = TestBook.write(source("Blocked.fb2"), "Blocked", cover = picture)
        val id = FileHash.partialMd5(plain)
        val archive = TestFolder.zip(source("Blocked.fb2.zip"), plain)
        // Where the book would go, something the copy cannot take the place of.
        File(dir, "$id.fb2").mkdirs()

        assertEquals(Imported.Failed("Blocked.fb2.zip", ImportFailure.NotCopied), take(send("Blocked.fb2.zip", archive)))
        assertEquals(Imported.Failed("Blocked.fb2", ImportFailure.NotCopied), take(send("Blocked.fb2", plain)))
        assertNull(runBlocking { library.book(id) })
        assertFalse(covers.file(id).exists())
        assertEquals(listOf("$id.fb2", "$shownId.fb2").sorted(), kept())
        assertTrue(covers.file(shownId).isFile)
    }

    @Test
    fun bookWhoseCopyCannotBePutBackKeepsItsCover() = runBlocking<Unit> {
        val file = fb2("Lost Its Copy")
        val book = library.open(File(dir.apply { mkdirs() }, "lost.fb2").apply { writeBytes(file.readBytes()) }, "", "")
        library.savePosition(book.id, "/body/p[4]", 0.3f)
        File(book.path).delete()
        File(dir, "${book.id}.fb2").mkdirs()
        val cover = covers.file(book.id).apply { parentFile!!.mkdirs(); writeBytes(picture) }

        assertEquals(Imported.Failed("Lost.fb2", ImportFailure.NotCopied), take(send("Lost.fb2", file)))
        assertArrayEquals("the cover of a book the library has stays", picture, cover.readBytes())
        assertEquals("/body/p[4]", library.book(book.id)!!.xpointer)
    }

    @Test
    fun onlyContentAddressesAreRead() {
        val file = fb2("By Path")

        assertEquals(Imported.Failed("By Path.fb2", ImportFailure.NoFile), take(Uri.fromFile(file)))
        assertEquals(Imported.Failed("book.fb2", ImportFailure.NoFile), take(Uri.parse("https://example.org/book.fb2")))
        assertEquals(0, opened.get())
        assertTrue(books().isEmpty())
    }

    @Test
    fun importCalledOffLeavesNothingBehind() {
        val file = fb2("Called Off")
        lateinit var job: Job
        val uri = send("Called Off.fb2") {
            object : InputStream() {
                private val from = file.inputStream()

                override fun read(): Int = throw UnsupportedOperationException()

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    // The reader leaves the app while the file is being copied.
                    job.cancel()
                    return from.read(b, off, minOf(len, 1024))
                }
            }
        }
        var result: Imported? = null

        runBlocking {
            job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { result = importer().take(uri) }
            job.start()
            job.join()
        }

        assertTrue(job.isCancelled)
        assertNull(result)
        assertEquals(emptyList<String>(), kept())
        assertTrue(books().isEmpty())
    }

    private companion object {
        val TITLE = Regex("<book-title>(.*?)</book-title>")
    }
}
