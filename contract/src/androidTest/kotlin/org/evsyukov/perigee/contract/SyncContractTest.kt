package org.evsyukov.perigee.contract

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.ReadingStatus
import org.evsyukov.perigee.engine.EngineDocument
import org.evsyukov.perigee.sync.SyncReport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rounds of syncing against a real bookOrbit server: the reader's place both
 * ways, status and rating both ways, and the pages read. Requirements R7 and R9.
 */
@RunWith(AndroidJUnit4::class)
class SyncContractTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "sync-contract")
    private lateinit var server: BookOrbitServer
    private lateinit var device: ContractDevice

    @Before
    fun connect() {
        server = ContractServer.connect()
        device = ContractDevice.signedIn(context)
    }

    /** A book of the server, downloaded and opened on the device. */
    private suspend fun onDevice(title: String): Pair<Book, File> {
        val remote = device.orbit.books(title, 0).books.single { it.title == title }
        val folder = File(dir, title.replace(' ', '-')).apply { deleteRecursively() }
        val file = device.orbit.download(remote.files.first(), title, folder)
        val book = device.library.open(file, title, "")
        device.orbit.matchLibrary(all = true)
        return checkNotNull(device.library.book(book.id)) to file
    }

    private suspend fun fresh(book: Book): Book = checkNotNull(device.library.book(book.id))

    /** The server orders places by whole seconds; what follows must fall into a later one. */
    private fun nextSecond() = Thread.sleep(1100)

    /** What a device is told of the book's latest place. */
    private fun latest(book: Book): JSONObject = server.koreader("GET", "/koreader/syncs/progress/${book.id}").json()

    /** What the server's web pages know of the reader and the book. */
    private fun card(title: String): JSONObject {
        val query = JSONObject().put("pagination", JSONObject().put("page", 0).put("size", 50)).put("q", title)
        val items = server.jwt("POST", "/books/query", query).json().getJSONArray("items")
        return (0 until items.length()).map(items::getJSONObject).single { it.getString("title") == title }
    }

    @Test
    fun placeGoesToTheServerAndALaterOneFromTheWebReaderIsOfferedNotOverwritten() = runBlocking<Unit> {
        val (book, file) = onDevice("Contract Media Css")
        assertNotNull("the server has this file", book.serverFileId)
        EngineDocument.open(context, file, 720, 1280).use { doc ->
            nextSecond()
            // Where the book opens is not a move; the first page turn is.
            device.library.savePosition(book.id, doc.xpointer(), doc.progress)
            assertTrue(doc.goToPage(doc.pageCount / 4))
            val here = doc.xpointer()
            device.library.savePosition(book.id, here, doc.progress)

            val sent = device.orbit.sync()
            assertEquals(1, sent.placesSent)

            // The web reader sees the place as its own kind of pointer and the same share of the book.
            val stored = server.jwt("GET", "/books/files/${book.serverFileId}/progress").json()
            assertFalse("the server made a CFI of $here", stored.isNull("cfi"))
            assertEquals(doc.progress * 100.0, stored.getDouble("percentage"), 0.01)
            assertEquals(device.accounts.current()!!.deviceId, latest(book).getString("device_id"))
            assertEquals("the app's own place coming back is not news", SyncReport(), device.orbit.syncPlace(book.id))

            // The reader reads on here without the server hearing of it…
            nextSecond()
            assertTrue(doc.goToPage(doc.pageCount / 3))
            device.library.savePosition(book.id, doc.xpointer(), doc.progress)
            val unsent = doc.xpointer()

            // …and then further still in the web reader, which saves a CFI. The CFI of that
            // place is learnt the way the server makes one: from a device that is there.
            assertTrue(doc.goToPage(doc.pageCount * 3 / 4))
            val webPage = doc.currentPage
            val webShare = doc.progress.toDouble()
            nextSecond()
            val other = JSONObject().put("document", book.id).put("percentage", webShare).put("progress", doc.xpointer())
                .put("device", "Other").put("device_id", "contract-other").put("timestamp", System.currentTimeMillis() / 1000)
            assertEquals(200, server.koreader("PUT", "/koreader/syncs/progress", other).code)
            val cfi = server.jwt("GET", "/books/files/${book.serverFileId}/progress").json().getString("cfi")
            nextSecond()
            val save = server.jwt("POST", "/books/files/${book.serverFileId}/progress", JSONObject().put("percentage", webShare * 100).put("cfi", cfi))
            assertTrue("web save: $save", save.code in 200..204)

            val round = device.orbit.sync()

            assertEquals("the later place is offered", 1, round.offers)
            assertEquals("and the one here is not sent over it", 0, round.placesSent)
            assertEquals("web", latest(book).getString("device"))
            assertEquals(unsent, fresh(book).xpointer)
            val offer = checkNotNull(device.library.pendingOffer(book.id))
            assertEquals("web", offer.device)
            assertEquals(webShare.toFloat(), offer.progress, 0.001f)
            assertTrue("the engine finds the offered place ${offer.xpointer}", doc.goToXPointer(checkNotNull(offer.xpointer)))
            assertEquals("on the page the web reader was at", webPage, doc.currentPage)

            // The reader goes there; from then on it is their place here, and the server is told so.
            nextSecond()
            device.library.savePosition(book.id, doc.xpointer(), doc.progress)
            device.library.settleOffer(book.id)
            assertEquals(1, device.orbit.sync().placesSent)
            assertEquals(device.accounts.current()!!.deviceId, latest(book).getString("device_id"))
            assertNull(device.library.pendingOffer(book.id))
        }
    }

    @Test
    fun readerWhoStaysSendsTheirOwnPlaceOverTheOtherOne() = runBlocking<Unit> {
        val (book, file) = onDevice("Contract Spine NonXhtml")
        EngineDocument.open(context, file, 720, 1280).use { doc ->
            nextSecond()
            device.library.savePosition(book.id, doc.xpointer(), doc.progress)
            assertTrue(doc.goToPage(doc.pageCount / 2))
            val here = doc.xpointer()
            device.library.savePosition(book.id, here, doc.progress)

            // Another device reports a place later than that.
            nextSecond()
            assertTrue(doc.goToPage(doc.pageCount / 5))
            val other = JSONObject().put("document", book.id).put("percentage", doc.progress.toDouble()).put("progress", doc.xpointer())
                .put("device", "Kindle").put("device_id", "contract-other").put("timestamp", System.currentTimeMillis() / 1000)
            assertEquals(200, server.koreader("PUT", "/koreader/syncs/progress", other).code)

            assertEquals(1, device.orbit.sync().offers)
            assertEquals("Kindle", device.library.pendingOffer(book.id)?.device)
            assertEquals("contract-other", latest(book).getString("device_id"))

            // The reader says no. The place here was never sent, and now it is theirs to keep.
            device.library.settleOffer(book.id)
            nextSecond()
            val round = device.orbit.sync()
            assertEquals(1, round.placesSent)
            assertEquals(0, round.offers)
            assertEquals(here, latest(book).getString("progress"))
        }
    }

    @Test
    fun fb2PlaceIsKeptWordForWordForOtherDevices() = runBlocking<Unit> {
        val (book, file) = onDevice("Contract Fb2")
        EngineDocument.open(context, file, 720, 1280).use { doc ->
            nextSecond()
            device.library.savePosition(book.id, doc.xpointer(), doc.progress)
            assertTrue(doc.goToPage(doc.pageCount / 2))
            device.library.savePosition(book.id, doc.xpointer(), doc.progress)

            assertEquals(1, device.orbit.sync().placesSent)

            assertEquals(doc.xpointer(), latest(book).getString("progress"))
            // The web reader has only how far into the book that is.
            val stored = server.jwt("GET", "/books/files/${book.serverFileId}/progress").json()
            assertTrue(stored.isNull("cfi"))
            assertEquals(doc.progress * 100.0, stored.getDouble("percentage"), 0.01)
        }
    }

    @Test
    fun statusAndRatingGoBothWays() = runBlocking<Unit> {
        val title = "Contract Plain"
        val (book, _) = onDevice(title)
        device.library.setStatus(book.id, ReadingStatus.Read)
        device.library.setRating(book.id, 4)

        assertTrue(device.orbit.sync().statesSent >= 1)

        assertEquals("read", card(title).getJSONObject("readStatus").getString("status"))
        assertEquals(4, card(title).getInt("rating"))
        assertFalse(fresh(book).stateUnsent)

        // Changed elsewhere, as from the reader's KOReader.
        val base = "/koreader/plugin/catalog/books/${book.serverBookId}"
        assertEquals(200, server.koreader("PUT", "$base/read-status", JSONObject().put("status", "want_to_read")).code)
        assertEquals(200, server.koreader("PUT", "$base/rating", JSONObject().put("rating", 2)).code)
        assertTrue(device.orbit.sync().statesTaken >= 1)
        assertEquals(ReadingStatus.ToRead, fresh(book).status)
        assertEquals(2, fresh(book).rating)
        assertFalse("what came from the server is not sent back", fresh(book).stateUnsent)

        // Stars taken off here are taken off there.
        device.library.setRating(book.id, null)
        device.orbit.sync()
        assertTrue(card(title).isNull("rating"))
        assertEquals("the status the server had is left as it was", "want_to_read", card(title).getJSONObject("readStatus").getString("status"))

        // A status the app has no word for stays on the server when the reader gives stars here.
        assertEquals(200, server.koreader("PUT", "$base/read-status", JSONObject().put("status", "on_hold")).code)
        device.orbit.sync()
        device.library.setRating(book.id, 5)
        device.orbit.sync()
        assertEquals("on_hold", card(title).getJSONObject("readStatus").getString("status"))
        assertEquals(5, card(title).getInt("rating"))
    }

    @Test
    fun pagesReadReachTheServersStatisticsOnce() = runBlocking<Unit> {
        val (book, _) = onDevice("Contract Plain")
        fun seconds() = server.jwt("GET", "/books/${book.serverBookId}/sessions").json().getJSONObject("stats").optInt("totalSeconds")
        val before = seconds()
        // Three pages read just now, one after another.
        val start = System.currentTimeMillis() / 1000 - 600 + (System.nanoTime() % 300)
        device.library.addPageRead(book.id, page = 2, totalPages = 20, startedAt = start, seconds = 40)
        device.library.addPageRead(book.id, page = 3, totalPages = 20, startedAt = start + 40, seconds = 35)
        device.library.addPageRead(book.id, page = 4, totalPages = 20, startedAt = start + 75, seconds = 50)

        assertEquals(3, device.orbit.sync().pagesSent)

        assertEquals("the time read is in the book's statistics", before + 125, seconds())
        assertTrue(device.library.pageReadsToSend(10).isEmpty())

        // The same pages sent again, as after an answer that was lost, are not counted twice.
        device.library.addPageRead(book.id, page = 2, totalPages = 20, startedAt = start, seconds = 40)
        assertEquals(1, device.orbit.sync().pagesSent)
        assertEquals(before + 125, seconds())
    }
}
