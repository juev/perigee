package org.evsyukov.perigee.contract

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.Bookmark
import org.evsyukov.perigee.data.Quote
import org.evsyukov.perigee.engine.EngineDocument
import org.json.JSONArray
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
 * Quotes and bookmarks exchanged with a real bookOrbit server: made, changed
 * and removed on either side. Requirement R8.
 *
 * The server keeps what earlier runs left in its books, and hands all of it to
 * a device it has not seen; every mark here is told apart by words of its own.
 */
@RunWith(AndroidJUnit4::class)
class MarksContractTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "marks-contract")
    private val title = "Contract Spine NonXhtml"
    private lateinit var server: BookOrbitServer
    private lateinit var device: ContractDevice
    private lateinit var book: Book
    private lateinit var file: File

    @Before
    fun bookOnTheDevice() = runBlocking<Unit> {
        server = ContractServer.connect()
        device = ContractDevice.signedIn(context)
        val remote = device.orbit.books(title, 0).books.single { it.title == title }
        file = device.orbit.download(remote.files.first(), title, File(dir, "book").apply { deleteRecursively() })
        device.library.open(file, title, "")
        device.orbit.matchLibrary(all = true)
        book = checkNotNull(device.library.book(org.evsyukov.perigee.data.FileHash.partialMd5(file)))
        assertNotNull("the server has this file", book.serverFileId)
    }

    private fun words(what: String) = "perigee $what ${System.nanoTime()}"

    private suspend fun quote(note: String): Quote? = device.library.marksOf(book.id).second.firstOrNull { it.note == note }
    private suspend fun bookmark(text: String): Bookmark? = device.library.marksOf(book.id).first.firstOrNull { it.text == text }

    private fun list(path: String): List<JSONObject> {
        val array: JSONArray = server.jwt("GET", "/books/${book.serverBookId}/$path").array()
        return (0 until array.length()).map(array::getJSONObject)
    }

    private fun webQuotes(note: String) = list("annotations").filter { !it.isNull("note") && it.getString("note") == note }
    private fun webBookmarks(title: String) = list("bookmarks").filter { it.getString("title") == title }

    /** A range of 25 characters at the top of a page in the given part of the book, with its text. */
    private fun range(doc: EngineDocument, from: Double): Triple<String, String, String> {
        val textPointer = Regex("""^(.*/text\(\)(?:\[\d+])?)\.(\d+)$""")
        for (page in (doc.pageCount * from).toInt() until doc.pageCount) {
            doc.goToPage(page)
            val (node, offset) = textPointer.matchEntire(doc.xpointer())?.destructured ?: continue
            val start = "$node.$offset"
            val end = "$node.${offset.toInt() + 25}"
            val text = doc.textBetween(start, end) ?: continue
            if (text.length == 25) return Triple(start, end, text)
        }
        error("no page starts inside a text node long enough for a quote")
    }

    @Test
    fun quoteMadeHereIsSeenChangedAndRemovedFromEitherSide() = runBlocking<Unit> {
        EngineDocument.open(context, file, 720, 1280).use { doc ->
            val (start, end, text) = range(doc, 0.3)
            val note = words("quote")
            val id = device.library.addQuote(book.id, start, end, text, 0xFFE066, "A chapter", note)

            device.orbit.sync()

            // The web reader has it at a place of its own kind, exactly.
            val web = webQuotes(note).single()
            assertFalse("the server made a CFI of $start", web.isNull("cfi"))
            assertEquals("exact", web.getString("positionStatus"))
            assertEquals(text.trim(), web.getString("text").trim())
            assertEquals("#FFE066", web.getString("color").uppercase())
            assertFalse(checkNotNull(quote(note)).unsent)

            // Changed here: the same quote changes there.
            val edited = "$note, thought again"
            device.library.updateQuote(id, edited, 0x8FC8FF)
            device.orbit.sync()
            assertTrue("no second quote was made", webQuotes(note).isEmpty())
            assertEquals("#8FC8FF", webQuotes(edited).single().getString("color").uppercase())
            assertEquals(web.getInt("id"), webQuotes(edited).single().getInt("id"))

            // The same change sent once more, as after an answer lost on the way, makes nothing new.
            val again = JSONObject().put("datetime", checkNotNull(quote(edited)).stamp).put("drawer", "lighten").put("color", "#8FC8FF")
                .put("text", text).put("note", edited).put("posFormat", "xpointer").put("pos0", start).put("pos1", end)
            val body = JSONObject().put("deviceId", device.accounts.current()!!.deviceId).put("deviceModel", "Perigee").put("pluginVersion", "0.0.1")
                .put("books", JSONArray().put(JSONObject().put("hash", book.id).put("keys", JSONArray()).put("keysComplete", false).put("changes", JSONArray().put(again))))
            val repeated = server.koreader("POST", "/koreader/plugin/annotations/exchange", body)
            assertEquals(0, repeated.json().getJSONArray("results").getJSONObject(0).getJSONObject("applied").getInt("created"))
            assertEquals(1, webQuotes(edited).size)

            // Changed in the web reader: it changes here.
            val fromWeb = "$note, as the browser has it"
            val patched = server.jwt("PATCH", "/books/${book.serverBookId}/annotations/${web.getInt("id")}", JSONObject().put("note", fromWeb))
            assertEquals("web edit: $patched", 200, patched.code)
            device.orbit.syncMarks(book.id)
            val changed = checkNotNull(quote(fromWeb)) { "the note changed in the browser reached the device" }
            assertEquals(id, changed.id)
            assertFalse("what came from the server is not sent back", changed.unsent)

            // Removed in the web reader: it goes from here, and stays gone.
            assertTrue(server.jwt("DELETE", "/books/${book.serverBookId}/annotations/${web.getInt("id")}").code in 200..204)
            device.orbit.syncMarks(book.id)
            assertNull(quote(fromWeb))
            device.orbit.sync()
            assertNull(quote(fromWeb))
            assertTrue(webQuotes(fromWeb).isEmpty())
        }
    }

    @Test
    fun quoteMadeInTheWebReaderComesHereAndOneRemovedHereGoesThere() = runBlocking<Unit> {
        EngineDocument.open(context, file, 720, 1280).use { doc ->
            // The web reader's kind of place for a range is learnt from the server, by sending the range once.
            val (start, end, text) = range(doc, 0.6)
            val probe = words("probe")
            val probeId = device.library.addQuote(book.id, start, end, text, 0xFFE066, null, probe)
            device.orbit.sync()
            val cfi = webQuotes(probe).single().getString("cfi")
            device.library.removeQuote(probeId)
            device.orbit.sync()
            assertTrue("removed here, it is gone from the web reader", webQuotes(probe).isEmpty())

            val note = words("web quote")
            val made = server.jwt("POST", "/books/${book.serverBookId}/annotations", JSONObject().put("text", text).put("cfi", cfi).put("color", "blue").put("note", note))
            assertEquals("web quote: $made", 201, made.code)

            device.orbit.syncMarks(book.id)

            val taken = checkNotNull(quote(note)) { "the quote made in the browser reached the device" }
            assertEquals("the engine finds the same words by the place it was given", text.trim(), doc.textBetween(taken.start, taken.end)?.trim())
            assertFalse(taken.unsent)

            // Nothing is made twice by asking again.
            device.orbit.sync()
            device.orbit.syncMarks(book.id)
            assertEquals(1, device.library.marksOf(book.id).second.count { it.note == note })
            assertEquals(1, webQuotes(note).size)

            // Removed here: it goes from the web reader, and does not come back.
            device.library.removeQuote(taken.id)
            device.orbit.sync()
            assertTrue(webQuotes(note).isEmpty())
            device.orbit.syncMarks(book.id)
            assertNull(quote(note))
        }
    }

    @Test
    fun bookmarksGoBothWaysAndRemovalsToo() = runBlocking<Unit> {
        EngineDocument.open(context, file, 720, 1280).use { doc ->
            assertTrue(doc.goToPage(doc.pageCount / 3))
            val here = doc.xpointer()
            val mine = words("bookmark")
            device.library.addBookmark(book.id, here, mine, null)

            device.orbit.sync()

            val web = webBookmarks(mine).single()
            assertFalse("the server made a CFI of $here", web.isNull("cfi"))
            assertTrue(checkNotNull(bookmark(mine)).sent)
            device.orbit.sync()
            assertEquals("asking again makes no second one", 1, webBookmarks(mine).size)

            // Removed here: gone there.
            device.library.removeBookmark(checkNotNull(bookmark(mine)).id)
            device.orbit.sync()
            assertTrue(webBookmarks(mine).isEmpty())
            device.orbit.syncMarks(book.id)
            assertNull("and it does not come back", bookmark(mine))

            // Made in the web reader, at the place the first one was: it comes here, on that page.
            val theirs = words("web bookmark")
            val made = server.jwt("POST", "/books/${book.serverBookId}/bookmarks", JSONObject().put("cfi", web.getString("cfi")).put("title", theirs))
            assertTrue("web bookmark: $made", made.code in 200..201)
            device.orbit.syncMarks(book.id)
            val taken = checkNotNull(bookmark(theirs)) { "the bookmark made in the browser reached the device" }
            assertEquals(doc.pageCount / 3, doc.pageOf(taken.xpointer))

            // Removed in the web reader: gone here.
            assertTrue(server.jwt("DELETE", "/books/${book.serverBookId}/bookmarks/${made.json().getInt("id")}").code in 200..204)
            device.orbit.syncMarks(book.id)
            assertNull(bookmark(theirs))
            device.orbit.sync()
            assertNull(bookmark(theirs))
        }
    }
}
