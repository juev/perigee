package org.evsyukov.perigee.sync

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
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            Reply(code, stream?.use { it.readBytes().decodeToString() }.orEmpty())
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
        const val USER_AGENT = "Perigee"
    }
}
