package org.evsyukov.perigee.sync

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** One request as the server saw it. Header names are in lower case. */
data class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String) {
    /** The body as it came, for one that is not text, as a file sent in a form. */
    var bytes: ByteArray = body.toByteArray()
        internal set
}

class Answer(
    val code: Int = 200, val body: ByteArray = ByteArray(0), val type: String = "application/json",
    val headers: Map<String, String> = emptyMap(),
) {
    constructor(code: Int, json: String) : this(code, json.toByteArray())

    companion object {
        /** No answer at all: the connection is closed, as when the network goes. */
        val Dropped = Answer(0)
    }
}

/** A server that answers as the test tells it to and remembers what it was asked. */
class TestServer(private val answers: (Seen) -> Answer) : AutoCloseable {
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val seen = CopyOnWriteArrayList<Seen>()
    val url = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { client.use(::serve) } }
            }
        }
    }

    /** The requests to a path, in the order they came. */
    fun asked(path: String): List<Seen> = seen.filter { it.path == path }

    private fun serve(client: Socket) {
        val input = client.getInputStream().buffered()
        val first = readLine(input)?.split(' ') ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isNullOrEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val body = ByteArray(headers["content-length"]?.toIntOrNull() ?: 0)
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }
        val request = Seen(first[0], first.getOrElse(1) { "" }, headers, body.decodeToString()).also { it.bytes = body }
        seen += request
        val answer = answers(request)
        if (answer === Answer.Dropped) return
        client.getOutputStream().apply {
            val extra = answer.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
            write(
                ("HTTP/1.1 ${answer.code} X\r\nContent-Type: ${answer.type}\r\nContent-Length: ${answer.body.size}\r\n" +
                    "${extra}Connection: close\r\n\r\n").toByteArray(),
            )
            write(answer.body)
            flush()
        }
    }

    /** A line of the request's head; bytes, as the body that follows is not text to a reader. */
    private fun readLine(input: java.io.InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
        }
    }

    override fun close() = socket.close()
}
