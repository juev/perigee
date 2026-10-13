package org.evsyukov.perigee.contract

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.engine.Engine
import org.evsyukov.perigee.opds.OpdsClient
import org.evsyukov.perigee.opds.UnauthorizedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The OPDS client against the catalog of a real bookOrbit server: requirement R5. */
@RunWith(AndroidJUnit4::class)
class OpdsContractTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "opds-contract").apply { deleteRecursively() }
    private lateinit var server: BookOrbitServer
    private lateinit var client: OpdsClient

    @Before
    fun connect() {
        server = ContractServer.connect()
        server.ensureOpds()
        client = OpdsClient(server.opdsUrl, BookOrbitServer.OPDS_USERNAME, BookOrbitServer.OPDS_PASSWORD)
    }

    @Test
    fun catalogIsBrowsedFromItsFirstPageToTheBooks() = runBlocking {
        val root = client.feed()
        val all = root.entries.first { it.title == "All Books" }
        val books = client.feed(checkNotNull(all.feed))
        assertTrue("got ${books.entries.map { it.title }}", books.entries.any { it.title == "Contract Plain" })
        assertTrue("every book offers a file", books.entries.all { it.downloads.isNotEmpty() })
    }

    @Test
    fun longListComesInPages() = runBlocking {
        val titles = ArrayList<String>()
        var page: String? = "${server.opdsUrl}/catalog?size=2"
        var pages = 0
        while (page != null) {
            val feed = client.feed(page)
            titles += feed.entries.map { it.title }
            page = feed.next
            pages++
        }
        assertTrue("$pages pages", pages >= 2)
        assertEquals("no book twice: $titles", titles.size, titles.toSet().size)
        assertTrue(titles.contains("Contract Plain"))
    }

    @Test
    fun searchFindsABookByItsTitle() = runBlocking {
        val root = client.feed()
        val found = client.search(root, client.root, "Plain")
        assertNotNull("the catalog offers a search", found)
        assertEquals(listOf("Contract Plain"), found!!.entries.map { it.title })
    }

    @Test
    fun downloadedBookIsTheBookTheCatalogListed() = runBlocking {
        val book = client.feed("${server.opdsUrl}/catalog").entries.first { it.title == "Contract Plain" }
        val link = book.downloads.first { it.format == "epub" }

        val file = client.download(link, book.title, dir)

        assertTrue("named as a book: ${file.name}", file.name.endsWith(".epub"))
        val said = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { Engine.describe(context, it, file.name) }
        assertEquals("Contract Plain", said?.title)
    }

    @Test
    fun wrongPasswordIsRefused() {
        val stranger = OpdsClient(server.opdsUrl, BookOrbitServer.OPDS_USERNAME, "not-the-password")
        assertThrows(UnauthorizedException::class.java) { runBlocking { stranger.feed() } }
        assertThrows(UnauthorizedException::class.java) { runBlocking { OpdsClient(server.opdsUrl).feed() } }
    }
}
