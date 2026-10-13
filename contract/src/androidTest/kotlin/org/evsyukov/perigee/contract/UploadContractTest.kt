package org.evsyukov.perigee.contract

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.Fb2Archive
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.LocalBook
import org.evsyukov.perigee.sync.UploadPlace
import org.evsyukov.perigee.sync.UploadRefusal
import org.evsyukov.perigee.sync.UploadRefusedException
import org.evsyukov.perigee.sync.Uploaded
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sending the reader's books to a real bookOrbit server, and what the server
 * does with a book that looks like one it has. The books are made here and
 * taken off the server again once a test is over.
 */
@RunWith(AndroidJUnit4::class)
class UploadContractTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "upload-contract").apply {
        deleteRecursively()
        mkdirs()
    }
    private lateinit var server: BookOrbitServer
    private lateinit var device: ContractDevice

    /** In every title of a run, so that books of one run are told from those of another, and found to be taken off. */
    private val run = "Run" + System.currentTimeMillis().toString(36)

    @Before
    fun connect() {
        server = ContractServer.connect()
        device = ContractDevice.signedIn(context)
    }

    @After
    fun takeOffTheServer() {
        val ids = listed(run).map { it.getInt("id") }
        if (ids.isNotEmpty()) assertEquals(204, server.jwt("DELETE", "/books", JSONObject().put("bookIds", JSONArray(ids))).code)
    }

    /** The books the server lists by a phrase. */
    private fun listed(phrase: String): List<JSONObject> {
        val query = JSONObject().put("pagination", JSONObject().put("page", 0).put("size", 50)).put("q", phrase)
        val items = server.jwt("POST", "/books/query", query).json().getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }
    }

    /** The books of this run that the server has under exactly this title. */
    private fun titled(title: String): List<JSONObject> = listed(run).filter { it.getString("title") == title }

    /** The server reads what a file says of itself after it has answered; waits until it names the author of a book. */
    private fun awaitAuthor(title: String) {
        repeat(40) {
            if (titled(title).all { it.getJSONArray("authors").length() > 0 }) return
            Thread.sleep(500)
        }
        error("the server never named the author of $title: ${titled(title)}")
    }

    private suspend fun place(): UploadPlace {
        val library = device.orbit.uploadOptions().libraries.single()
        return UploadPlace(library.id, library.folders.single().id)
    }

    private suspend fun onDevice(file: File, format: String, title: String, author: String): Book =
        device.library.keep(file, LocalBook(FileHash.partialMd5(file), format, title, listOf(author), "en", "", "", false))

    private fun epub(name: String, title: String, author: String, text: String): File {
        val file = File(dir, "$name.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            // The first entry of an EPUB says what it is, and is not compressed.
            val type = "application/epub+zip".toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = type.size.toLong()
                    compressedSize = type.size.toLong()
                    crc = CRC32().apply { update(type) }.value
                },
            )
            zip.write(type)
            zip.closeEntry()
            fun add(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            add(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            add(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:title>$title</dc:title><dc:creator>$author</dc:creator><dc:language>en</dc:language>
<dc:identifier id="id">perigee-upload-$name</dc:identifier><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
</metadata>
<manifest><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest>
<spine><itemref idref="c"/></spine></package>""",
            )
            add(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>$title</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">One</a></li></ol></nav></body></html>""",
            )
            add(
                "OEBPS/chapter.xhtml",
                """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml">
<head><title>$title</title></head><body><h1>$title</h1><p>$text</p></body></html>""",
            )
        }
        return file
    }

    private fun fb2(name: String, title: String, first: String, last: String, text: String): File = File(dir, "$name.fb2").apply {
        writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info>
<author><first-name>$first</first-name><last-name>$last</last-name></author><book-title>$title</book-title><lang>ru</lang>
</title-info></description><body><section><p>$text</p></section></body></FictionBook>""",
        )
    }

    private inline fun <reified T : Throwable> failing(crossinline block: suspend () -> Unit): T =
        assertThrows(T::class.java) { runBlocking { block() } }

    @Test
    fun serverSaysWhatItTakesFromTheReader() = runBlocking<Unit> {
        val options = device.orbit.uploadOptions()

        assertTrue(options.allowed)
        assertEquals("500 MB unless the administrator said otherwise", 500L * 1024 * 1024, options.maxBytes)
        assertTrue("got ${options.formats}", options.formats.containsAll(listOf("epub", "fb2", "mobi")))
        assertTrue("of what the app reads, only those three", options.formats.none { it in listOf("fb3", "txt", "rtf", "doc", "docx", "odt", "html", "chm") })
        val library = options.libraries.single()
        assertEquals("Contract", library.name)
        assertEquals("a library that takes whatever the server does", emptySet<String>(), library.formats)
        assertEquals(1, library.folders.size)
        assertEquals(listOf(library), options.librariesFor("epub"))
    }

    @Test
    fun bookSentIsFoundByItsFingerprintAndNotSentTwice() = runBlocking<Unit> {
        val title = "Sent Once $run"
        val file = epub("sent-once", title, "Upload Author", "The text of a book sent once.")
        val book = onDevice(file, "epub", title, "Upload Author")
        device.orbit.matchLibrary(all = true)
        assertNull("not there before it is sent", device.library.book(book.id)?.serverFileId)

        assertEquals(Uploaded.Sent, device.orbit.upload(book, file, place()))

        val sent = checkNotNull(device.library.book(book.id))
        val there = titled(title).single()
        assertEquals(there.getLong("id"), sent.serverBookId)
        assertEquals(there.getJSONArray("files").getJSONObject(0).getLong("id"), sent.serverFileId)
        assertEquals("the file the server keeps is the file that was sent", file.length(), there.getJSONArray("files").getJSONObject(0).getLong("sizeBytes"))

        assertEquals(Uploaded.WasThere, device.orbit.upload(book, file, place()))
        assertEquals("still one book", 1, titled(title).size)

        // A device that never saw the book finds it by the fingerprint alone.
        assertEquals(sent.serverFileId, device.orbit.match(listOf(book.id))[book.id]?.id)
    }

    @Test
    fun fb2WithARussianTitleIsFiledByTheNameItIsSentUnder() = runBlocking<Unit> {
        val title = "Проба пера $run"
        val file = fb2("proba", title, "Иван", "Петров", "Текст книги на русском языке.")
        val book = onDevice(file, "fb2", title, "Иван Петров")

        assertEquals(Uploaded.Sent, device.orbit.upload(book, file, place()))

        assertNotNull(device.library.book(book.id)?.serverFileId)
        // The server reads what an FB2 says of itself only after it has filed it, by the name it came under.
        awaitAuthor(title)
        assertEquals("Иван Петров", titled(title).single().getJSONArray("authors").getString(0))
    }

    @Test
    fun bookKeptInAnArchiveIsSentUnpackedAndFoundByTheFingerprintOfTheBookInside() = runBlocking<Unit> {
        val title = "Из архива $run"
        val plain = fb2("archived", title, "Иван", "Петров", "Книга, которая лежала в архиве.")
        val archive = File(dir, "archived.fb2.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("archived.fb2"))
            zip.write(plain.readBytes())
            zip.closeEntry()
        }
        plain.delete()
        // As the library knows such a book: by the file inside the archive.
        val id = checkNotNull(archive.inputStream().use { Fb2Archive.fingerprint(it) })
        val unpacked = File(dir, "$id.fb2")
        assertTrue(archive.inputStream().use { Fb2Archive.unpack(it, unpacked) })
        val book = device.library.keep(unpacked, LocalBook(id, "fb2", title, listOf("Иван Петров"), "ru", "", "", false))

        assertEquals(Uploaded.Sent, device.orbit.upload(book, unpacked, place()))

        val there = titled(title).single().getJSONArray("files").getJSONObject(0)
        assertEquals("fb2", there.getString("format"))
        assertEquals("the server knows the file by the fingerprint the archive was known by", there.getLong("id"), device.orbit.match(listOf(id))[id]?.id)
        assertEquals(there.getLong("id"), device.library.book(id)?.serverFileId)
    }

    @Test
    fun fileThatIsNotWhatItsNameSaysIsRefused() = runBlocking<Unit> {
        val title = "Not A Mobi $run"
        val file = File(dir, "not-a.mobi").apply { writeText("plain text under the name of a book") }
        val book = onDevice(file, "mobi", title, "Upload Author")

        val refused = failing<UploadRefusedException> { device.orbit.upload(book, file, place()) }

        assertEquals(UploadRefusal.NotABook, refused.why)
        assertNull(device.library.book(book.id)?.serverFileId)
        assertTrue(titled(title).isEmpty())
    }

    @Test
    fun libraryOrFolderThatIsNotThereIsRefused() = runBlocking<Unit> {
        val title = "Nowhere $run"
        val file = epub("nowhere", title, "Upload Author", "A book with nowhere to go.")
        val book = onDevice(file, "epub", title, "Upload Author")

        // Checked against what the server says it has, before anything is sent.
        assertEquals(UploadRefusal.NoPlace, failing<UploadRefusedException> { device.orbit.upload(book, file, UploadPlace(9999)) }.why)
        assertEquals(UploadRefusal.NoPlace, failing<UploadRefusedException> { device.orbit.upload(book, file, UploadPlace(place().libraryId, 9999)) }.why)
        assertTrue(titled(title).isEmpty())
    }

    /**
     * What the server does with a book that looks like one it has. The library
     * of the contract server keeps a book to a folder and names files by the
     * server's own pattern, author / title / title.
     */
    @Test
    fun bookThatLooksLikeOneOnTheServer() = runBlocking<Unit> {
        val title = "Twice Told $run"
        val first = epub("first-edition", title, "Upload Author", "The first edition.")
        val original = onDevice(first, "epub", title, "Upload Author")
        assertEquals(Uploaded.Sent, device.orbit.upload(original, first, place()))
        val serverBook = checkNotNull(device.library.book(original.id)?.serverBookId)
        awaitAuthor(title)

        // The same title and author in another file: pointed out on the device as a likeness, not as the book.
        val second = epub("second-edition", title, "Upload Author", "The second edition, with other words in it.")
        val edition = onDevice(second, "epub", title, "Upload Author")
        assertNotEquals(original.id, edition.id)
        device.orbit.matchLibrary(all = true)
        val marked = checkNotNull(device.library.book(edition.id))
        assertNull(marked.serverFileId)
        assertEquals(serverBook, marked.serverLikeId)
        assertEquals("$title — Upload Author", marked.serverLikeName)

        // Sent all the same, it would get the name the first has: the server refuses, and keeps one book.
        val refused = failing<UploadRefusedException> { device.orbit.upload(marked, second, place()) }
        assertEquals(UploadRefusal.NameTaken, refused.why)
        assertEquals(1, titled(title).size)
        assertNull(device.library.book(edition.id)?.serverFileId)

        // The same title in another format says nothing of itself that this server reads before filing it:
        // it goes under "Unknown Author" and becomes a second book.
        val other = fb2("other-format", title, "Upload", "Author", "The same book as FB2.")
        val asFb2 = onDevice(other, "fb2", title, "Upload Author")
        assertEquals(Uploaded.Sent, device.orbit.upload(asFb2, other, place()))
        assertEquals(2, titled(title).size)
        assertNotEquals(serverBook, device.library.book(asFb2.id)?.serverBookId)

        // Another title is another book.
        val renamedTitle = "Twice Told Again $run"
        val third = epub("renamed", renamedTitle, "Upload Author", "The second edition, with other words in it and another title.")
        val renamed = onDevice(third, "epub", renamedTitle, "Upload Author")
        assertEquals(Uploaded.Sent, device.orbit.upload(renamed, third, place()))
        assertEquals(1, titled(renamedTitle).size)
        assertEquals(3, listed(run).size)
    }
}
