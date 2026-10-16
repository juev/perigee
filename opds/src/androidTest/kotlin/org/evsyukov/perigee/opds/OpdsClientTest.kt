package org.evsyukov.perigee.opds

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The client against small servers the test runs itself. */
@RunWith(AndroidJUnit4::class)
class OpdsClientTest {
    /** One request as the server saw it. */
    private data class Seen(val path: String, val authorization: String?)

    private class Answer(
        val code: Int = 200, val body: ByteArray = ByteArray(0), val type: String = "application/atom+xml",
        val headers: Map<String, String> = emptyMap(),
    )

    /** A server that answers by path and remembers what it was asked. */
    private inner class Server(private val answers: (String) -> Answer) : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val seen = CopyOnWriteArrayList<Seen>()
        val url = "http://127.0.0.1:${socket.localPort}"

        init {
            servers += this
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { client.use(::serve) } }
                }
            }
        }

        private fun serve(client: Socket) {
            val reader = client.getInputStream().bufferedReader()
            val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return
            var authorization: String? = null
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
                if (line.startsWith("authorization:", ignoreCase = true)) authorization = line.substringAfter(':').trim()
            }
            seen += Seen(path, authorization)
            val answer = answers(path)
            val head = buildString {
                append("HTTP/1.1 ${answer.code} X\r\nContent-Type: ${answer.type}\r\nContent-Length: ${answer.body.size}\r\n")
                append("Connection: close\r\n")
                answer.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("\r\n")
            }
            client.getOutputStream().apply {
                write(head.toByteArray())
                write(answer.body)
                flush()
            }
        }

        override fun close() = socket.close()
    }

    private val servers = ArrayList<Server>()
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "opds-downloads").apply {
        deleteRecursively()
    }

    @After
    fun stopServers() = servers.forEach { it.close() }

    private fun page(vararg entries: String) = Answer(
        body = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Shelf</title>${entries.joinToString("")}</feed>""".toByteArray(),
    )

    private fun basic(user: String, password: String) =
        "Basic " + Base64.encodeToString("$user:$password".toByteArray(), Base64.NO_WRAP)

    @Test
    fun pageIsFetchedWithTheUserNameAndPassword() = runBlocking {
        val server = Server { page("<entry><title>One</title><id>1</id></entry>") }
        val feed = OpdsClient("${server.url}/opds", "reader", "sécret").feed()
        assertEquals(listOf("One"), feed.entries.map { it.title })
        assertEquals(listOf(Seen("/opds", basic("reader", "sécret"))), server.seen.toList())
    }

    @Test
    fun catalogWithoutAPasswordIsAskedWithoutOne() = runBlocking {
        val server = Server { page() }
        OpdsClient("${server.url}/opds").feed()
        assertNull(server.seen.single().authorization)
    }

    @Test
    fun refusedPasswordIsToldApartFromOtherFailures() {
        val server = Server { path -> if (path == "/opds") Answer(401) else Answer(503) }
        val client = OpdsClient("${server.url}/opds", "reader", "wrong")
        assertThrows(UnauthorizedException::class.java) { runBlocking { client.feed() } }
        val failure = assertThrows(CatalogResponseException::class.java) { runBlocking { client.feed("${server.url}/down") } }
        assertEquals(503, failure.code)
    }

    @Test
    fun pageThatIsNotACatalogIsRefused() {
        val server = Server { Answer(body = "<html>Welcome</html>".toByteArray(), type = "text/html") }
        assertThrows(NotACatalogException::class.java) { runBlocking { OpdsClient(server.url).feed() } }
    }

    @Test
    fun passwordStaysWithTheCatalogWhenLinksLeadElsewhere() = runBlocking {
        val elsewhere = Server { Answer(body = byteArrayOf(1, 2, 3), type = "image/png") }
        val catalog = Server { path ->
            when (path) {
                "/moved" -> Answer(302, headers = mapOf("Location" to "${elsewhere.url}/file.png"))
                "/here" -> Answer(301, headers = mapOf("Location" to "/there"))
                else -> Answer(body = byteArrayOf(9), type = "image/png")
            }
        }
        val client = OpdsClient("${catalog.url}/opds", "reader", "secret")

        assertArrayEquals("a redirect to another server is followed", byteArrayOf(1, 2, 3), client.picture("${catalog.url}/moved"))
        assertArrayEquals("so is a direct link", byteArrayOf(1, 2, 3), client.picture("${elsewhere.url}/cover.png"))
        assertTrue("the other server never sees the password: ${elsewhere.seen}", elsewhere.seen.all { it.authorization == null })

        assertArrayEquals(byteArrayOf(9), client.picture("${catalog.url}/here"))
        assertEquals("the catalog's own pages keep it across a redirect", basic("reader", "secret"), catalog.seen.last().authorization)
        assertEquals("/there", catalog.seen.last().path)
    }

    @Test
    fun redirectsThatNeverEndAreGivenUp() {
        val server = Server { Answer(302, headers = mapOf("Location" to "/again")) }
        assertThrows(java.io.IOException::class.java) { runBlocking { OpdsClient(server.url).feed() } }
        assertTrue("asked ${server.seen.size} times", server.seen.size <= 10)
    }

    @Test
    fun searchUsesTheTemplateOfThePageOrOfItsDescription() = runBlocking {
        val server = Server { path ->
            when {
                path == "/osd.xml" -> Answer(
                    body = """<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
                        <Url type="application/atom+xml" template="/find?q={searchTerms}"/></OpenSearchDescription>""".toByteArray(),
                    type = "application/opensearchdescription+xml",
                )
                path.startsWith("/json") -> Answer(
                    body = """{"metadata": {"title": "Found"}, "navigation": [{"href": "/more", "title": "Hit for $path"}]}""".toByteArray(),
                    type = "application/opds+json",
                )
                path.startsWith("/find") || path.startsWith("/direct") -> page("<entry><title>Hit for $path</title><id>1</id></entry>")
                else -> page()
            }
        }
        val client = OpdsClient("${server.url}/opds")

        val direct = OpdsFeed("", emptyList(), searchTemplate = "direct?q={searchTerms}")
        assertEquals("Hit for /direct?q=two+words", client.search(direct, "${server.url}/opds", "two words")!!.entries.single().title)

        val described = OpdsFeed("", emptyList(), searchDescription = "${server.url}/osd.xml")
        assertEquals("Hit for /find?q=dune", client.search(described, "${server.url}/opds", "dune")!!.entries.single().title)

        val json = OpdsFeed("", emptyList(), searchTemplate = "/json{?query,page}")
        assertEquals("an OPDS 2.0 template", "Hit for /json?query=dune", client.search(json, "${server.url}/opds", "dune")!!.entries.single().title)

        assertNull(client.search(OpdsFeed("", emptyList()), "${server.url}/opds", "dune"))
    }

    @Test
    fun bookIsDownloadedUnderTheNameTheServerGives() = runBlocking {
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        val server = Server { path ->
            when (path) {
                "/named" -> Answer(body = bytes, type = "application/epub+zip", headers = mapOf("Content-Disposition" to "attachment; filename=\"Dune - Frank Herbert.epub\""))
                "/encoded" -> Answer(body = bytes, type = "application/epub+zip", headers = mapOf("Content-Disposition" to "attachment; filename*=UTF-8''%D0%94%D1%8E%D0%BD%D0%B0.epub"))
                "/climbing" -> Answer(body = bytes, type = "application/epub+zip", headers = mapOf("Content-Disposition" to "attachment; filename=\"../../databases/reading.epub\""))
                "/misnamed" -> Answer(body = bytes, type = "application/epub+zip", headers = mapOf("Content-Disposition" to "attachment; filename=\"download.php\""))
                else -> Answer(body = bytes, type = "application/epub+zip")
            }
        }
        val client = OpdsClient(server.url)
        fun link(path: String) = OpdsLink("${server.url}$path", "application/epub+zip")

        val named = client.download(link("/named"), "Dune", dir)
        assertEquals("Dune - Frank Herbert.epub", named.name)
        assertArrayEquals(bytes, named.readBytes())

        assertEquals("Дюна.epub", client.download(link("/encoded"), "Dune", dir).name)

        val climbing = client.download(link("/climbing"), "Dune", dir)
        assertEquals("a name cannot lead out of the folder", dir.canonicalFile, climbing.canonicalFile.parentFile)

        assertEquals("What? A: \"Title\".epub".let { "What A Title.epub" }, client.download(link("/misnamed"), "What? A: \"Title\"", dir).name)
        assertEquals("book.epub", client.download(link("/plain"), "  ", dir).name)
        assertFalse("nothing half-written is left", dir.list().orEmpty().any { it.endsWith(".part") })
    }

    @Test
    fun brokenDownloadLeavesNoFile() {
        val server = Server { Answer(404) }
        val client = OpdsClient(server.url)
        assertThrows(CatalogResponseException::class.java) {
            runBlocking { client.download(OpdsLink("${server.url}/gone", "application/epub+zip"), "Gone", dir) }
        }
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test
    fun pictureTooLargeIsNotKept() = runBlocking {
        val server = Server { Answer(body = ByteArray(5000), type = "image/jpeg") }
        val client = OpdsClient(server.url)
        assertNull(client.picture("${server.url}/big.jpg", limit = 1000))
        assertEquals(5000, client.picture("${server.url}/big.jpg", limit = 5000)!!.size)
        assertNull("a picture that cannot be had is no picture", client.picture("http://127.0.0.1:1/none.jpg"))
    }

    @Test
    fun addressThatIsNotOnTheWebIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { OpdsClient("file:///sdcard/catalog.xml") }
        assertThrows(IllegalArgumentException::class.java) { OpdsClient("not an address") }
    }
}
