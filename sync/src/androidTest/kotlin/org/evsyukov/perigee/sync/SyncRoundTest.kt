package org.evsyukov.perigee.sync

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.ReadingStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A round of syncing against a stand-in server: requirements R7 and R9. */
@RunWith(AndroidJUnit4::class)
class SyncRoundTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The device's clock, in milliseconds; the server's times are the same clock in seconds. */
    private var clock = 1_700_000_000_000L
    private val library = Library.inMemory(context) { clock }
    private val accounts = Accounts.of(library) { clock }
    private val orbit = BookOrbit(accounts, library, "0.0.1", now = { clock })
    private val server = FakeOrbit()

    @Before
    fun signIn() = runBlocking<Unit> { orbit.signIn(server.url, "reader", "right-password") }

    @After
    fun stop() = server.close()

    private fun later(seconds: Int = 60) {
        clock += seconds * 1000L
    }

    /** A book the reader opened that the server has too, as book [serverId]. */
    private suspend fun shared(name: String, serverId: Long = 7): Book {
        val file = File(context.cacheDir, name).apply { writeText("the text of $name ${System.nanoTime()}") }
        val book = library.open(file, name, "")
        server.files[book.id] = serverId to serverId * 10
        library.onServer(book.id, serverId, serverId * 10)
        // Where the book opened; not a move.
        library.savePosition(book.id, "/body/p[1]", 0f)
        return book
    }

    private suspend fun fresh(book: Book): Book = checkNotNull(library.book(book.id))

    private fun elsewhere(book: Book, share: Double, xpointer: String?, secondsFromNow: Int, device: String = "web") {
        server.places[book.id] = JSONObject().put("document", book.id).put("percentage", share)
            .put("progress", xpointer ?: JSONObject.NULL).put("device", device).put("device_id", "bookorbit-web")
            .put("timestamp", clock / 1000 + secondsFromNow)
    }

    private fun puts() = server.asked("/koreader/syncs/progress").filter { it.method == "PUT" }

    private fun serverSays(vararg books: String) {
        server.books = JSONArray(books.joinToString(",", "[", "]"))
    }

    private fun state(id: Long, status: String?, rating: Int?) =
        """{"id":$id,"title":"B$id","authors":[],"files":[],"rating":${rating ?: "null"},
            "readStatus":${status?.let { """{"status":"$it","source":"manual"}""" } ?: "null"}}"""

    @Test
    fun placeTheReaderMovedToIsSentOnce() = runBlocking<Unit> {
        val book = shared("moved.fb2")
        later()
        library.savePosition(book.id, "/body/p[40]", 0.25f)
        val movedAt = fresh(book).movedAt

        assertEquals(1, orbit.sync().placesSent)

        val sent = JSONObject(puts().single().body)
        assertEquals(book.id, sent.getString("document"))
        assertEquals("/body/p[40]", sent.getString("progress"))
        assertEquals(0.25, sent.getDouble("percentage"), 0.0001)
        assertEquals(accounts.current()!!.deviceId, sent.getString("device_id"))
        assertEquals(BookOrbit.DEVICE_MODEL, sent.getString("device"))
        assertEquals(movedAt / 1000, sent.getLong("timestamp"))
        assertFalse(fresh(book).placeUnsent)

        // The server answers with the app's own place from now on; that is not news.
        later()
        val again = orbit.sync()
        assertEquals(0, again.placesSent + again.offers)
        assertEquals(1, puts().size)
        assertNull(library.pendingOffer(book.id))
    }

    @Test
    fun laterPlaceFromElsewhereIsOfferedAndTheOneHereIsNotSentOverIt() = runBlocking<Unit> {
        val book = shared("both.fb2")
        later()
        library.savePosition(book.id, "/body/p[10]", 0.1f)
        elsewhere(book, 0.6, "/body/DocFragment[3]/body/p[4]/text().0", secondsFromNow = 30)

        val report = orbit.sync()

        assertEquals(1, report.offers)
        assertEquals(0, report.placesSent)
        assertTrue("nothing was sent over the later place", puts().isEmpty())
        val offer = checkNotNull(library.pendingOffer(book.id))
        assertEquals(0.6f, offer.progress, 0.0001f)
        assertEquals("/body/DocFragment[3]/body/p[4]/text().0", offer.xpointer)
        assertEquals("web", offer.device)
        assertEquals("the reader stays where they are until they decide", "/body/p[10]", fresh(book).xpointer)

        // Not decided yet, and the reader reads on here: still nothing goes over the other place.
        later(120)
        library.savePosition(book.id, "/body/p[11]", 0.11f)
        orbit.sync()
        assertTrue(puts().isEmpty())
        assertEquals(0.6f, library.pendingOffer(book.id)?.progress)

        // The reader chose to stay: what they read here is what counts now.
        library.settleOffer(book.id)
        assertEquals(1, orbit.sync().placesSent)
        assertEquals("/body/p[11]", JSONObject(puts().single().body).getString("progress"))
        assertNull("the place decided about is not offered again", library.pendingOffer(book.id))
    }

    @Test
    fun placeTheReaderStaysAtIsSentAsLaterThanTheOneTheyTurnedDown() = runBlocking<Unit> {
        val book = shared("stayed.fb2")
        later()
        library.savePosition(book.id, "/body/p[10]", 0.1f)
        val elsewhereAt = clock / 1000 + 30
        elsewhere(book, 0.6, "/body/DocFragment[3]/body/p[4]/text().0", secondsFromNow = 30)
        assertEquals(1, orbit.sync().offers)

        // The reader stays without reading on: the place here was reached before the other one.
        library.settleOffer(book.id)
        assertEquals(1, orbit.sync().placesSent)

        val sent = JSONObject(puts().single().body)
        assertEquals("/body/p[10]", sent.getString("progress"))
        // A server that keeps the later of two places would drop it otherwise.
        assertTrue("sent as of ${sent.getLong("timestamp")}, the other is of $elsewhereAt", sent.getLong("timestamp") > elsewhereAt)
        assertFalse(fresh(book).placeUnsent)
    }

    @Test
    fun readerWhoGoesToThePlaceFromElsewhereSendsItBackAsTheirOwn() = runBlocking<Unit> {
        val book = shared("accepted.fb2")
        elsewhere(book, 0.6, "/body/p[60]", secondsFromNow = -10)
        assertEquals(1, orbit.syncPlace(book.id).offers)

        // As the screen does when the reader agrees.
        later()
        library.savePosition(book.id, "/body/p[60]", 0.6f)
        library.settleOffer(book.id)

        assertEquals(1, orbit.sync().placesSent)
        assertEquals("/body/p[60]", JSONObject(puts().single().body).getString("progress"))
    }

    @Test
    fun bookJustOpenedDoesNotOverruleWhatWasReadElsewhere() = runBlocking<Unit> {
        // Read on another device days ago; opened here for the first time.
        val book = shared("first-time.fb2")
        elsewhere(book, 0.42, "/body/p[77]", secondsFromNow = -5 * 24 * 3600, device = "KOReader")

        assertEquals(1, orbit.syncPlace(book.id).offers)

        assertTrue(puts().isEmpty())
        assertEquals("KOReader", library.pendingOffer(book.id)?.device)
    }

    @Test
    fun olderPlaceFromElsewhereGivesWayToTheOneHere() = runBlocking<Unit> {
        val book = shared("newer-here.fb2")
        later()
        library.savePosition(book.id, "/body/p[10]", 0.1f)
        elsewhere(book, 0.6, "/body/p[60]", secondsFromNow = -3600)

        val report = orbit.sync()

        assertEquals(0, report.offers)
        assertEquals(1, report.placesSent)
        assertNull(library.pendingOffer(book.id))
    }

    @Test
    fun samePlaceElsewhereIsNotOffered() = runBlocking<Unit> {
        val book = shared("same.fb2")
        later()
        library.savePosition(book.id, "/body/p[10]", 0.5f)
        library.placeSent(book.id, fresh(book).movedAt)
        // The server names a place its own way; where it is in the book tells that it is the same one.
        elsewhere(book, 0.5002, "/body/div/p[10]", secondsFromNow = 30)

        assertEquals(0, orbit.sync().offers)
        assertNull(library.pendingOffer(book.id))
    }

    @Test
    fun placeKnownOnlyAsAShareOfTheBookIsOfferedAsSuch() = runBlocking<Unit> {
        // As for a format the server cannot name places in for the device.
        val book = shared("share.fb2")
        elsewhere(book, 0.33, null, secondsFromNow = 10)

        orbit.syncPlace(book.id)

        val offer = checkNotNull(library.pendingOffer(book.id))
        assertNull(offer.xpointer)
        assertEquals(0.33f, offer.progress, 0.0001f)
    }

    @Test
    fun whatWasReadWithoutTheServerGoesWhenItIsBack() = runBlocking<Unit> {
        val book = shared("offline.fb2")
        later()
        library.savePosition(book.id, "/body/p[10]", 0.1f)
        library.addPageRead(book.id, page = 2, totalPages = 20, startedAt = clock / 1000 - 50, seconds = 40)
        server.down = true

        assertThrows(IOException::class.java) { runBlocking { orbit.sync() } }
        later()
        library.savePosition(book.id, "/body/p[20]", 0.2f)
        library.addPageRead(book.id, page = 3, totalPages = 20, startedAt = clock / 1000 - 50, seconds = 45)
        assertThrows(IOException::class.java) { runBlocking { orbit.sync() } }
        assertTrue(fresh(book).placeUnsent)

        server.down = false
        val report = orbit.sync()

        assertEquals(1, report.placesSent)
        assertEquals("only the last place matters", "/body/p[20]", JSONObject(server.places.getValue(book.id).toString()).getString("progress"))
        assertEquals(2, report.pagesSent)
        assertEquals(listOf(2, 3), server.pagesRead.map { it.getInt("page") })
        assertTrue(library.pageReadsToSend(10).isEmpty())
    }

    @Test
    fun whatTheServerTookButDidNotSaySoIsSentAgainWithoutBeingCountedTwice() = runBlocking<Unit> {
        val book = shared("lost-answer.fb2")
        later()
        library.savePosition(book.id, "/body/p[10]", 0.1f)
        library.addPageRead(book.id, page = 2, totalPages = 20, startedAt = clock / 1000 - 50, seconds = 40)
        library.setRating(book.id, 4)
        serverSays(state(7, "reading", null))
        server.losingAnswers = true

        assertThrows(IOException::class.java) { runBlocking { orbit.sync() } }
        assertTrue("not known to be taken, so still to be sent", fresh(book).placeUnsent)
        server.losingAnswers = false
        orbit.sync()

        assertFalse(fresh(book).placeUnsent)
        assertFalse(fresh(book).stateUnsent)
        assertEquals("/body/p[10]", server.places.getValue(book.id).getString("progress"))
        assertEquals(4, server.ratings[7])
        assertEquals("the page read is in the server's statistics once", 1, server.pagesRead.size)
        assertTrue(library.pageReadsToSend(10).isEmpty())
    }

    @Test
    fun bookTheServerHasNoMoreStopsBeingSynced() = runBlocking<Unit> {
        val book = shared("removed.fb2")
        later()
        library.savePosition(book.id, "/body/p[10]", 0.1f)
        server.files.clear()

        assertEquals(0, orbit.sync().placesSent)

        assertNull(fresh(book).serverFileId)
        assertEquals("the reader's place is theirs still", "/body/p[10]", fresh(book).xpointer)
        orbit.sync()
        assertEquals("it is not asked about again", 1, server.seen.count { "/koreader/syncs/progress/" in it.path })
    }

    @Test
    fun statusAndRatingChangedHereGoToTheServer() = runBlocking<Unit> {
        val book = shared("state-up.fb2")
        serverSays(state(7, null, null))
        later()
        library.setStatus(book.id, ReadingStatus.Read)
        library.setRating(book.id, 5)

        assertEquals(1, orbit.sync().statesSent)

        assertEquals("read", server.statuses[7])
        assertEquals(5, server.ratings[7])
        assertFalse(fresh(book).stateUnsent)

        // The server now has what the reader said; nothing more to send.
        serverSays(state(7, "read", 5))
        val before = server.seen.count { "/catalog/books/" in it.path }
        assertEquals(0, orbit.sync().statesSent)
        assertEquals(before, server.seen.count { "/catalog/books/" in it.path })

        later()
        library.setRating(book.id, null)
        orbit.sync()
        assertTrue("a rating taken off is taken off there too", 7L in server.ratings && server.ratings[7] == null)
    }

    @Test
    fun statusAndRatingChangedOnTheServerComeHere() = runBlocking<Unit> {
        val book = shared("state-down.fb2")
        serverSays(state(7, "reading", null))
        orbit.sync()

        serverSays(state(7, "read", 4), state(99, "want_to_read", 1))
        assertEquals(1, orbit.sync().statesTaken)

        assertEquals(ReadingStatus.Read, fresh(book).status)
        assertEquals(4, fresh(book).rating)
        assertFalse("what came from the server is not sent back", fresh(book).stateUnsent)
        assertTrue(server.statuses.isEmpty() || server.statuses[7] == "reading")
    }

    @Test
    fun reviewWrittenHereGoesToTheServer() = runBlocking<Unit> {
        val book = shared("review-up.fb2")
        serverSays(state(7, null, null))
        later()
        library.setReview(book.id, "  A slow start,\nand then I could not put it down.  ")
        assertTrue(fresh(book).syncUnsent)

        orbit.sync()

        assertEquals("A slow start,\nand then I could not put it down.", server.reviews[7])
        assertFalse(fresh(book).reviewUnsent)
        val sent = server.seen.count { it.path.endsWith("/personal-note") }
        orbit.sync()
        assertEquals("sent once", sent, server.seen.count { it.path.endsWith("/personal-note") })

        later()
        library.setReview(book.id, " ")
        orbit.sync()
        assertNull("a review taken off here is taken off there too", server.reviews[7])
        assertNull(fresh(book).review)
    }

    @Test
    fun reviewWrittenOnTheServerComesHereUnlessTheReadersOwnIsNotSentYet() = runBlocking<Unit> {
        val book = shared("review-down.fb2")
        serverSays(state(7, null, null))
        orbit.sync()

        server.reviews[7] = "Written in the browser."
        orbit.sync()
        assertEquals("Written in the browser.", fresh(book).review)
        assertFalse("what came from the server is not sent back", fresh(book).reviewUnsent)

        // Changed here while the server cannot be reached, and in the browser meanwhile: the reader's goes, the later one.
        later()
        library.setReview(book.id, "Written here.")
        server.reviews[7] = "Changed in the browser."
        server.down = true
        assertThrows(IOException::class.java) { runBlocking { orbit.sync() } }
        assertEquals("Written here.", fresh(book).review)
        server.down = false
        orbit.sync()
        assertEquals("Written here.", server.reviews[7])
        assertEquals("Written here.", fresh(book).review)

        server.reviews.remove(7)
        orbit.sync()
        assertNull("a review taken off on the server is taken off here", fresh(book).review)
    }

    @Test
    fun statusTheAppHasNoWordForIsLeftAloneBothWays() = runBlocking<Unit> {
        val book = shared("on-hold.fb2")
        serverSays(state(7, "reading", null))
        orbit.sync()
        server.statuses.clear()

        // Put on hold in the browser; here the reader gives it stars.
        serverSays(state(7, "on_hold", null))
        orbit.sync()
        assertEquals("the reader's own word for it stays", ReadingStatus.Reading, fresh(book).status)

        later()
        library.setRating(book.id, 3)
        orbit.sync()
        assertEquals(3, server.ratings[7])
        assertNull("the stars did not undo what was set in the browser", server.statuses[7])

        // The status itself changed here is the reader's word, and goes.
        later()
        library.setStatus(book.id, ReadingStatus.Read)
        orbit.sync()
        assertEquals("read", server.statuses[7])
    }

    @Test
    fun manyPagesReadGoUpInPartsTheServerTakes() = runBlocking<Unit> {
        val book = shared("long-read.fb2")
        val start = clock / 1000 - 100_000
        repeat(1100) { library.addPageRead(book.id, page = it + 1, totalPages = 2000, startedAt = start + it * 60L, seconds = 50) }

        assertEquals(1100, orbit.sync().pagesSent)

        val parts = server.asked("/koreader/plugin/page-stats").map { request ->
            val books = JSONObject(request.body).getJSONArray("books")
            (0 until books.length()).sumOf { books.getJSONObject(it).getJSONArray("events").length() }
        }
        assertEquals(listOf(500, 500, 100), parts)
        assertEquals(1100, server.pagesRead.size)
        val event = server.pagesRead.first()
        assertEquals(listOf(1, 2000, 50), listOf(event.getInt("page"), event.getInt("totalPages"), event.getInt("durationSeconds")))
        assertEquals(start, event.getLong("startTime"))
    }

    @Test
    fun pagesReadLongAgoInBooksTheServerNeverHadAreDropped() = runBlocking<Unit> {
        shared("anchor.fb2")
        val file = File(context.cacheDir, "local-only.fb2").apply { writeText("never on the server ${System.nanoTime()}") }
        val local = library.open(file, "local", "")
        library.addPageRead(local.id, 1, 10, startedAt = clock / 1000 - 100L * 24 * 3600, seconds = 30)
        library.addPageRead(local.id, 2, 10, startedAt = clock / 1000 - 3600, seconds = 30)

        assertEquals(0, orbit.sync().pagesSent)

        // The book reaches the server at last: what was read lately is told, what was read a season ago is gone.
        server.files[local.id] = 8L to 80L
        library.onServer(local.id, 8, 80)
        assertEquals(1, orbit.sync().pagesSent)
        assertEquals(2, server.pagesRead.single().getInt("page"))
    }

    @Test
    fun serverOfAnotherVersionIsSentNothing() = runBlocking<Unit> {
        val book = shared("unsupported.fb2")
        later()
        library.savePosition(book.id, "/body/p[10]", 0.1f)
        library.setRating(book.id, 5)
        library.addPageRead(book.id, 1, 10, clock / 1000 - 60, 30)
        server.version = "v4.0.0"
        orbit.checkServer()
        val before = server.seen.size

        assertThrows(IncompatibleServerException::class.java) { runBlocking { orbit.sync() } }
        assertThrows(IncompatibleServerException::class.java) { runBlocking { orbit.syncPlace(book.id) } }

        assertEquals("not a request went out", before, server.seen.size)
        assertTrue("it all waits for a server the app knows", fresh(book).placeUnsent && fresh(book).stateUnsent)
        assertEquals(1, library.pageReadsToSend(10).size)
    }

    @Test
    fun channelThatIsNotReadySaysSoAndKeepsWhatIsToBeSent() = runBlocking<Unit> {
        val book = shared("no-channel.fb2")
        later()
        library.savePosition(book.id, "/body/p[10]", 0.1f)
        server.deviceKey = FakeOrbit.md5("changed in the browser")

        val failure = assertThrows(DeviceChannelException::class.java) { runBlocking { orbit.sync() } }

        assertEquals(DeviceChannel.PasswordNeeded, failure.state)
        assertTrue(fresh(book).placeUnsent)
    }

    @Test
    fun bookTheServerHasNotIsLeftOutOfIt() = runBlocking<Unit> {
        val file = File(context.cacheDir, "mine-alone.fb2").apply { writeText("only here ${System.nanoTime()}") }
        val book = library.open(file, "mine", "")
        library.savePosition(book.id, "/a", 0f)
        later()
        library.savePosition(book.id, "/b", 0.5f)

        assertEquals(SyncReport(), orbit.sync())
        assertEquals(SyncReport(), orbit.syncPlace(book.id))
        assertTrue(server.seen.none { "/syncs/" in it.path || "/catalog/" in it.path })
    }
}
