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

    /** The providers readers are signed in through, as the list of ways in names them. */
    var oidcProviders = JSONArray()

    /** Where the server says the provider's sign-in page is; empty as when it cannot reach the provider. */
    var authorizationEndpoint = "https://id.example/authorize"

    /** States handed out and not used yet; a state is good for one callback. */
    val oidcStates = HashSet<String>()

    /** What the callback is answered with instead, while not null. */
    var oidcCallbackAnswer: Answer? = null

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

    private fun page(body: JSONObject, books: JSONArray, always: Boolean = false): JSONArray {
        if (!paged && !always) return books
        val pagination = body.getJSONObject("pagination")
        val from = pagination.getInt("page") * pagination.getInt("size")
        val part = JSONArray()
        for (i in from until minOf(books.length(), from + pagination.getInt("size"))) part.put(books.get(i))
        return part
    }

    /**
     * The books the query's filter lets through. Of its rules only "has a file in one of these
     * formats" is understood, matched on any of the book's files as the server does.
     */
    private fun matching(body: JSONObject, books: JSONArray = this.books): JSONArray {
        val rules = body.optJSONObject("filter")?.optJSONArray("rules") ?: return books
        val wanted = (0 until rules.length()).map { rules.getJSONObject(it) }
            .filter { it.optString("field") == "format" && it.optString("operator") == "includesAny" }
            .map { rule -> rule.getJSONArray("value").let { formats -> (0 until formats.length()).map(formats::getString).toSet() } }
        val others = (0 until rules.length()).map { rules.getJSONObject(it) }.filter { it.optString("field") != "format" }
        val found = JSONArray()
        for (i in 0 until books.length()) {
            val book = books.getJSONObject(i)
            val files = book.optJSONArray("files") ?: JSONArray()
            val formats = (0 until files.length()).map { files.getJSONObject(it).optString("format") }
            if (wanted.all { formats.any(it::contains) } && others.all { holds(it, card(book)) }) found.put(book)
        }
        return found
    }

    /** Fields of the catalogue's filter a version of the server does not know, and refuses a query naming. */
    var unknownFields = emptySet<String>()

    /** Whether [book] holds to one of the rules the server matches by: status, author, series, genre, library. */
    private fun holds(rule: JSONObject, book: JSONObject): Boolean {
        fun values() = rule.opt("value").let { if (it is JSONArray) (0 until it.length()).map(it::getString) else listOf(it.toString()) }
        fun list(name: String) = book.optJSONArray(name)?.let { names -> (0 until names.length()).map(names::getString) }.orEmpty()
        return when (rule.getString("field")) {
            "readStatus" -> (book.optJSONObject("readStatus")?.optString("status") ?: "unread") in values()
            "author" -> values().any { part -> list("authors").any { it.contains(part, ignoreCase = true) } }
            "series" -> !book.isNull("seriesName") && book.optString("seriesName").contains(values().single(), ignoreCase = true)
            "genre" -> values().any(list("genres")::contains)
            "library" -> values().any { it == (if (book.has("libraryName")) book.getString("libraryName") else libraryNames.first()) }
            else -> true
        }
    }

    /** What the list of books is answered with instead, while not null. */
    var queryAnswer: Answer? = null

    /** A query naming a field of [unknownFields] is refused, as a version of the server that has not got it refuses it. */
    private fun refusedQuery(body: JSONObject): Answer? {
        val rules = body.optJSONObject("filter")?.optJSONArray("rules") ?: return null
        val named = (0 until rules.length()).map { rules.getJSONObject(it).optString("field") }
        return if (named.any { it in unknownFields }) error(400, "Invalid filter field") else null
    }

    /** What the books say they are about, as HTML, by book; a book not here has no description. */
    val descriptions = HashMap<Long, String>()

    /** What a book asked about alone is answered with instead, while not null. */
    var detailAnswer: Answer? = null

    /**
     * A book of [books] as the server tells of it when asked about it alone: authors are objects
     * here, and what the list does not carry is null unless the book in [books] has it.
     */
    private fun detail(id: Long): Answer {
        val book = (0 until books.length()).map { books.getJSONObject(it) }.firstOrNull { it.optLong("id") == id }
            ?: return error(404, "Book not found")
        val names = book.optJSONArray("authors") ?: JSONArray()
        val authors = JSONArray()
        for (i in 0 until names.length()) authors.put(JSONObject().put("id", i + 1).put("name", names.getString(i)).put("sortName", names.getString(i)))
        val answer = JSONObject().put("id", id).put("authors", authors).put("description", descriptions[id] ?: JSONObject.NULL)
        for (field in listOf("title", "subtitle", "seriesName", "seriesIndex", "publisher", "publishedYear", "language", "pageCount", "isbn13")) {
            answer.put(field, if (book.has(field)) book.get(field) else JSONObject.NULL)
        }
        val files = JSONArray()
        book.optJSONArray("files")?.let { list ->
            for (i in 0 until list.length()) {
                val file = list.getJSONObject(i)
                files.put(
                    JSONObject().put("id", file.getLong("id")).put("format", file.opt("format") ?: JSONObject.NULL).put("role", file.optString("role", "primary"))
                        .put("sizeBytes", file.opt("sizeBytes") ?: JSONObject.NULL).put("filename", JSONObject.NULL),
                )
            }
        }
        val inCollections = JSONArray()
        for (c in collections.filter { id in it.books && (it.userId == readerId || it.isPublic) }) inCollections.put(JSONObject().put("id", c.id).put("name", c.name))
        val mine = card(book)
        answer.put("readStatus", mine.opt("readStatus") ?: JSONObject.NULL).put("rating", mine.opt("rating") ?: JSONObject.NULL)
            .put("personalNote", reviews[id] ?: JSONObject.NULL).put("genres", book.optJSONArray("genres") ?: JSONArray())
            .put("libraryId", 1).put("libraryName", if (book.has("libraryName")) book.getString("libraryName") else libraryNames.first())
            .put("files", files).put("collections", inCollections)
            .put("coverSource", if (book.optBoolean("hasCover")) "extracted" else JSONObject.NULL)
        return Answer(200, answer.toString())
    }

    val downloads = HashMap<Long, Answer>()

    /** Whether the reader may put books into libraries, the largest file taken, and the libraries there are. */
    var uploadAllowed = true
    var maxUploadBytes = 500L * 1024 * 1024
    var libraries = JSONArray("""[{"id":1,"name":"Books","allowedFormats":[],"organizationMode":"book_per_file","folders":[{"id":10,"name":"books"}]}]""")

    /** What opening an upload session is answered with instead, while not null. */
    var uploadAnswer: Answer? = null

    /** What finishing an upload session is answered with instead, while not null; the session fails with it. */
    var completeAnswer: Answer? = null

    /** The most of a file an upload session takes at a time. */
    var chunkBytes = 16L * 1024 * 1024

    /** How many of the next parts of files are taken and then not answered, as when the network goes. */
    var droppedChunks = 0

    /** While set, a finished file is filed away and the answer is not given, as when the app is closed meanwhile. */
    var droppedCompletes = false

    /** A file the server took. */
    class Upload(val libraryId: Long, val folderId: Long?, val name: String, val bytes: ByteArray, val bookId: Long)

    val uploads = ArrayList<Upload>()

    /** Done once a file is taken, before the answer goes, as to have the server fail right after. */
    var afterUpload: () -> Unit = {}

    /** An upload session, and what it got of its file so far. */
    class Session(val id: String, val key: String, val libraryId: Long, val folderId: Long?, val name: String, val size: Long) {
        val bytes = java.io.ByteArrayOutputStream()
        var status = "receiving"
        var bookId: Long? = null
        var parts = 0
    }

    val sessions = LinkedHashMap<String, Session>()

    private fun json(session: Session) = JSONObject().put("id", session.id).put("filename", session.name).put("sizeBytes", session.size)
        .put("receivedBytes", session.bytes.size()).put("chunkSizeBytes", chunkBytes).put("status", session.status)
        .put("bookId", session.bookId ?: JSONObject.NULL)
        .put("target", JSONObject().put("kind", "library").put("libraryId", session.libraryId).apply { session.folderId?.let { put("folderId", it) } })

    private fun openUpload(body: JSONObject): Answer {
        if (!uploadAllowed) return error(403, "Library upload permission is required")
        uploadAnswer?.let { return it }
        val key = body.getString("idempotencyKey")
        sessions.values.firstOrNull { it.key == key }?.let { return Answer(201, json(it).toString()) }
        val target = body.getJSONObject("target")
        val libraryId = target.getLong("libraryId")
        val folderId = if (target.has("folderId")) target.getLong("folderId") else null
        val library = (0 until libraries.length()).map { libraries.getJSONObject(it) }.firstOrNull { it.getLong("id") == libraryId }
            ?: return error(400, "Library not found", "UPLOAD_TARGET_INVALID")
        if (folderId != null) {
            val folders = library.getJSONArray("folders")
            if ((0 until folders.length()).none { folders.getJSONObject(it).getLong("id") == folderId }) {
                return error(400, "Folder does not belong to this library", "UPLOAD_TARGET_INVALID")
            }
        }
        val name = body.getString("filename")
        val size = body.getLong("sizeBytes")
        val format = name.substringAfterLast('.', "").lowercase()
        if (format !in FORMATS) return error(400, "Unsupported file type .$format", "UPLOAD_FORMAT_UNSUPPORTED")
        val allowed = library.getJSONArray("allowedFormats")
        if (allowed.length() > 0 && (0 until allowed.length()).none { allowed.getString(it) == format }) {
            return error(400, "This library does not allow .$format files", "UPLOAD_FORMAT_NOT_ALLOWED")
        }
        if (size > maxUploadBytes) return error(413, "File exceeds the upload limit", "UPLOAD_TOO_LARGE")
        val session = Session(java.util.UUID.randomUUID().toString(), key, libraryId, folderId, name, size)
        sessions[session.id] = session
        return Answer(201, json(session).toString())
    }

    private fun takePart(request: Seen, session: Session): Answer {
        if (session.status != "receiving") return error(409, "Upload session is ${session.status}", "UPLOAD_SESSION_STATE_INVALID")
        val offset = request.headers["upload-offset"]?.toLongOrNull() ?: return error(400, "Upload-Offset must be a non-negative integer")
        if (offset != session.bytes.size().toLong()) {
            return Answer(
                409,
                JSONObject().put("statusCode", 409).put("errorCode", "UPLOAD_OFFSET_MISMATCH")
                    .put("message", "Expected upload offset ${session.bytes.size()}, received $offset").put("expectedOffset", session.bytes.size()).toString(),
            )
        }
        val (_, bytes) = filePart(request) ?: return error(400, "No chunk provided")
        if (bytes.isEmpty()) return error(400, "File must not be empty", "UPLOAD_EMPTY")
        if (bytes.size > chunkBytes || offset + bytes.size > session.size) return error(413, "Chunk exceeds the remaining upload size", "UPLOAD_TOO_LARGE")
        session.bytes.write(bytes)
        session.parts++
        if (droppedChunks > 0) {
            droppedChunks--
            return Answer.Dropped
        }
        return Answer(200, json(session).toString())
    }

    private fun complete(session: Session): Answer {
        if (session.status == "processing" || session.status == "completed") {
            return if (droppedCompletes) Answer.Dropped else Answer(202, json(session).toString())
        }
        if (session.status != "receiving") return error(409, "Upload session is ${session.status}", "UPLOAD_SESSION_STATE_INVALID")
        if (session.bytes.size().toLong() != session.size) {
            return error(409, "Upload is incomplete: ${session.bytes.size()} of ${session.size} bytes received", "UPLOAD_SESSION_STATE_INVALID")
        }
        completeAnswer?.let {
            session.status = "failed"
            return it
        }
        // Filed by its name: the same name again is the same place.
        if (uploads.any { it.libraryId == session.libraryId && it.name == session.name }) {
            session.status = "failed"
            return error(409, "A file named \"${session.name}\" already exists at the target location", "UPLOAD_DESTINATION_CONFLICT")
        }
        val bytes = session.bytes.toByteArray()
        val bookId = marks.incrementAndGet().toLong()
        uploads += Upload(session.libraryId, session.folderId, session.name, bytes, bookId)
        files[partialMd5(bytes)] = bookId to bookId * 10
        session.status = "processing"
        session.bookId = bookId
        afterUpload()
        if (droppedCompletes) return Answer.Dropped
        return Answer(202, json(session).toString())
    }

    private fun uploads(request: Seen, path: String, body: JSONObject): Answer {
        if (path == "/uploads" && request.method == "POST") return openUpload(body)
        val id = path.removePrefix("/uploads/").substringBefore('/')
        val session = sessions[id] ?: return error(404, "Upload session not found")
        return when {
            request.method == "GET" -> Answer(200, json(session).toString())
            request.method == "DELETE" -> when (session.status) {
                "processing" -> error(409, "An upload cannot be cancelled after import processing begins", "UPLOAD_SESSION_STATE_INVALID")
                "completed" -> Answer(200, json(session).toString())
                else -> {
                    session.status = "cancelled"
                    Answer(200, json(session).toString())
                }
            }
            path.endsWith("/chunks") -> takePart(request, session)
            path.endsWith("/complete") -> complete(session)
            else -> error(404, "Cannot ${request.method} $path")
        }
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

    /** The reader's review by book, as the reader's session sets it; the server keeps none for nothing but white space. */
    val reviews = HashMap<Long, String>()

    /** What writing a note through the session is answered with instead, while not null. */
    var noteAnswer: Answer? = null

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


    /** The reader's number on the server, as signing in answers it; their collections are made under it. */
    val readerId = 1L

    /** A book of [books] by its id; null for one the server has not. */
    private fun bookJson(id: Long): JSONObject? =
        (0 until books.length()).map { books.getJSONObject(it) }.firstOrNull { it.optLong("id") == id }

    /**
     * A book as the reader's session answers it: with the status and rating the reader set since, through
     * either way in, in place of what [books] says.
     */
    private fun card(book: JSONObject): JSONObject {
        val id = book.optLong("id")
        val copy = JSONObject(book.toString())
        statuses[id]?.let { copy.put("readStatus", JSONObject().put("status", it).put("source", "manual")) }
        if (id in ratings) copy.put("rating", ratings[id] ?: JSONObject.NULL)
        return copy
    }

    private fun cards(found: JSONArray): JSONArray = JSONArray().apply { for (i in 0 until found.length()) put(card(found.getJSONObject(i))) }

    /** The ids of the books of [books] on each shelf of the home page, by the shelf's type, in the server's order. */
    val shelves = HashMap<String, List<Long>>()

    /** Shelves the batch says it could not fill. */
    val failedShelves = HashSet<String>()

    /** The shelves the server has; a version before one has not it at all. */
    var shelfTypes = setOf("recently-added", "continue-reading", "continue-listening", "want-to-read", "up-next-in-series", "random", "smart-scope")

    /** While false, there is no batch of shelves, as on a server before it: each shelf is asked for alone. */
    var shelfBatch = true

    /** What the batch of shelves, and one shelf, are answered with instead, while not null. */
    var shelvesAnswer: Answer? = null
    var shelfAnswer: Answer? = null

    private fun shelfBooks(type: String, limit: Int): JSONArray = JSONArray().apply {
        for (id in shelves[type].orEmpty().take(limit)) bookJson(id)?.let { put(card(it)) }
    }

    private fun shelvesBatch(body: JSONObject): Answer {
        if (!shelfBatch) return error(404, "Cannot POST /api/v1/dashboard/scrollers/batch")
        shelvesAnswer?.let { return it }
        val items = body.getJSONArray("items")
        if (items.length() !in 1..8) return error(400, "items must contain between 1 and 8 elements")
        val answered = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val type = item.getString("type")
            if (type !in shelfTypes) return error(400, "each value in items.type must be one of the following values")
            val limit = item.getInt("limit")
            if (limit !in 1..50) return error(400, "limit must not be greater than 50")
            val failed = type in failedShelves
            answered.put(JSONObject().put("id", item.getString("id")).put("books", if (failed) JSONArray() else shelfBooks(type, limit)).put("failed", failed))
        }
        return Answer(200, JSONObject().put("items", answered).toString())
    }

    private fun oneShelf(type: String, params: Map<String, String>): Answer {
        shelfAnswer?.let { return it }
        if (type !in shelfTypes) return error(400, "Validation failed (enum string is expected)")
        val limit = params["limit"]?.toIntOrNull() ?: 20
        // Two shelves count nothing, and the newest counts the month's only: none of it is the length of the answer.
        val total: Any = if (type == "random" || type == "up-next-in-series") JSONObject.NULL else shelves[type].orEmpty().size
        return Answer(200, JSONObject().put("books", shelfBooks(type, limit.coerceIn(1, 50))).put("total", total).toString())
    }

    /** Whether a collection's book is found by a phrase within the collection: by its title, as far as the stand-in goes. */
    private fun inPhrase(book: JSONObject, q: String) = q.isEmpty() || book.optString("title").contains(q, ignoreCase = true)

    /** What putting a book into a collection, or taking it out, is answered with instead, while not null. */
    var membershipAnswer: Answer? = null

    /** What a collection's books, queried or listed, are answered with instead, while not null. */
    var collectionBooksAnswer: Answer? = null

    /** A collection as the server keeps it. */
    class ServerCollection(
        val id: Long, var name: String, val userId: Long, var icon: String = "book", var description: String? = null,
        var isPublic: Boolean = false, var syncToKobo: Boolean = false, val books: MutableList<Long> = ArrayList(),
    )

    val collections = ArrayList<ServerCollection>()
    private val collectionIds = AtomicInteger(500)

    /** While false, a collection's books are not queried, as on a server before it: they are only listed. */
    var collectionQuery = true

    /** What reading the collections, and changing one, is answered with instead, while not null. */
    var collectionsAnswer: Answer? = null
    var collectionWriteAnswer: Answer? = null

    /** A collection made on the server, as in the browser: the reader's own unless another reader is named. */
    fun collection(name: String, vararg bookIds: Long, owner: Long = readerId, public: Boolean = false): ServerCollection =
        ServerCollection(collectionIds.incrementAndGet().toLong(), name, owner, isPublic = public, books = bookIds.toMutableList()).also { collections += it }

    private fun json(collection: ServerCollection, memberOf: ServerCollection.() -> Int? = { null }) = JSONObject()
        .put("id", collection.id).put("userId", collection.userId).put("name", collection.name).put("icon", collection.icon)
        .put("description", collection.description ?: JSONObject.NULL).put("isPublic", collection.isPublic)
        .put("isOwner", collection.userId == readerId).put("syncToKobo", collection.syncToKobo).put("displayOrder", 0)
        .put("bookCount", collection.books.count { bookJson(it) != null })
        .put("createdAt", "2031-01-01T00:00:00.000Z").put("updatedAt", "2031-01-01T00:00:00.000Z")
        .apply { collection.memberOf()?.let { put("memberCount", it) } }

    private fun collectionRoute(request: Seen, path: String, params: Map<String, String>, body: JSONObject): Answer {
        val parts = path.removePrefix("/collections").trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            if (request.method == "GET") {
                collectionsAnswer?.let { return it }
                val asked = params["bookIds"]?.split(',')?.mapNotNull { it.trim().toLongOrNull() }
                val list = JSONArray()
                if (asked == null) {
                    for (c in collections.filter { it.userId == readerId || it.isPublic }) list.put(json(c))
                } else {
                    // Asked with books: the reader's own collections, with how many of those books each has.
                    for (c in collections.filter { it.userId == readerId }) list.put(json(c) { asked.count { it in this.books } })
                }
                return Answer(200, list.toString())
            }
            collectionWriteAnswer?.let { return it }
            val name = body.optString("name").trim()
            if (name.isEmpty()) return error(400, "name should not be empty")
            if (body.optString("icon").trim().isEmpty()) return error(400, "Icon is required")
            if (collections.any { it.userId == readerId && it.name == name }) return error(409, "A collection with this name already exists")
            val made = ServerCollection(
                collectionIds.incrementAndGet().toLong(), name, readerId, body.getString("icon").trim(),
                if (body.has("description")) body.getString("description") else null,
                body.optBoolean("isPublic", false), body.optBoolean("syncToKobo", false),
            )
            collections += made
            return Answer(201, json(made).toString())
        }
        val id = parts[0].toLongOrNull() ?: return error(400, "Validation failed (numeric string is expected)")
        val collection = collections.firstOrNull { it.id == id } ?: return error(404, "Collection not found")
        val readable = collection.isPublic || collection.userId == readerId
        val writable = collection.userId == readerId
        when {
            parts.size == 1 && request.method == "GET" -> return if (readable) Answer(200, json(collection).toString()) else error(403, "No access to this collection")
            parts.size == 2 && parts[1] == "books" && request.method == "GET" -> {
                collectionBooksAnswer?.let { return it }
                if (!readable) return error(403, "No access to this collection")
                val size = params["size"]?.toIntOrNull() ?: 50
                val page = params["page"]?.toIntOrNull() ?: 0
                val q = params["q"].orEmpty().trim()
                val inIt = JSONArray().apply { collection.books.mapNotNull(::bookJson).filter { inPhrase(it, q) }.forEach { put(card(it)) } }
                val part = JSONArray()
                for (i in page * size until minOf(inIt.length(), page * size + size)) part.put(inIt.get(i))
                return Answer(200, JSONObject().put("items", part).put("total", inIt.length()).put("page", page).put("size", size).toString())
            }
            parts.size == 3 && parts[2] == "query" -> {
                if (!collectionQuery) return error(404, "Cannot POST /api/v1/collections/$id/books/query")
                collectionBooksAnswer?.let { return it }
                if (!readable) return error(403, "No access to this collection")
                val q = body.optString("q").trim()
                val inIt = JSONArray().apply { collection.books.mapNotNull(::bookJson).filter { inPhrase(it, q) }.forEach { put(it) } }
                val found = matching(body, inIt)
                return Answer(201, JSONObject().put("items", cards(page(body, found, always = true))).put("total", found.length()).put("page", body.getJSONObject("pagination").getInt("page")).toString())
            }
        }
        collectionWriteAnswer?.let { return it }
        if (!writable) return error(403, "Cannot modify this collection")
        return when {
            parts.size == 1 && request.method == "PATCH" -> {
                if (body.has("name")) {
                    val name = body.getString("name").trim()
                    if (name.isEmpty()) return error(400, "name should not be empty")
                    if (collections.any { it !== collection && it.userId == readerId && it.name == name }) return error(409, "A collection with this name already exists")
                    collection.name = name
                }
                if (body.has("icon")) collection.icon = body.getString("icon")
                if (body.has("description")) collection.description = body.getString("description")
                if (body.has("isPublic")) collection.isPublic = body.getBoolean("isPublic")
                if (body.has("syncToKobo")) collection.syncToKobo = body.getBoolean("syncToKobo")
                Answer(200, json(collection).toString())
            }
            parts.size == 1 && request.method == "DELETE" -> {
                collections.remove(collection)
                Answer(204)
            }
            parts.size == 2 && parts[1] == "books" -> {
                membershipAnswer?.let { return it }
                val ids = body.getJSONArray("bookIds").let { list -> (0 until list.length()).map(list::getLong) }
                if (request.method == "POST") ids.filter { it !in collection.books }.forEach(collection.books::add) else collection.books.removeAll(ids.toSet())
                Answer(if (request.method == "POST") 201 else 200, json(collection).toString())
            }
            else -> error(404, "Cannot ${request.method} /api/v1$path")
        }
    }

    /** Books like each book, by its id, in the server's order; the books of its author and series come from [books]. */
    val similar = HashMap<Long, List<Long>>()

    /** While false, the server lists nothing beside a book, as one before it does. */
    var relatedBooks = true

    /** What the books beside another are answered with instead, while not null. */
    var relatedAnswer: Answer? = null

    private fun related(id: Long, kind: String): Answer {
        if (!relatedBooks) return error(404, "Cannot GET /api/v1/books/$id/$kind")
        relatedAnswer?.let { return it }
        val book = bookJson(id) ?: return error(404, "Book $id not found")
        val all = (0 until books.length()).map { books.getJSONObject(it) }
        fun names(of: JSONObject) = of.optJSONArray("authors")?.let { list -> (0 until list.length()).map(list::getString) }.orEmpty()
        val chosen = when (kind) {
            "recommendations" -> similar[id].orEmpty().mapNotNull(::bookJson)
            "author-books" -> all.filter { it.optLong("id") != id && names(it).any(names(book)::contains) }
            else -> if (book.isNull("seriesName") || !book.has("seriesName")) emptyList() else all.filter { it.optString("seriesName") == book.optString("seriesName") }
        }
        val list = JSONArray()
        for (other in chosen) {
            val formats = other.optJSONArray("files")?.let { files -> (0 until files.length()).map { files.getJSONObject(it).optString("format") } }.orEmpty()
            val entry = JSONObject().put("id", other.getLong("id")).put("title", other.opt("title") ?: JSONObject.NULL).put("coverAspectRatio", "portrait")
                .put("updatedAt", JSONObject.NULL).put("hasCover", other.optBoolean("hasCover")).put("authors", JSONArray(names(other)))
                .put("readStatus", statuses[other.getLong("id")]?.let { JSONObject().put("status", it).put("source", "manual") } ?: JSONObject.NULL)
                .put("isAudiobook", formats.isNotEmpty() && formats.all { it in setOf("m4b", "mp3") })
                .put("isComic", formats.isNotEmpty() && formats.all { it in setOf("cbz", "cbr") })
            if (kind == "series-books") entry.put("seriesIndex", other.opt("seriesIndex") ?: JSONObject.NULL)
            list.put(entry)
        }
        return Answer(200, list.toString())
    }

    /** The statuses the server takes, as 3.3.0 has them. */
    var knownStatuses = setOf("unread", "want_to_read", "reading", "on_hold", "read", "abandoned")

    /** What setting a status, a rating and a note through the session is answered with instead, while not null. */
    var statusAnswer: Answer? = null
    var ratingAnswer: Answer? = null

    /** Whether the reader may set ratings through the session: the server wants them to edit the library's books for it. */
    var ratingThroughSession = true

    /** While set, the server keeps the books' ratings locked: it answers that it set them, and does not. */
    var ratingsLocked = false

    private fun setStatus(id: Long, body: JSONObject): Answer {
        statusAnswer?.let { return it }
        if (bookJson(id) == null) return error(404, "Book not found")
        val status = body.optString("status")
        if (status !in knownStatuses) return error(400, "status must be one of the following values: ${knownStatuses.joinToString()}")
        statuses[id] = status
        return Answer(
            200,
            JSONObject().put("status", status).put("source", "manual").put("startedAt", JSONObject.NULL).put("finishedAt", JSONObject.NULL)
                .put("updatedAt", "2031-01-01T00:00:00.000Z").toString(),
        )
    }

    private fun setRatings(body: JSONObject): Answer {
        ratingAnswer?.let { return it }
        if (!ratingThroughSession) return error(403, "Missing permission: library_edit_metadata")
        val ids = body.getJSONArray("bookIds").let { list -> (0 until list.length()).map(list::getLong) }
        if (ids.any { bookJson(it) == null }) return error(404, "Book not found")
        if (!ratingsLocked) for (id in ids) ratings[id] = if (body.isNull("rating")) null else body.getInt("rating")
        return Answer(204)
    }

    /** The libraries the reader sees, by name. */
    var libraryNames = listOf("Books")

    /** What the list of libraries is answered with instead, while not null. */
    var librariesAnswer: Answer? = null

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

    /** How long the server takes over a request to a path, in milliseconds: as one that answers late, after the reader moved on. */
    @Volatile
    var slow: (String) -> Long = { 0 }

    /** Requests to these paths, without the query, are taken and not answered until the test lets them go. */
    private val held = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

    /** Holds the answers to requests to [path], as `/books/7/status`, until [release]. */
    fun hold(path: String) {
        held[path] = java.util.concurrent.CountDownLatch(1)
    }

    fun release(path: String) {
        held.remove(path)?.countDown()
    }

    private fun answer(request: Seen): Answer {
        val path = request.path.removePrefix("/api/v1")
        held[path.substringBefore('?')]?.await(60, java.util.concurrent.TimeUnit.SECONDS)
        slow(path.substringBefore('?')).takeIf { it > 0 }?.let { Thread.sleep(it) }
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
            return Answer(200, JSONObject().put("passwordLoginEnabled", passwordLogin).put("oidcProviders", oidcProviders).toString())
        }
        if (path.startsWith("/auth/oidc/") && path.endsWith("/state")) {
            val state = "state-" + issued.incrementAndGet()
            oidcStates += state
            return Answer(200, JSONObject().put("state", state).put("authorizationEndpoint", authorizationEndpoint).toString())
        }
        if (path == "/auth/oidc/callback") {
            oidcCallbackAnswer?.let { return it }
            if (!oidcStates.remove(body.getString("state"))) {
                return Answer(401, """{"statusCode":401,"message":"Your login session expired. Please try signing in again.","errorCode":"oidc_state_expired"}""")
            }
            return Answer(200, tokens().put("mode", "login").put("user", JSONObject().put("id", 2).put("username", "sso-reader").put("name", "Reader Name")).toString())
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
            if (path == "/koreader/plugin/book-states") {
                val books = body.getJSONArray("books")
                val results = JSONArray()
                val unmatched = JSONArray()
                for (i in 0 until books.length()) {
                    val hash = books.getJSONObject(i).getString("hash")
                    val file = files[hash]
                    if (file == null) {
                        unmatched.put(hash)
                        continue
                    }
                    val review = reviews[file.first]
                    results.put(
                        JSONObject().put("hash", hash).put("statusApplied", false).put("ratingApplied", false).put("reviewApplied", false)
                            .put("rating", ratings[file.first] ?: JSONObject.NULL).put("ratingSet", ratings[file.first] != null)
                            .put("ratingUpdatedAt", JSONObject.NULL).put("reviewNote", review ?: JSONObject.NULL)
                            .put("reviewNoteSet", review != null).put("reviewUpdatedAt", if (review == null) JSONObject.NULL else "2031-01-01T00:00:00.000Z"),
                    )
                }
                return Answer(201, JSONObject().put("results", results).put("unmatched", unmatched).toString())
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
        val route = path.substringBefore('?')
        val params = path.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        return when {
            path == "/app-info" -> Answer(200, JSONObject().put("version", version).put("features", JSONArray()).toString())
            route == "/dashboard/scrollers/batch" && request.method == "POST" -> shelvesBatch(body)
            route.startsWith("/dashboard/scrollers/") && request.method == "GET" -> oneShelf(route.removePrefix("/dashboard/scrollers/"), params)
            route == "/collections" || route.startsWith("/collections/") -> collectionRoute(request, route, params, body)
            route == "/libraries" && request.method == "GET" -> librariesAnswer ?: Answer(200, JSONArray().apply { libraryNames.forEachIndexed { i, name -> put(JSONObject().put("id", i + 1).put("name", name)) } }.toString())
            Regex("/books/\\d+/(recommendations|author-books|series-books)").matches(route) && request.method == "GET" ->
                related(route.removePrefix("/books/").substringBefore('/').toLong(), route.substringAfterLast('/'))
            Regex("/books/\\d+/status").matches(route) && request.method == "PATCH" -> setStatus(route.removePrefix("/books/").substringBefore('/').toLong(), body)
            route == "/books/bulk-set-rating" && request.method == "POST" -> setRatings(body)
            path == "/uploads/capabilities" -> Answer(
                200,
                JSONObject().put("maxFileSizeBytes", maxUploadBytes).put("chunkSizeBytes", chunkBytes)
                    .put("supportedFormats", JSONArray(FORMATS)).put("canUploadToLibrary", uploadAllowed).put("canUseBookDock", false)
                    .put("libraries", if (uploadAllowed) libraries else JSONArray()).toString(),
            )
            path == "/uploads" || path.startsWith("/uploads/") -> uploads(request, path, body)
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
            Regex("/books/\\d+/personal-note").matches(path) && request.method == "PATCH" -> {
                val id = path.removePrefix("/books/").substringBefore('/').toLong()
                if (hashOf(id) == null && bookJson(id) == null) return error(404, "Book not found")
                noteAnswer?.let { return it }
                val note = if (body.isNull("note")) "" else body.getString("note").trim()
                if (note.isEmpty()) reviews.remove(id) else reviews[id] = note
                Answer(200, JSONObject().put("id", id).put("personalNote", reviews[id] ?: JSONObject.NULL).toString())
            }
            Regex("/books/\\d+").matches(path) && request.method == "GET" -> detailAnswer ?: detail(path.removePrefix("/books/").toLong())
            path == "/books/query" -> queryAnswer ?: refusedQuery(body) ?: matching(body).let { found ->
                Answer(201, JSONObject().put("items", cards(page(body, found))).put("total", total ?: found.length()).put("page", body.getJSONObject("pagination").getInt("page")).toString())
            }
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
