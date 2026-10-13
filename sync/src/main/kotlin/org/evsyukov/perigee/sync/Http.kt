package org.evsyukov.perigee.sync

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONException
import org.json.JSONObject

/** An answer of the server, read whole. */
internal class Reply(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299

    /** The answer as an object; null when it is empty or the word "null". */
    fun json(): JSONObject? {
        val text = body.trim()
        if (text.isEmpty() || text == "null") return null
        return try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IOException("the server did not answer in JSON", e)
        }
    }

    /** What the server says went wrong: its message, and the code it gives some failures. */
    val message: String get() = runCatching { json()?.opt("message")?.toString() }.getOrNull().orEmpty()
    val errorCode: String get() = runCatching { json()?.optString("errorCode") }.getOrNull().orEmpty()
    val retryAfter: Int get() = runCatching { json()?.optInt("retryAfterSeconds") }.getOrNull() ?: 0
}

/**
 * Requests to the API of one server. Redirects are not followed: the tokens
 * and passwords sent along are for this server only.
 */
internal class Http(base: String) {
    private val api = "$base/api/v1"

    fun call(method: String, path: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): Reply =
        open(method, path, headers) { connection ->
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            answer(connection)
        }

    /**
     * Sends a file as the one part of a form, under [name]. [sent] is told how
     * many bytes of the file have gone, as they go.
     */
    fun upload(path: String, headers: Map<String, String>, file: File, name: String, sent: (Long) -> Unit): Reply =
        open("POST", path, headers) { connection ->
            val boundary = "perigee-" + java.util.UUID.randomUUID()
            // The name goes as it is, in UTF-8, as browsers send it; a quote or a line end would break the header.
            val quoted = name.replace("\"", "%22").replace("\r", " ").replace("\n", " ")
            val head = (
                "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$quoted\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n"
                ).toByteArray()
            val tail = "\r\n--$boundary--\r\n".toByteArray()
            connection.doOutput = true
            // The server answers once it has filed the book away, not as the last byte arrives.
            connection.readTimeout = UPLOAD_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            // Streamed: a book is not worth holding whole in memory.
            connection.setFixedLengthStreamingMode(head.size + file.length() + tail.size)
            try {
                connection.outputStream.use { out ->
                    out.write(head)
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            total += read
                            sent(total)
                        }
                    }
                    out.write(tail)
                }
            } catch (e: IOException) {
                // A server that refuses at once stops listening; what it said is worth more than the broken pipe.
                return@open runCatching { answer(connection) }.getOrNull()?.takeIf { !it.ok } ?: throw e
            }
            answer(connection)
        }

    private fun answer(connection: HttpURLConnection): Reply {
        val code = connection.responseCode
        val stream = if (code < 400) connection.inputStream else connection.errorStream
        return Reply(code, stream?.use { it.readBytes().decodeToString() }.orEmpty())
    }

    /** A request whose answer is not JSON, as a file: [read] is given the connection once the answer has begun. */
    fun <T> open(method: String, path: String, headers: Map<String, String>, read: (HttpURLConnection) -> T): T {
        val connection = URL(api + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json, */*;q=0.5")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach(connection::setRequestProperty)
            return read(connection)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000
        const val UPLOAD_TIMEOUT_MS = 120_000
        const val USER_AGENT = "Perigee"
    }
}
