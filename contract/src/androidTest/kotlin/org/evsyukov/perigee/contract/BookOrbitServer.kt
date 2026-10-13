package org.evsyukov.perigee.contract

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Minimal client of the contract server: just the calls the position contract needs. */
internal class BookOrbitServer(private val baseUrl: String) {
    data class Response(val code: Int, val body: String) {
        fun json() = JSONObject(body)
        fun array() = JSONArray(body)
    }

    private lateinit var accessToken: String

    fun login() {
        val response = request(
            "POST", "/auth/login",
            JSONObject().put("username", USERNAME).put("password", PASSWORD).put("clientKind", "native"),
        )
        check(response.code in 200..201) { "login failed: $response" }
        accessToken = response.json().getString("accessToken")
    }

    /** The KOReader channel needs its own credentials; one set exists per user. */
    fun ensureKoreaderCredentials() {
        val response = jwt(
            "POST", "/koreader/credentials",
            JSONObject().put("username", KO_USERNAME).put("password", KO_PASSWORD),
        )
        check(response.code in 200..201 || response.code == 409) { "credentials failed: $response" }
    }

    /** OPDS is off on a new server and has credentials of its own, any number per user. */
    fun ensureOpds() {
        val enabled = jwt("PATCH", "/app-settings/opds_enabled", JSONObject().put("value", "true"))
        check(enabled.code == 200) { "enabling OPDS failed: $enabled" }
        val user = jwt(
            "POST", "/opds-users",
            JSONObject().put("username", OPDS_USERNAME).put("password", OPDS_PASSWORD),
        )
        check(user.code in 200..201 || user.code == 409) { "OPDS user failed: $user" }
    }

    val opdsUrl: String get() = "$baseUrl/api/v1/opds"

    fun jwt(method: String, path: String, body: JSONObject? = null) =
        request(method, path, body, mapOf("authorization" to "Bearer $accessToken"))

    fun koreader(method: String, path: String, body: JSONObject? = null) =
        request(method, path, body, mapOf("x-auth-user" to KO_USERNAME, "x-auth-key" to md5(KO_PASSWORD.toByteArray())))

    data class Book(val bookId: Int, val fileId: Int)

    fun bookByTitle(title: String): Book {
        val query = JSONObject().put("pagination", JSONObject().put("page", 0).put("size", 50))
        val items = jwt("POST", "/books/query", query).json().getJSONArray("items")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.getString("title") == title) {
                return Book(item.getInt("id"), item.getJSONArray("files").getJSONObject(0).getInt("id"))
            }
        }
        error("book '$title' is not on the server; run contract/up.py")
    }

    fun download(fileId: Int, target: File) {
        val connection = open("GET", "/books/files/$fileId/download", mapOf("authorization" to "Bearer $accessToken"))
        check(connection.responseCode == 200) { "download failed: ${connection.responseCode}" }
        connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    private fun request(method: String, path: String, body: JSONObject?, headers: Map<String, String> = emptyMap()): Response {
        val connection = open(method, path, headers)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("content-type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = connection.responseCode
        val stream = if (code < 400) connection.inputStream else connection.errorStream
        return Response(code, stream?.use { it.readBytes().decodeToString() }.orEmpty())
    }

    private fun open(method: String, path: String, headers: Map<String, String>): HttpURLConnection {
        val connection = URL("$baseUrl/api/v1$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        headers.forEach(connection::setRequestProperty)
        return connection
    }

    companion object {
        const val USERNAME = "reader"
        const val PASSWORD = "Contract-Test-1"
        const val KO_USERNAME = "reader-ko"
        const val KO_PASSWORD = "ko-secret-1"
        const val OPDS_USERNAME = "reader-opds"
        const val OPDS_PASSWORD = "opds-secret-1"

        fun md5(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * KOReader's document fingerprint (util.partialMD5): 1 KB samples at offsets
         * 0, 1 KB, 4 KB, 16 KB … up to 1 GB. The server matches files by it.
         */
        fun partialMd5(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            val sample = ByteArray(1024)
            RandomAccessFile(file, "r").use { raf ->
                for (i in -1..10) {
                    val offset = if (i < 0) 0L else 1024L shl (2 * i)
                    if (offset >= raf.length()) break
                    raf.seek(offset)
                    val read = raf.read(sample)
                    if (read <= 0) break
                    digest.update(sample, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
