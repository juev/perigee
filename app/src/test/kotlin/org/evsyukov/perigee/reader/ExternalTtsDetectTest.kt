package org.evsyukov.perigee.reader

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The probes that find an OpenAI-format speech server by nothing but its address. */
@RunWith(RobolectricTestRunner::class)
class ExternalTtsDetectTest {
    private lateinit var server: HttpServer
    private val port get() = server.address.port
    private val base get() = "http://127.0.0.1:$port"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun client(url: String = base, key: String = "", cached: Detected? = null) =
        ExternalTtsClient(url, key, cached, store = {})

    private fun respond(exchange: HttpExchange, code: Int, type: String, body: ByteArray = ByteArray(0)) {
        exchange.responseHeaders.set("Content-Type", type)
        exchange.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        exchange.requestBody.close()
        exchange.responseBody.use { it.write(body) }
    }

    private fun speech(bytes: ByteArray, type: String = "audio/mpeg") {
        server.createContext("/v1/audio/speech") { respond(it, 200, type, bytes) }
    }

    private fun models(vararg ids: String) {
        val list = ids.joinToString(",") { """{"id":"$it"}""" }
        server.createContext("/v1/models") { respond(it, 200, "application/json", """{"data":[$list]}""".toByteArray()) }
    }

    @Test
    fun aRawAudioAnswerIsFoundOnTheOpenAIPathAndSpoken() = runBlocking<Unit> {
        models("whisper-1", "tts-1")
        speech(MP3)
        val stored = mutableListOf<String>()
        val session = ExternalTtsClient(base, "", null, store = { stored += it })

        val found = session.detect()
        assertEquals(Detected(base, "/v1/audio/speech", "tts-1", "alloy", "raw", ""), found)
        assertEquals("what was found is kept", 1, stored.size)

        val audio = session.synthesize("Привет", 1f)
        assertArrayEquals(MP3, audio)
        assertEquals("the extension of the audio as the server named it", ".mpeg", session.audioExt)
    }

    @Test
    fun audioCarriedAsBase64InsideJsonIsFoundAndDecoded() = runBlocking<Unit> {
        speech("""{"b64_json":"${B64(MP3)}"}""".toByteArray(), "application/json")
        val found = client().detect()
        assertEquals(Detected(base, "/v1/audio/speech", "tts-1", "alloy", "json", "b64_json"), found)
        assertArrayEquals(MP3, client(cached = found).synthesize("Hello", 1f))
    }

    @Test
    fun aDataMemberIsTakenWhenThereIsNoAudioMember() = runBlocking<Unit> {
        speech("""{"data":"${B64(MP3)}"}""".toByteArray(), "application/json")
        assertEquals("json", "data", client().detect()?.field)
    }

    @Test
    fun aWrongKeyIsReportedAsUnauthorized() = runBlocking<Unit> {
        server.createContext("/v1/audio/speech") { respond(it, 401, "application/json") }
        val session = client(key = "sk-wrong")
        assertNull(session.detect())
        assertEquals(SpeechNote.Unauthorized, session.failure)
    }

    @Test
    fun noEndpointAtEitherPathIsNotFound() = runBlocking<Unit> {
        val session = client()
        assertNull(session.detect())
        assertEquals(SpeechNote.NotFound, session.failure)
    }

    @Test
    fun anUnreachableAddressIsReportedAsUnreachable() = runBlocking<Unit> {
        // Port 1 keeps no server; the connection is refused at once.
        val session = client("http://127.0.0.1:1")
        assertNull(session.detect())
        assertEquals(SpeechNote.Unreachable, session.failure)
    }

