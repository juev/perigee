package org.evsyukov.perigee.sync

import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * A stand-in for a bookOrbit server that answers as version 3.3.0 was seen to
 * answer, as far as the client goes. What it lets through is the test's to set.
 */
class FakeOrbit : AutoCloseable {
    var version = "v3.3.0"
    var passwordLogin = true
    var password = "right-password"
    var loginAnswer: Answer? = null

    /** When the tokens given out from now on run out. */
    var accessExpiresAt = "2030-01-01T00:15:00.000Z"

    /** Tokens that are taken. A renewal takes the old pair out and puts a new one in. */
    val access = HashSet<String>()
    val refresh = HashSet<String>()
    private val issued = AtomicInteger()

    /** The devices' channel: no user name until one is made. */
    var deviceUser: String? = null
    var deviceKey: String? = null
    var deviceAllowed = true
    var deviceSwitchedOn = true
    val takenNames = HashSet<String>()

    /** Fingerprint to (book, file). */
    val files = HashMap<String, Pair<Long, Long>>()
    var books = JSONArray()

    /** How many books the server says it has; the number it gives when null. */
    var total: Int? = null

    /** What the list of books is answered with instead, while not null. */
    var queryAnswer: Answer? = null
    val downloads = HashMap<Long, Answer>()

    /** The latest place of each file, by its fingerprint, as the server answers a device with it. */
    val places = HashMap<String, JSONObject>()

    /** Status and rating by book, as set through the devices' channel. */
    val statuses = HashMap<Long, String>()
    val ratings = HashMap<Long, Int?>()

    /** Pages read the server took, and how many of them it had been told of before. */
    val pagesRead = ArrayList<JSONObject>()
    var pagesTwice = 0

    private val server = TestServer(::answer)
    val url get() = server.url
    val seen get() = server.seen
    fun asked(path: String) = server.asked("/api/v1$path")

    private fun error(code: Int, message: String, errorCode: String? = null, retry: Int? = null) = Answer(
        code,
        JSONObject().put("statusCode", code).put("message", message).apply {
            if (errorCode != null) put("errorCode", errorCode)
            if (retry != null) put("retryAfterSeconds", retry)
        }.toString(),
    )

    private fun tokens(): JSONObject {
        val n = issued.incrementAndGet()
        val a = "access-$n"
        val r = "%064x".format(n)
        access += a
        refresh += r
        return JSONObject().put("accessToken", a).put("accessTokenExpiresAt", accessExpiresAt)
            .put("refreshToken", r).put("refreshTokenExpiresAt", "2030-01-08T00:00:00.000Z").put("sessionId", n)
    }

    /** While set, the devices' channel does what it is asked and then fails to say so, as when an answer is lost. */
    var losingAnswers = false

    /** While set, the devices' channel does nothing and says so. */
    var down = false

    private fun answer(request: Seen): Answer {
        val path = request.path.removePrefix("/api/v1")
        val device = path.startsWith("/koreader/plugin/") || path.startsWith("/koreader/syncs/")
        if (device && down) return error(503, "Service Unavailable")
        val answer = respond(request)
        return if (device && losingAnswers && request.method != "GET") error(502, "Bad Gateway") else answer
    }

