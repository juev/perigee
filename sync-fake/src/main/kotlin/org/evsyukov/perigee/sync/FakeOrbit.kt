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

    /** While set, the list of books is handed out a page at a time, as the real server does; otherwise whole to every asking. */
    var paged = false

    private fun page(body: JSONObject): JSONArray {
        if (!paged) return books
        val pagination = body.getJSONObject("pagination")
        val from = pagination.getInt("page") * pagination.getInt("size")
        val part = JSONArray()
        for (i in from until minOf(books.length(), from + pagination.getInt("size"))) part.put(books.get(i))
        return part
    }

    /** What the list of books is answered with instead, while not null. */
    var queryAnswer: Answer? = null
    val downloads = HashMap<Long, Answer>()

    /** Whether the reader may put books into libraries, the largest file taken, and the libraries there are. */
    var uploadAllowed = true
    var maxUploadBytes = 500L * 1024 * 1024
    var libraries = JSONArray("""[{"id":1,"name":"Books","allowedFormats":[],"organizationMode":"book_per_file","folders":[{"id":10,"name":"books"}]}]""")

    /** What a file sent is answered with instead, while not null. */
    var uploadAnswer: Answer? = null

    /** A file the server took. */
    class Upload(val libraryId: Long, val folderId: Long?, val name: String, val bytes: ByteArray, val bookId: Long)

    val uploads = ArrayList<Upload>()

    /** Done once a file is taken, before the answer goes, as to have the server fail right after. */
    var afterUpload: () -> Unit = {}

    private fun upload(request: Seen, path: String): Answer {
        if (!uploadAllowed) return error(403, "Missing permission: library_upload")
        uploadAnswer?.let { return it }
        val libraryId = path.removePrefix("/libraries/").substringBefore('/').toLong()
        val folderId = path.substringAfter("folderId=", "").toLongOrNull()
        val library = (0 until libraries.length()).map { libraries.getJSONObject(it) }.firstOrNull { it.getLong("id") == libraryId }
            ?: return error(404, "Library not found")
        if (folderId != null) {
            val folders = library.getJSONArray("folders")
            if ((0 until folders.length()).none { folders.getJSONObject(it).getLong("id") == folderId }) {
                return error(400, "Folder does not belong to this library")
            }
        }
        val (name, bytes) = filePart(request) ?: return error(400, "No file provided")
        val format = name.substringAfterLast('.', "").lowercase()
        if (format !in FORMATS) return error(400, "Unsupported file type .$format", "UPLOAD_FORMAT_UNSUPPORTED")
        val allowed = library.getJSONArray("allowedFormats")
        if (allowed.length() > 0 && (0 until allowed.length()).none { allowed.getString(it) == format }) {
            return error(400, "This library does not allow .$format files", "UPLOAD_FORMAT_NOT_ALLOWED")
        }
        if (bytes.size > maxUploadBytes) return error(413, "File exceeds the upload limit", "UPLOAD_TOO_LARGE")
        if (bytes.isEmpty()) return error(400, "File must not be empty", "UPLOAD_EMPTY")
        // Filed by its name: the same name again is the same place.
        if (uploads.any { it.libraryId == libraryId && it.name == name }) {
            return error(409, "A file named \"$name\" already exists at the target location", "UPLOAD_DESTINATION_CONFLICT")
        }
        val bookId = marks.incrementAndGet().toLong()
        uploads += Upload(libraryId, folderId, name, bytes, bookId)
        files[partialMd5(bytes)] = bookId to bookId * 10
        afterUpload()
        return Answer(201, JSONObject().put("bookId", bookId).put("filename", name).put("format", format).put("sizeBytes", bytes.size).toString())
    }

    /** The name and contents of the file a form carries. */
    private fun filePart(request: Seen): Pair<String, ByteArray>? {
        val boundary = request.headers["content-type"]?.substringAfter("boundary=", "")?.takeIf { it.isNotEmpty() } ?: return null
        val body = request.bytes
        val headEnd = indexOf(body, "\r\n\r\n".toByteArray(), 0).takeIf { it >= 0 } ?: return null
        val head = body.copyOfRange(0, headEnd).decodeToString()
        val name = Regex("filename=\"([^\"]*)\"").find(head)?.groupValues?.get(1) ?: return null
        val end = indexOf(body, "\r\n--$boundary--".toByteArray(), headEnd).takeIf { it >= 0 } ?: return null
        return name to body.copyOfRange(headEnd + 4, end)
    }

    private fun indexOf(bytes: ByteArray, part: ByteArray, from: Int): Int {
        for (i in from..bytes.size - part.size) {
            if ((part.indices).all { bytes[i + it] == part[it] }) return i
        }
        return -1
    }

    /** The latest place of each file, by its fingerprint, as the server answers a device with it. */
    val places = HashMap<String, JSONObject>()

    /** Status and rating by book, as set through the devices' channel. */
    val statuses = HashMap<Long, String>()
    val ratings = HashMap<Long, Int?>()

    /** Pages read the server took, and how many of them it had been told of before. */
    val pagesRead = ArrayList<JSONObject>()
    var pagesTwice = 0

    /** A quote as the server keeps it, with what it knows of the device having it. */
    class Quote(
        val serverId: Long, var stamp: String?, val pos0: String, val pos1: String, var text: String, var note: String?,
        var color: String, var version: Int = 1, var deleted: Boolean = false,
        /** The device has it, as of [onDevice]; a removal the device was told of is [removalKnown]. */
        var known: Boolean = false, var onDevice: Int = 0, var removalKnown: Boolean = false,
        /** Given in the web reader or through the reader's session; the devices' channel says nothing of it. */
        var starred: Boolean = false,
    )

    class Bookmark(
        val serverId: Long, val pos: String, var title: String, var key: String? = null, var deleted: Boolean = false,
        var removalKnown: Boolean = false,
    )

    /** Quotes and bookmarks by the fingerprint of the file. */
    val quotes = HashMap<String, MutableList<Quote>>()
    val bookmarks = HashMap<String, MutableList<Bookmark>>()
    private val marks = AtomicInteger(100)

    /** While set, bookmarks from devices are refused, as for a format the server has no places in. */
    var bookmarksRefused = false

    /** How many quotes a list of them is handed over in parts of; the real server's is a hundred. */
    var handedOverAtOnce = 100

    /** A quote made in the web reader. */
    fun webQuote(hash: String, pos0: String, pos1: String, text: String, note: String? = null, color: String = "yellow"): Quote =
        Quote(marks.incrementAndGet().toLong(), null, pos0, pos1, text, note, color).also { quotes.getOrPut(hash) { ArrayList() } += it }

    fun webBookmark(hash: String, pos: String, title: String): Bookmark =
        Bookmark(marks.incrementAndGet().toLong(), pos, title).also { bookmarks.getOrPut(hash) { ArrayList() } += it }

    private fun key(stamp: String, place: String) = md5("$stamp|$place")

    /** The fingerprint of the file of a book, by the server's number for the book. */
    private fun hashOf(bookId: Long): String? = files.entries.firstOrNull { it.value.first == bookId }?.key

    /** Quotes and bookmarks as the reader's session lists them, and a star given or taken off through it. */
    private fun marksOfBook(request: Seen, path: String): Answer {
        val parts = path.removePrefix("/books/").split('/')
        val hash = parts[0].toLongOrNull()?.let(::hashOf) ?: return error(404, "Book not found")
        val list = JSONArray()
        if (parts[1] == "bookmarks") {
            for (bookmark in bookmarks[hash].orEmpty().filter { !it.deleted }) {
                list.put(JSONObject().put("id", bookmark.serverId).put("cfi", JSONObject.NULL).put("title", bookmark.title))
            }
            return Answer(200, list.toString())
        }
        if (request.method == "PATCH") {
            val quote = quotes[hash].orEmpty().firstOrNull { it.serverId == parts[2].toLong() && !it.deleted } ?: return error(404, "Annotation not found")
            quote.starred = JSONObject(request.body).getBoolean("starred")
            return Answer(200, "{}")
        }
        for (quote in quotes[hash].orEmpty().filter { !it.deleted }) {
            list.put(
                JSONObject().put("id", quote.serverId).put("text", quote.text).put("note", quote.note ?: JSONObject.NULL)
                    .put("starredAt", if (quote.starred) "2031-01-01T00:00:00.000Z" else JSONObject.NULL),
            )
        }
        return Answer(200, list.toString())
    }

    private fun exchangeQuotes(body: JSONObject): Answer {
        val book = body.getJSONArray("books").getJSONObject(0)
        val hash = book.getString("hash")
        if (hash !in files) return Answer(201, JSONObject().put("results", JSONArray()).put("unmatched", JSONArray().put(hash)).toString())
        val list = quotes.getOrPut(hash) { ArrayList() }
        val changes = book.getJSONArray("changes")
        for (i in 0 until changes.length()) {
            val change = changes.getJSONObject(i)
            val known = list.firstOrNull { it.stamp == change.getString("datetime") && it.pos0 == change.getString("pos0") }
            val note = if (change.has("note")) change.getString("note") else null
            if (known == null) {
                list += Quote(
                    marks.incrementAndGet().toLong(), change.getString("datetime"), change.getString("pos0"), change.optString("pos1"),
                    change.optString("text"), note, change.optString("color"), known = true, onDevice = 1,
                )
            } else if (!known.deleted && (known.note != note || known.color != change.optString("color"))) {
                known.note = note
                known.color = change.optString("color")
                known.version++
                known.onDevice = known.version
            }
        }
        if (book.getBoolean("keysComplete")) {
            val keys = book.getJSONArray("keys")
            val present = (0 until keys.length()).map { keys.getJSONObject(it) }
            for (quote in list) {
                if (!quote.known || quote.deleted) continue
                val stamp = quote.stamp ?: continue
                if (present.any { it.getString("k") == key(stamp, quote.pos0) || it.getString("dt") == stamp }) continue
                quote.deleted = true
                quote.removalKnown = true
            }
        }
        val add = JSONArray()
        val edit = JSONArray()
        val delete = JSONArray()
        var room = handedOverAtOnce
        var more = false
        for (quote in list) {
            val entry = JSONObject().put("serverId", quote.serverId).put("version", quote.version)
                .put("drawer", "lighten").put("color", quote.color).put("text", quote.text).put("note", quote.note ?: JSONObject.NULL)
                .put("chapter", JSONObject.NULL)
            val into = when {
                !quote.deleted && !quote.known -> add
                !quote.deleted && quote.version > quote.onDevice -> edit
                quote.deleted && quote.known && !quote.removalKnown -> delete
                else -> continue
            }
            if (room == 0) {
                more = true
                break
            }
            room--
            if (quote.stamp == null) quote.stamp = "2031-01-01 00:00:%02d".format(quote.serverId % 60)
            entry.put("datetime", quote.stamp)
            when (into) {
                add -> entry.put("datetimeUpdated", JSONObject.NULL).put("posFormat", "xpointer").put("pos0", quote.pos0).put("pos1", quote.pos1)
                else -> entry.put("key", key(quote.stamp!!, quote.pos0)).put("datetimeUpdated", "2031-01-01 00:01:00")
            }
            into.put(entry)
        }
        val result = JSONObject().put("hash", hash).put("bookId", files.getValue(hash).first)
            .put("toApply", JSONObject().put("add", add).put("edit", edit).put("delete", delete)).put("more", more)
        return Answer(201, JSONObject().put("results", JSONArray().put(result)).put("unmatched", JSONArray()).toString())
    }

    private fun ackQuotes(body: JSONObject): Answer {
        val book = body.getJSONArray("books").getJSONObject(0)
        val list = quotes[book.getString("hash")].orEmpty()
        val applied = book.getJSONArray("applied")
        for (i in 0 until applied.length()) {
            val entry = applied.getJSONObject(i)
            val quote = list.firstOrNull { it.serverId == entry.getLong("serverId") } ?: continue
            if (entry.getString("status") != "applied") continue
            quote.known = true
            quote.onDevice = entry.getInt("version")
        }
        val deleted = book.getJSONArray("deleted")
        for (i in 0 until deleted.length()) {
            list.firstOrNull { it.serverId == deleted.getJSONObject(i).getLong("serverId") }?.removalKnown = true
        }
        return Answer(201, "{}")
    }

    private fun exchangeBookmarks(body: JSONObject): Answer {
        val book = body.getJSONArray("books").getJSONObject(0)
        val hash = book.getString("hash")
        if (hash !in files) return Answer(201, JSONObject().put("results", JSONArray()).put("unmatched", JSONArray().put(hash)).toString())
        val list = bookmarks.getOrPut(hash) { ArrayList() }
        val changes = book.getJSONArray("changes")
        var rejected = 0
        for (i in 0 until changes.length()) {
            val change = changes.getJSONObject(i)
            val k = key(change.getString("datetime"), change.getString("pos"))
            if (list.any { it.key == k }) continue
            if (bookmarksRefused) {
                rejected++
                continue
            }
            // Titled as the server titles a bookmark: by its words, else its chapter.
            val title = change.optString("note").trim().ifEmpty { change.optString("chapter").trim() }.ifEmpty { "Bookmark" }
            list += Bookmark(marks.incrementAndGet().toLong(), change.getString("pos"), title, key = k)
        }
        if (book.getBoolean("keysComplete")) {
            val keys = book.getJSONArray("keys")
            val present = (0 until keys.length()).map { keys.getJSONObject(it).getString("k") }.toSet()
            for (bookmark in list) {
                if (bookmark.key == null || bookmark.deleted || bookmark.key in present) continue
                bookmark.deleted = true
                bookmark.removalKnown = true
            }
        }
        val add = JSONArray()
        val delete = JSONArray()
        for (bookmark in list) {
            when {
                !bookmark.deleted && bookmark.key == null ->
                    add.put(JSONObject().put("serverId", bookmark.serverId).put("pos", bookmark.pos).put("pageno", JSONObject.NULL).put("title", bookmark.title))
                bookmark.deleted && bookmark.key != null && !bookmark.removalKnown ->
                    delete.put(JSONObject().put("serverId", bookmark.serverId).put("key", bookmark.key).put("datetime", JSONObject.NULL))
            }
        }
        val result = JSONObject().put("hash", hash).put("bookId", files.getValue(hash).first).put("rejected", rejected)
            .put("toApply", JSONObject().put("add", add).put("delete", delete)).put("more", false)
        return Answer(201, JSONObject().put("results", JSONArray().put(result)).put("unmatched", JSONArray()).toString())
    }

    private fun ackBookmarks(body: JSONObject): Answer {
        val book = body.getJSONArray("books").getJSONObject(0)
        val list = bookmarks[book.getString("hash")].orEmpty()
        val applied = book.getJSONArray("applied")
        for (i in 0 until applied.length()) {
            val entry = applied.getJSONObject(i)
            list.firstOrNull { it.serverId == entry.getLong("serverId") }?.key = entry.getString("key")
        }
        val deleted = book.getJSONArray("deleted")
        for (i in 0 until deleted.length()) {
            list.firstOrNull { it.serverId == deleted.getJSONObject(i).getLong("serverId") }?.removalKnown = true
        }
        return Answer(201, "{}")
    }

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
        val form = request.headers["content-type"].orEmpty().startsWith("multipart/")
        val body = if (form || request.body.isBlank()) JSONObject() else JSONObject(request.body)
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
            when (path) {
                "/koreader/plugin/annotations/exchange" -> return exchangeQuotes(body)
                "/koreader/plugin/annotations/exchange-ack" -> return ackQuotes(body)
                "/koreader/plugin/bookmarks/exchange" -> return exchangeBookmarks(body)
                "/koreader/plugin/bookmarks/exchange-ack" -> return ackBookmarks(body)
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
            path == "/uploads/capabilities" -> Answer(
                200,
                JSONObject().put("maxFileSizeBytes", maxUploadBytes).put("chunkSizeBytes", 16 * 1024 * 1024)
                    .put("supportedFormats", JSONArray(FORMATS)).put("canUploadToLibrary", uploadAllowed).put("canUseBookDock", false)
                    .put("libraries", if (uploadAllowed) libraries else JSONArray()).toString(),
            )
            path.startsWith("/libraries/") && request.method == "POST" -> upload(request, path)
            path == "/koreader/credentials" && !deviceAllowed -> error(403, "Missing permission: koreader_sync")
            path == "/koreader/credentials" && request.method == "GET" ->
                Answer(200, deviceUser?.let { JSONObject().put("username", it).put("syncEnabled", true).toString() }.orEmpty())
            path == "/koreader/credentials" && request.method == "PATCH" -> when {
                deviceUser == null -> error(404, "KOReader credentials not found")
                else -> {
                    deviceKey = md5(body.getString("password"))
                    Answer(200, "{\"success\":true}")
                }
            }
            path == "/koreader/credentials" && request.method == "POST" -> when {
                deviceUser != null -> error(409, "KOReader credentials already exist")
                body.getString("username") in takenNames -> error(409, "Username already taken")
                else -> {
                    deviceUser = body.getString("username")
                    deviceKey = md5(body.getString("password"))
                    Answer(201, "{\"success\":true}")
                }
            }
            path == "/books/query" -> queryAnswer ?: Answer(201, JSONObject().put("items", page(body)).put("total", total ?: books.length()).put("page", body.getJSONObject("pagination").getInt("page")).toString())
            Regex("/books/\\d+/(annotations|bookmarks)(/\\d+)?").matches(path) -> marksOfBook(request, path)
            path.startsWith("/books/files/") -> downloads[path.removePrefix("/books/files/").removeSuffix("/download").toLong()] ?: error(404, "No file")
            path.endsWith("/thumbnail") -> Answer(200, byteArrayOf(1, 2, 3), "image/jpeg")
            else -> error(404, "Cannot ${request.method} $path")
        }
    }

    override fun close() = server.close()

    companion object {
        /** What the real server takes, of the formats the app reads. */
        val FORMATS = listOf("epub", "kepub", "pdf", "mobi", "azw3", "fb2")

        /** The fingerprint the server knows a file by: KOReader's, of samples a kilobyte long spread through the file. */
        fun partialMd5(bytes: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("MD5")
            for (i in -1..10) {
                val offset = if (i < 0) 0L else 1024L shl (2 * i)
                if (offset >= bytes.size) break
                digest.update(bytes, offset.toInt(), minOf(1024, bytes.size - offset.toInt()))
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** The key the devices' channel takes for a password. */
        fun md5(text: String): String =
            java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
