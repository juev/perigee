package org.evsyukov.perigee.contract

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.evsyukov.perigee.engine.EngineDocument
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Positions produced by the engine must survive the trip through bookOrbit:
 * xpointer → server CFI → xpointer for progress, and the same for highlights.
 * The server is the one from contract/docker-compose.yml, seeded by contract/up.py.
 */
@RunWith(Parameterized::class)
class PositionContractTest(private val title: String) {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: BookOrbitServer
    private lateinit var book: BookOrbitServer.Book
    private lateinit var file: File
    private lateinit var hash: String
    private val deviceId = "perigee-${System.currentTimeMillis()}"

    @Before
    fun connect() {
        server = ContractServer.connect()
        book = server.bookByTitle(title)
        file = File(context.cacheDir, "contract-${book.fileId}.epub")
        server.download(book.fileId, file)
        hash = BookOrbitServer.partialMd5(file)
    }

    @Test
    fun progressSurvivesTheServerRoundTrip() = open().use { doc ->
        for (fraction in listOf(0.15, 0.5, 0.85)) {
            val page = (doc.pageCount * fraction).toInt()
            assertTrue(doc.goToPage(page))
            val sent = doc.xpointer()
            val percentage = doc.progress.toDouble()

            // The device reports its position the way KOReader does.
            val push = server.koreader(
                "PUT", "/koreader/syncs/progress",
                JSONObject()
                    .put("document", hash)
                    .put("percentage", percentage)
                    .put("progress", sent)
                    .put("device", DEVICE_MODEL)
                    .put("device_id", deviceId)
                    .put("timestamp", System.currentTimeMillis() / 1000),
            )
            assertEquals("push: ${push.body}", 200, push.code)

            // The web reader sees it as a CFI…
            val stored = server.jwt("GET", "/books/files/${book.fileId}/progress").json()
            val cfi = stored.optString("cfi").takeUnless { stored.isNull("cfi") }
            log("progress page=$page sent=$sent cfi=$cfi")
            assertNotNull("server converted $sent to a CFI", cfi)
            assertEquals(percentage * 100, stored.getDouble("percentage"), 0.01)

            // …and when the web reader saves that CFI back, the device gets an xpointer
            // converted from it, because a web save drops the stored xpointer.
            val save = server.jwt(
                "POST", "/books/files/${book.fileId}/progress",
                JSONObject().put("percentage", percentage * 100).put("cfi", cfi),
            )
            assertTrue("web save: $save", save.code in 200..204)
            val pulled = server.koreader("GET", "/koreader/syncs/progress/$hash").json()
            val received = pulled.optString("progress").takeUnless { pulled.isNull("progress") }
            log("progress page=$page received=$received identical=${received == sent} device=${pulled.opt("device")}")
            assertNotNull("server converted $cfi back to an xpointer", received)

            doc.goToPage(0)
            assertTrue("engine resolves $received", doc.goToXPointer(received!!))
            assertEquals("the converted position lands on the page it was taken from", page, doc.currentPage)

            // The server orders pushes by whole seconds against its own clock;
            // a push in the same second as the web save above would be dropped.
            Thread.sleep(1100)
        }
    }

