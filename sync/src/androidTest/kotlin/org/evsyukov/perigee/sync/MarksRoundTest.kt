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
import org.evsyukov.perigee.data.Quote
import org.evsyukov.perigee.data.ReadingStatus
import org.evsyukov.perigee.data.Stamps
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

/** Quotes and bookmarks in a round of syncing against a stand-in server: requirement R8. */
@RunWith(AndroidJUnit4::class)
class MarksRoundTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var clock = 1_700_000_000_000L

    /** Every reading of the clock is a second on, so that things made one after another are stamped apart. */
    private val library = Library.inMemory(context) { clock.also { clock += 1000 } }
    private val accounts = Accounts.of(library) { clock }
    private val orbit = BookOrbit(accounts, library, "0.0.1", now = { clock })
    private val server = FakeOrbit()
    private lateinit var book: Book

    @Before
    fun signInWithABookTheServerHas() = runBlocking<Unit> {
        orbit.signIn(server.url, "reader", "right-password")
        val file = File(context.cacheDir, "marked.epub").apply { writeText("a book to mark ${System.nanoTime()}") }
        book = library.open(file, "Marked", "")
        server.files[book.id] = 7L to 70L
        library.onServer(book.id, 7, 70)
        // Not being read: marks alone decide whether the book is spoken of.
        library.setStatus(book.id, ReadingStatus.None)
    }

    @After
    fun stop() = server.close()

    /** The key of the devices' channel went with the account; the reader sets a password anew. */
    private suspend fun signInAgain() {
        assertEquals(DeviceChannel.PasswordNeeded, orbit.signIn(server.url, "reader", "right-password"))
        assertEquals(DeviceChannel.Ready, orbit.newDevicePassword("chosen anew"))
    }

    private suspend fun quotes(): List<Quote> = library.marksOf(book.id).second
    private suspend fun bookmarks() = library.marksOf(book.id).first
    private fun onServer() = server.quotes[book.id].orEmpty()
    private fun marksOnServer() = server.bookmarks[book.id].orEmpty()
    private fun exchanges(what: String) = server.asked("/koreader/plugin/$what/exchange").map { JSONObject(it.body).getJSONArray("books").getJSONObject(0) }

    @Test
    fun quoteMadeHereGoesToTheServerOnce() = runBlocking<Unit> {
        library.addQuote(book.id, "/body/p[3]/text().4", "/body/p[3]/text().20", "sixteen letters!", 0xFFE066, "One", note = "mine")

        assertEquals(1, orbit.sync().marksSent)

        val there = onServer().single()
        assertEquals("sixteen letters!", there.text)
        assertEquals("mine", there.note)
        assertEquals("the colour goes as it is", "#FFE066", there.color)
        val sent = exchanges("annotations").single()
        assertTrue(sent.getBoolean("keysComplete"))
        val change = sent.getJSONArray("changes").getJSONObject(0)
        val made = quotes().single()
        assertEquals(made.stamp, change.getString("datetime"))
        assertEquals("One", change.getString("chapter"))
        assertFalse("not changed since it was made", change.has("datetimeUpdated"))
        assertEquals(Stamps.key(made.stamp, made.start), sent.getJSONArray("keys").getJSONObject(0).getString("k"))
        assertFalse(made.unsent)

        // Nothing changed: the book is not spoken of again.
        assertEquals(0, orbit.sync().marksSent)
        assertEquals(1, exchanges("annotations").size)
        assertEquals(1, onServer().size)
    }

    @Test
    fun noteAndColourChangedHereChangeOnTheServer() = runBlocking<Unit> {
        val id = library.addQuote(book.id, "/a", "/b", "quoted", 0xFFE066, null)
        orbit.sync()

        library.updateQuote(id, "second thoughts", 0x8FC8FF)
        assertEquals(1, orbit.sync().marksSent)

        val there = onServer().single()
        assertEquals("still one quote", 1, onServer().size)
        assertEquals("second thoughts", there.note)
        assertEquals("#8FC8FF", there.color)
        val change = exchanges("annotations").last().getJSONArray("changes").getJSONObject(0)
        assertTrue("told as a change of the same quote", change.has("datetimeUpdated"))
        assertEquals(quotes().single().stamp, change.getString("datetime"))
    }

    @Test
    fun quoteRemovedHereIsRemovedOnTheServerAndDoesNotComeBack() = runBlocking<Unit> {
        val keep = library.addQuote(book.id, "/a", "/b", "kept", 1, null)
        val drop = library.addQuote(book.id, "/c", "/d", "dropped", 1, null)
        orbit.sync()
        assertEquals(2, onServer().size)

        library.removeQuote(drop)
        orbit.sync()

        assertEquals(listOf("dropped"), onServer().filter { it.deleted }.map { it.text })
        assertEquals(listOf("kept"), quotes().map { it.text })
        orbit.sync()
        orbit.syncMarks(book.id)
        assertEquals("it stays removed", listOf("kept"), quotes().map { it.text })
        assertTrue(keep > 0)
    }

    @Test
    fun quoteMadeInTheWebReaderComesHereOnceAndIsNotSentBack() = runBlocking<Unit> {
        server.webQuote(book.id, "/body/DocFragment[2]/body/p[4]/text().0", "/body/DocFragment[2]/body/p[4]/text().12", "from the web", "web note", "blue")

        assertEquals(1, orbit.syncMarks(book.id).marksTaken)

        val taken = quotes().single()
        assertEquals("from the web", taken.text)
        assertEquals("web note", taken.note)
        assertEquals("/body/DocFragment[2]/body/p[4]/text().0", taken.start)
        assertEquals("blue as the web shows it", 0x38BDF8, taken.color)
        assertFalse(taken.unsent)
        assertTrue("the server was told the device has it", onServer().single().known)

        orbit.sync()
        orbit.syncMarks(book.id)
        assertEquals("not taken twice", 1, quotes().size)
        assertEquals("and not made a second time on the server", 1, onServer().size)
        assertTrue(exchanges("annotations").all { it.getJSONArray("changes").length() == 0 })
        // Its key is in the list of what is here, or the server would take it for removed.
        val last = exchanges("annotations").last()
        assertEquals(Stamps.key(taken.stamp, taken.start), last.getJSONArray("keys").getJSONObject(0).getString("k"))
        assertFalse(onServer().single().deleted)
    }

    @Test
    fun quoteChangedInTheWebReaderChangesHere() = runBlocking<Unit> {
        val web = server.webQuote(book.id, "/a", "/b", "from the web", null, "yellow")
        orbit.syncMarks(book.id)

        web.note = "added in the browser"
        web.color = "green"
        web.version++
        assertEquals(1, orbit.syncMarks(book.id).marksTaken)

        val changed = quotes().single()
        assertEquals("added in the browser", changed.note)
        assertEquals(0x4ADE80, changed.color)
        assertFalse("what came from the server is not sent back", changed.unsent)
        assertEquals("the server knows the device has the change", web.version, web.onDevice)
        assertTrue(exchanges("annotations").all { it.getJSONArray("changes").length() == 0 })
    }

    @Test
    fun quoteRemovedInTheWebReaderIsRemovedHere() = runBlocking<Unit> {
        val mine = library.addQuote(book.id, "/a", "/b", "made here", 1, null)
        val web = server.webQuote(book.id, "/c", "/d", "from the web")
        orbit.sync()
        assertEquals(2, quotes().size)

        web.deleted = true
        onServer().first { it.text == "made here" }.deleted = true
        assertEquals(2, orbit.syncMarks(book.id).marksTaken)

        assertTrue(quotes().isEmpty())
        assertTrue("the server heard that both are gone here", onServer().all { it.removalKnown })
        orbit.sync()
        assertTrue("and they do not come back", quotes().isEmpty())
        assertTrue(mine > 0)
    }

    @Test
    fun answerLostOnTheWayMakesNoSecondQuote() = runBlocking<Unit> {
        library.addQuote(book.id, "/a", "/b", "sent twice", 1, null)
        server.webQuote(book.id, "/c", "/d", "from the web")
        server.losingAnswers = true

        assertThrows(IOException::class.java) { runBlocking { orbit.sync() } }
        assertEquals("the server took it", 2, onServer().size)
        assertTrue("and the app does not know", quotes().single().unsent)

        server.losingAnswers = false
        orbit.sync()

        assertEquals("one quote of each, not two", listOf("from the web", "sent twice"), onServer().map { it.text }.sorted())
        assertEquals(listOf("from the web", "sent twice"), quotes().map { it.text }.sorted())
        assertFalse(quotes().any { it.unsent })
        assertFalse(onServer().any { it.deleted })
    }

    @Test
    fun listTooLongToSendWholeIsNotSaidToBeWhole() = runBlocking<Unit> {
        // More quotes than the server takes keys of at once.
        repeat(5001) { library.takeQuote(book.id, "2026-01-01 00:00:00", "/body/p[$it]", "/body/p[$it]", "q$it", null, 1, null) }
        library.addQuote(book.id, "/new", "/new", "one more", 1, null)

        orbit.sync()

        val sent = exchanges("annotations").first()
        assertEquals(5000, sent.getJSONArray("keys").length())
        assertFalse("or the quotes left out would be removed on the server", sent.getBoolean("keysComplete"))
    }

    @Test
    fun manyQuotesGoAndComeInParts() = runBlocking<Unit> {
        repeat(120) { library.addQuote(book.id, "/mine[$it]", "/mine[$it]", "mine $it", 1, null) }
        repeat(230) { server.webQuote(book.id, "/web[$it]", "/web[$it]", "web $it") }

        val report = orbit.sync()

        assertEquals(120, report.marksSent)
        assertEquals(230, report.marksTaken)
        assertEquals(350, quotes().size)
        assertEquals(350, onServer().size)
        assertTrue(exchanges("annotations").all { it.getJSONArray("changes").length() <= 50 })
        assertTrue(onServer().all { it.known && !it.deleted })
    }

    @Test
    fun bookmarkMadeHereGoesToTheServerAndOneRemovedIsRemovedThere() = runBlocking<Unit> {
        library.addBookmark(book.id, "/body/p[5]", "The first words of the page", "One")
        library.addBookmark(book.id, "/body/p[9]", "Another page", "One")

        assertEquals(2, orbit.sync().marksSent)

        assertEquals(listOf("Another page", "The first words of the page"), marksOnServer().map { it.title }.sorted())
        assertTrue(bookmarks().all { it.sent })
        val sent = exchanges("bookmarks").single()
        assertTrue(sent.getBoolean("keysComplete"))
        assertEquals(2, sent.getJSONArray("keys").length())

        library.removeBookmark(bookmarks().first { it.xpointer == "/body/p[9]" }.id)
        orbit.sync()

        assertEquals(listOf("Another page"), marksOnServer().filter { it.deleted }.map { it.title })
        assertEquals(1, bookmarks().size)
        orbit.sync()
        orbit.syncMarks(book.id)
        assertEquals("it does not come back", 1, bookmarks().size)
        assertEquals(2, marksOnServer().size)
    }

    @Test
    fun bookmarkMadeInTheWebReaderComesHereAndOneRemovedThereGoes() = runBlocking<Unit> {
        val web = server.webBookmark(book.id, "/body/DocFragment[2]/body/p[7]", "Marked in the browser")

        assertEquals(1, orbit.syncMarks(book.id).marksTaken)

        val taken = bookmarks().single()
        assertEquals("Marked in the browser", taken.text)
        assertEquals("/body/DocFragment[2]/body/p[7]", taken.xpointer)
        assertEquals("the server was told what the device knows it by", Stamps.key(taken.stamp, taken.xpointer), web.key)
        orbit.sync()
        assertEquals("not taken twice, not sent back", 1, bookmarks().size)
        assertEquals(1, marksOnServer().size)
        assertFalse(web.deleted)

        web.deleted = true
        assertEquals(1, orbit.syncMarks(book.id).marksTaken)
        assertTrue(bookmarks().isEmpty())
        assertTrue(web.removalKnown)
    }

    @Test
    fun bookmarkTheServerCannotPlaceIsNotSentOverAndOver() = runBlocking<Unit> {
        // As in FB2, where the server has no places of its own.
        server.bookmarksRefused = true
        library.addBookmark(book.id, "/body/section/p[5]", "A page", null)

        orbit.sync()
        orbit.sync()
        orbit.syncMarks(book.id)

        assertEquals("told once", 1, exchanges("bookmarks").sumOf { it.getJSONArray("changes").length() })
        assertEquals("and kept here all the same", 1, bookmarks().size)
    }

    @Test
    fun marksMadeBeforeSigningInAreSentWhenTheBookIsFoundOnTheServer() = runBlocking<Unit> {
        orbit.signOut()
        library.addQuote(book.id, "/a", "/b", "made offline", 1, null)
        library.addBookmark(book.id, "/body/p[5]", "A page", null)

        signInAgain()
        // The same reader on what is another device to the server; the book is found again by its file.
        assertEquals(2, orbit.sync().marksSent)
        assertEquals(1, onServer().size)
        assertEquals(1, marksOnServer().size)
    }

    @Test
    fun readerWhoSignsInAgainDoesNotGetTheirOwnQuotesTwice() = runBlocking<Unit> {
        library.addQuote(book.id, "/a", "/b", "made here", 1, null)
        orbit.sync()
        orbit.signOut()
        // To the server the app is now a device it has not seen: it hands the quote over as new.
        onServer().single().known = false

        signInAgain()
        orbit.sync()
        orbit.syncMarks(book.id)

        assertEquals("known by its stamp and place, and not taken as another", 1, quotes().size)
        assertEquals(1, onServer().size)
        assertFalse(onServer().single().deleted)
    }

    @Test
    fun starGivenHereIsGivenOnTheServerToTheQuoteWithTheSameWords() = runBlocking<Unit> {
        val first = library.addQuote(book.id, "/a", "/b", "the same words", 1, null)
        val second = library.addQuote(book.id, "/c", "/d", "the same words", 1, null)
        library.addQuote(book.id, "/e", "/f", " other words ", 1, null)
        orbit.sync()

        library.starQuote(second, true)
        assertEquals(1, orbit.sync().marksSent)

        assertEquals("the second of two alike, as they were made", listOf(false, true, false), onServer().map { it.starred })
        assertFalse(quotes().single { it.id == second }.starUnsent)
        assertEquals("each quote knows its number there", onServer().map { it.serverId }, quotes().map { it.serverId })

        library.starQuote(second, false)
        library.starQuote(first, true)
        orbit.sync()
        assertEquals(listOf(true, false, false), onServer().map { it.starred })
        assertEquals("one PATCH a change", 3, server.seen.count { it.method == "PATCH" })
    }

    @Test
    fun starGivenOnTheServerComesHereUnlessOneGivenHereWaits() = runBlocking<Unit> {
        val web = server.webQuote(book.id, "/a", "/b", "from the web")
        orbit.syncMarks(book.id)
        val taken = quotes().single()
        assertEquals("tied by the number it was handed over with", web.serverId, taken.serverId)

        web.starred = true
        assertEquals(1, orbit.syncMarks(book.id).marksTaken)
        assertTrue(quotes().single().starred)
        assertFalse("taken, not to be sent back", quotes().single().starUnsent)

        // Taken off here meanwhile: the reader's own word goes up, not the server's down.
        library.starQuote(taken.id, false)
        orbit.sync()
        assertFalse(web.starred)
        assertFalse(quotes().single().starred)
    }

    @Test
    fun starOfAQuoteTheServerHasNotYetGoesWithItInOneRound() = runBlocking<Unit> {
        val id = library.addQuote(book.id, "/a", "/b", "new and starred", 1, null)
        library.starQuote(id, true)

        orbit.sync()

        assertTrue(onServer().single().starred)
        assertFalse(quotes().single().starUnsent)
    }

    @Test
    fun starsGoToTheNextServerAnew() = runBlocking<Unit> {
        val id = library.addQuote(book.id, "/a", "/b", "starred once", 1, null)
        library.starQuote(id, true)
        orbit.sync()
        orbit.signOut()
        server.quotes.clear()

        signInAgain()
        orbit.sync()

        assertTrue("sent with the quote to what is a new device to the server", onServer().single().starred)
    }

    @Test
    fun bookmarkTitleGivenOnTheServerIsShownHere() = runBlocking<Unit> {
        library.addBookmark(book.id, "/body/p[5]", "  The first words of the page ", "One")
        library.addBookmark(book.id, "/body/p[9]", "", "Chapter Two")
        val web = server.webBookmark(book.id, "/body/p[12]", "Marked in the browser")
        orbit.sync()
        assertEquals("the titles the server made are no titles of the reader's", 0, orbit.syncMarks(book.id).marksTaken)
        assertEquals(listOf("  The first words of the page ", "", "Marked in the browser"), bookmarks().map { it.text })
        assertTrue("each knows its number there", bookmarks().all { it.serverId != null })

        marksOnServer().first { it.pos == "/body/p[5]" }.title = "Where she leaves"
        marksOnServer().first { it.pos == "/body/p[9]" }.title = "Chapter Two, the end"
        web.title = "Renamed in the browser"
        assertEquals(3, orbit.syncMarks(book.id).marksTaken)

        assertEquals(listOf("Where she leaves", "Chapter Two, the end", "Renamed in the browser"), bookmarks().map { it.text })
        assertEquals("taken once", 0, orbit.syncMarks(book.id).marksTaken)
        assertTrue("not sent back", exchanges("bookmarks").last().getJSONArray("changes").length() == 0)
    }

    @Test
    fun bookTheServerHasNoMoreKeepsItsMarksHere() = runBlocking<Unit> {
        library.addQuote(book.id, "/a", "/b", "mine", 1, null)
        server.files.clear()

        assertEquals(0, orbit.sync().marksSent)

        assertNull(library.book(book.id)?.serverFileId)
        assertEquals(1, quotes().size)
        assertTrue("still to be sent, should the book come to a server again", quotes().single().unsent)
    }

    @Test
    fun coloursAreReadAsTheServerNamesThem() {
        assertEquals(0xF87171, BookOrbit.colorOf("red"))
        assertEquals(0x38BDF8, BookOrbit.colorOf(" Blue "))
        assertEquals(0x12AB9F, BookOrbit.colorOf("#12ab9f"))
        assertEquals("yellow when it says nothing known", 0xFACC15, BookOrbit.colorOf("chartreuse"))
        assertEquals(0xFACC15, BookOrbit.colorOf(""))
    }
}