    private fun respond(request: Seen): Answer {
        val path = request.path.removePrefix("/api/v1")
        val body = if (request.body.isBlank()) JSONObject() else JSONObject(request.body)
        if (path == "/auth/login-options") {
            return Answer(200, JSONObject().put("passwordLoginEnabled", passwordLogin).put("oidcProviders", JSONArray()).toString())
        }
        if (path == "/auth/login") {
            loginAnswer?.let { return it }
            if (body.getString("password") != password) return error(401, "Invalid credentials")
            return Answer(200, tokens().put("user", JSONObject().put("id", 1).put("username", body.getString("username")).put("name", "Reader Name")).toString())
        }
        if (path == "/auth/refresh") {
            if (!refresh.remove(body.optString("refreshToken"))) return error(401, "Unauthorized")
            return Answer(200, tokens().toString())
        }
        if (path == "/auth/logout") {
            refresh.remove(body.optString("refreshToken"))
            return Answer(200, "{}")
        }
        if (path.startsWith("/koreader/users/auth") || path.startsWith("/koreader/plugin/") || path.startsWith("/koreader/syncs/")) {
            if (request.headers["x-auth-user"] != deviceUser || request.headers["x-auth-key"] != deviceKey) return error(401, "Invalid credentials")
            if (!deviceSwitchedOn) return error(403, "Sync is disabled")
            if (!deviceAllowed) return error(401, "KOReader sync permission revoked")
            if (path == "/koreader/users/auth") return Answer(200, JSONObject().put("authorized", "OK").toString())
            if (path == "/koreader/syncs/progress" && request.method == "PUT") {
                val hash = body.getString("document")
                if (hash !in files) return error(404, "Book not found for the given document hash")
                places[hash] = body
                return Answer(200, JSONObject().put("document", hash).put("timestamp", body.optLong("timestamp")).toString())
            }
            if (path.startsWith("/koreader/syncs/progress/")) {
                return Answer(200, (places[path.substringAfterLast('/')] ?: JSONObject()).toString())
            }
            if (path.startsWith("/koreader/plugin/catalog/books/")) {
                val id = path.removePrefix("/koreader/plugin/catalog/books/").substringBefore('/').toLong()
                if (path.endsWith("/read-status")) statuses[id] = body.getString("status")
                if (path.endsWith("/rating")) ratings[id] = if (body.isNull("rating")) null else body.getInt("rating")
                return Answer(200, "{}")
            }
            if (path == "/koreader/plugin/page-stats") {
                val books = body.getJSONArray("books")
                val unmatched = JSONArray()
                for (i in 0 until books.length()) {
                    val book = books.getJSONObject(i)
                    if (book.getString("hash") !in files) {
                        unmatched.put(book.getString("hash"))
                        continue
                    }
                    val events = book.getJSONArray("events")
                    for (j in 0 until events.length()) {
                        val event = events.getJSONObject(j).put("hash", book.getString("hash"))
                        if (pagesRead.any { it.toString() == event.toString() }) pagesTwice++ else pagesRead += event
                    }
                }
                return Answer(201, JSONObject().put("results", JSONArray()).put("unmatched", unmatched).toString())
            }
            val hashes = body.getJSONArray("hashes")
            val matches = JSONArray()
            for (i in 0 until hashes.length()) {
                val file = files[hashes.getString(i)] ?: continue
                matches.put(JSONObject().put("hash", hashes.getString(i)).put("bookId", file.first).put("bookFileId", file.second))
            }
            return Answer(201, JSONObject().put("matches", matches).put("libraryVersion", "0123456789abcdef").toString())
        }
        if (request.headers["authorization"]?.removePrefix("Bearer ") !in access) return error(401, "Unauthorized")
        return when {
            path == "/app-info" -> Answer(200, JSONObject().put("version", version).put("features", JSONArray()).toString())
            path == "/koreader/credentials" && !deviceAllowed -> error(403, "Missing permission: koreader_sync")
            path == "/koreader/credentials" && request.method == "GET" ->
                Answer(200, deviceUser?.let { JSONObject().put("username", it).put("syncEnabled", true).toString() }.orEmpty())
            path == "/koreader/credentials" && request.method == "POST" -> when {
                deviceUser != null -> error(409, "KOReader credentials already exist")
                body.getString("username") in takenNames -> error(409, "Username already taken")
                else -> {
                    deviceUser = body.getString("username")
                    deviceKey = md5(body.getString("password"))
                    Answer(201, "{\"success\":true}")
                }
            }
            path == "/books/query" -> queryAnswer ?: Answer(201, JSONObject().put("items", books).put("total", total ?: books.length()).put("page", body.getJSONObject("pagination").getInt("page")).toString())
            path.startsWith("/books/files/") -> downloads[path.removePrefix("/books/files/").removeSuffix("/download").toLong()] ?: error(404, "No file")
            path.endsWith("/thumbnail") -> Answer(200, byteArrayOf(1, 2, 3), "image/jpeg")
            else -> error(404, "Cannot ${request.method} $path")
        }
    }

    override fun close() = server.close()

    companion object {
        /** The key the devices' channel takes for a password. */
        fun md5(text: String): String =
            java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