    @Test
    fun aVoiceFromTheServerListIsTriedBeforeTheDefault() = runBlocking<Unit> {
        models("voice01")
        server.createContext("/v1/audio/voices") {
            respond(it, 200, "application/json", """{"voices":["voice01","voice2"]}""".toByteArray())
        }
        // A server that 404s every voice it does not know, as edge-tts style servers do.
        server.createContext("/v1/audio/speech") { exchange ->
            val asked = org.json.JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8)).optString("voice")
            if (asked == "voice01") respond(exchange, 200, "audio/mpeg", MP3) else respond(exchange, 404, "text/plain")
        }
        val found = client().detect()
        assertEquals("/v1/audio/speech", found?.path)
        assertEquals("the voice comes from the server's list, not the default", "voice01", found?.voice)
        assertEquals("raw", found?.kind)
    }

    @Test
    fun theReaderPickedVoiceIsSpokenWhileTheServerStillListsIt() = runBlocking<Unit> {
        models("voice01")
        server.createContext("/v1/audio/voices") {
            respond(it, 200, "application/json", """{"voices":["voice01","voice2"]}""".toByteArray())
        }
        val askedVoices = mutableListOf<String>()
        server.createContext("/v1/audio/speech") { exchange ->
            askedVoices += org.json.JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8)).optString("voice")
            respond(exchange, 200, "audio/mpeg", MP3)
        }
        val session = ExternalTtsClient(base, "", null, chosenVoice = "voice2", store = {})
        assertNotNull(session.detect())
        assertArrayEquals(MP3, session.synthesize("Hello", 1f))
        // The probe speaks with the server's first voice; synthesis speaks with the reader's pick.
        assertEquals(listOf("voice01", "voice2"), askedVoices)
    }

    @Test
    fun aPickedVoiceTheServerNoLongerListsFallsBackToItsFirst() = runBlocking<Unit> {
        models("voice01")
        server.createContext("/v1/audio/voices") {
            respond(it, 200, "application/json", """{"voices":["voice01"]}""".toByteArray())
        }
        val askedVoices = mutableListOf<String>()
        server.createContext("/v1/audio/speech") { exchange ->
            askedVoices += org.json.JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8)).optString("voice")
            respond(exchange, 200, "audio/mpeg", MP3)
        }
        val session = ExternalTtsClient(base, "", null, chosenVoice = "voice2", store = {})
        assertEquals("voice01", session.detect()?.voice)
        assertEquals(listOf("voice01"), askedVoices)
    }

    @Test
    fun aCacheKeptWithoutTheVoiceListLearnsTheVoicesOnTheNextSession() = runBlocking<Unit> {
        models("voice01")
        server.createContext("/v1/audio/voices") {
            respond(it, 200, "application/json", """{"voices":["voice01","voice2"]}""".toByteArray())
        }
        speech(MP3)
        // What an older build stored: a detection with no voice list in it.
        val legacy = """{"base":"$base","path":"/v1/audio/speech","model":"voice01","voice":"voice01","kind":"raw","field":""}"""
        val found = client(cached = Detected.fromJson(legacy)).detect()
        assertEquals(listOf("voice01", "voice2"), found?.voices)
        assertEquals("voice01", found?.voice)
    }

    @Test
    fun theCacheSparesTheProbesUntilTheAddressChanges() = runBlocking<Unit> {
        val posts = AtomicInteger()
        models("tts-1")
        server.createContext("/v1/audio/speech") {
            posts.incrementAndGet()
            respond(it, 200, "audio/mpeg", MP3)
        }
        val stored = mutableListOf<String>()
        val first = ExternalTtsClient(base, "", null, store = { stored += it })
        assertEquals("/v1/audio/speech", first.detect()?.path)
        assertEquals("the probe itself synthesizes once", 1, posts.get())

        val again = ExternalTtsClient(base, "", cached = Detected.fromJson(stored.single()), store = {})
        assertEquals("the cache answers for the same address", "/v1/audio/speech", again.detect()?.path)
        assertEquals("no probe happened", 1, posts.get())

        // A different host of the same server is not the cached address: the probes run again.
        val moved = ExternalTtsClient("http://localhost:$port", "", cached = Detected.fromJson(stored.single()), store = {})
        assertEquals("/v1/audio/speech", moved.detect()?.path)
        assertEquals(2, posts.get())
    }

    @Test
    fun aMovedEndpointIsFoundAgainAfterIts404() = runBlocking<Unit> {
        speech(MP3)
        val stored = mutableListOf<String>()
        val session = ExternalTtsClient(base, "", null, store = { stored += it })
        assertArrayEquals(MP3, session.synthesize("Hello", 1f))
        assertEquals("/v1/audio/speech", Detected.fromJson(stored.last())?.path)

        // The server moved to the shorter path and now answers 404 where it used to speak.
        server.removeContext("/v1/audio/speech")
        server.createContext("/audio/speech") { respond(it, 200, "audio/mpeg", MP3) }
        server.createContext("/v1/audio/speech") { respond(it, 404, "text/plain") }
        val audio = session.synthesize("Hello again", 1f)
        assertArrayEquals(MP3, audio)
        assertEquals("the cache points at the new path", "/audio/speech", Detected.fromJson(stored.last())?.path)
    }

    @Test
    fun theAddressTypedIsNormalizedToABareServer() {
        assertEquals("https://tts.local", ExternalTtsClient.normalizeBaseUrl(" tts.local "))
        assertEquals("https://tts.local:8000", ExternalTtsClient.normalizeBaseUrl("https://TTS.local:8000/"))
        assertEquals("http://tts.local", ExternalTtsClient.normalizeBaseUrl("http://tts.local/"))
        assertNull(ExternalTtsClient.normalizeBaseUrl(""))
        assertNull(ExternalTtsClient.normalizeBaseUrl("https://"))
        assertNull(ExternalTtsClient.normalizeBaseUrl("ftp://tts.local"))
    }

    private fun B64(bytes: ByteArray) = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private companion object {
        // Enough of a file for the probes; nothing here is played back in these tests.
        val MP3 = ByteArray(64) { (it * 7).toByte() }
    }
}
