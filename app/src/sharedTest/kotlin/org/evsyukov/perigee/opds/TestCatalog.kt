package org.evsyukov.perigee.opds

import android.util.Base64
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** A small OPDS catalog served from the test itself. Every page wants the user name and password given. */
internal class TestCatalog(private val user: String, private val password: String, private val book: ByteArray) : AutoCloseable {
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"
    val asked = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { client.use(::serve) } }
            }
        }
    }

    private fun feed(title: String, body: String) = (
        """<feed xmlns="http://www.w3.org/2005/Atom"><title>$title</title>
        <link rel="search" type="application/atom+xml" href="/opds/find?q={searchTerms}"/>$body</feed>"""
        ).toByteArray() to "application/atom+xml"

    private fun bookEntry(title: String) = """<entry><title>$title</title><id>$title</id><author><name>Catalog Author</name></author>
        <link rel="http://opds-spec.org/acquisition" type="application/x-fictionbook+xml" href="/files/book.fb2"/>
        <link rel="http://opds-spec.org/acquisition" type="application/pdf" href="/files/book.pdf"/></entry>"""

    private fun answer(path: String): Pair<ByteArray, String>? = when {
        path == "/opds" -> feed(
            "Home Books",
            """<entry><title>All Books</title><id>all</id><content type="text">Everything there is</content>
               <link rel="subsection" type="application/atom+xml;profile=opds-catalog" href="/opds/all"/></entry>
               <entry><title>Broken Shelf</title><id>broken</id>
               <link rel="subsection" type="application/atom+xml;profile=opds-catalog" href="/opds/nowhere"/></entry>""",
        )
        path == "/opds/all" -> feed(
            "All Books",
            bookEntry("Listed Title") + """<link rel="next" type="application/atom+xml" href="/opds/all?page=2"/>""",
        )
        path == "/opds/all?page=2" -> feed("All Books", bookEntry("Second Page Title"))
        path.startsWith("/opds/find?q=") -> feed("Found", bookEntry("Found for ${path.substringAfter("q=")}"))
        path == "/files/book.fb2" -> book to "application/x-fictionbook+xml"
        path == "/page" -> "<html><body>Not a catalog</body></html>".toByteArray() to "text/html"
        else -> null
    }

    private fun serve(client: Socket) {
        val reader = client.getInputStream().bufferedReader()
        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return
        var authorization = ""
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) break
            if (line.startsWith("authorization:", ignoreCase = true)) authorization = line.substringAfter(':').trim()
        }
        asked += path
        val expected = "Basic " + Base64.encodeToString("$user:$password".toByteArray(), Base64.NO_WRAP)
        val found = answer(path)
        val (code, body, type) = when {
            authorization != expected -> Triple(401, ByteArray(0), "text/plain")
            found == null -> Triple(404, ByteArray(0), "text/plain")
            else -> Triple(200, found.first, found.second)
        }
        client.getOutputStream().apply {
            write("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(body)
            flush()
        }
    }

    override fun close() = socket.close()
}