    @Test
    fun highlightSurvivesTheServerRoundTrip() = open().use { doc ->
        val (pos0, pos1, text) = pickRange(doc)
        val datetime = deviceTime()
        val key = BookOrbitServer.md5("$datetime|$pos0".toByteArray())
        val keys = JSONArray().put(JSONObject().put("k", key).put("dt", datetime))

        // A highlight made on the device goes up as a pair of xpointers.
        val change = JSONObject()
            .put("datetime", datetime)
            .put("drawer", "lighten")
            .put("color", "yellow")
            .put("text", text)
            .put("posFormat", "xpointer")
            .put("pos0", pos0)
            .put("pos1", pos1)
        val up = exchange(deviceId, keys, JSONArray().put(change))
        log("highlight up: ${up.getJSONObject("applied")}")
        assertEquals(1, up.getJSONObject("applied").getInt("created"))

        // The web reader needs it as a CFI range with an exact position.
        val mine = annotations().first { it.getString("text") == text && it.getString("origin") != "web" }
        log("highlight pos0=$pos0 pos1=$pos1 cfi=${mine.opt("cfi")} status=${mine.opt("positionStatus")}")
        assertFalse("server produced a CFI for the highlight", mine.isNull("cfi"))
        assertEquals("exact", mine.getString("positionStatus"))

        // A highlight made in the web reader at that CFI comes down as xpointers.
        val web = server.jwt(
            "POST", "/books/${book.bookId}/annotations",
            JSONObject().put("text", text).put("cfi", mine.getString("cfi")).put("color", "blue"),
        )
        assertEquals("web highlight: ${web.body}", 201, web.code)
        val webId = web.json().getInt("id")

        // The server converts a CFI on one exchange and delivers the result on the next,
        // without announcing that more is coming.
        val offered = { exchange(deviceId, keys, JSONArray()).getJSONObject("toApply").getJSONArray("add") }
            .let { round -> round().findById(webId) ?: round().findById(webId) }
        log("highlight down: $offered")
        assertNotNull("the web highlight reaches the device within two exchanges", offered)
        val gotPos0 = offered!!.getString("pos0")
        val gotPos1 = offered.getString("pos1")
        log("highlight identical=${gotPos0 == pos0 && gotPos1 == pos1}")
        assertEquals("the converted range selects the same text in the engine", text, doc.textBetween(gotPos0, gotPos1))
    }

    private fun open() = EngineDocument.open(context, file, 720, 1280)

    /** A 25-character range starting at the top of some page in the second half of the book. */
    private fun pickRange(doc: EngineDocument): Triple<String, String, String> {
        val textPointer = Regex("""^(.*/text\(\)(?:\[\d+])?)\.(\d+)$""")
        for (page in doc.pageCount / 2 until doc.pageCount) {
            doc.goToPage(page)
            val match = textPointer.matchEntire(doc.xpointer()) ?: continue
            val (node, offset) = match.destructured
            val pos0 = "$node.$offset"
            val pos1 = "$node.${offset.toInt() + 25}"
            val text = doc.textBetween(pos0, pos1) ?: continue
            if (text.length == 25) return Triple(pos0, pos1, text)
        }
        error("no page starts inside a text node long enough for a highlight")
    }

    private fun exchange(deviceId: String, keys: JSONArray, changes: JSONArray): JSONObject {
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("deviceModel", DEVICE_MODEL)
            .put("pluginVersion", "0.1.0")
            .put("books", JSONArray().put(
                JSONObject().put("hash", hash).put("keys", keys).put("keysComplete", true).put("changes", changes),
            ))
        val response = server.koreader("POST", "/koreader/plugin/annotations/exchange", body)
        check(response.code in 200..201) { "exchange failed: $response" }
        val json = response.json()
        check(json.getJSONArray("unmatched").length() == 0) { "server did not match the file by hash: $json" }
        return json.getJSONArray("results").getJSONObject(0)
    }

    private fun JSONArray.findById(serverId: Int): JSONObject? =
        (0 until length()).map(::getJSONObject).firstOrNull { it.getInt("serverId") == serverId }

    private fun annotations(): List<JSONObject> {
        val array = server.jwt("GET", "/books/${book.bookId}/annotations").array()
        return (0 until array.length()).map(array::getJSONObject)
    }

    private fun deviceTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun log(message: String) = Log.i(TAG, "[$title] $message")

    companion object {
        const val TAG = "PerigeeContract"
        const val DEVICE_MODEL = "Perigee"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun books() = listOf("Contract Plain", "Contract Spine NonXhtml", "Contract Media Css")
    }
}
