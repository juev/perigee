package org.evsyukov.perigee.sync

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.data.Account
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.ReadingStatus
import org.evsyukov.perigee.data.RemotePosition
import org.evsyukov.perigee.data.Tokens
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A bookOrbit server the reader signed in to.
 *
 * The server is talked to in two ways. Signing in, its list of books, covers
 * and files go through the API its own web pages use, with tokens that are
 * renewed as they run out. What is exchanged about reading goes through the
 * channel it keeps for reading devices, which has a user name and key of its
 * own; the app sets that up when the reader signs in.
 *
 * @param clientVersion what the app calls its version to the server
 * @param deviceName what the reader's other devices are told this one is, as when a place read here is offered there
 */
class BookOrbit(
    private val accounts: Accounts,
    private val library: Library,
    private val clientVersion: String,
    private val deviceName: String = DEVICE_MODEL,
    private val now: () -> Long = System::currentTimeMillis,
) : SyncBackend {
    /** One renewal at a time: a token to renew with is good for one use. */
    private val renewing = Mutex()

    /** One look at the devices' channel at a time: two at once would each make a user name, or drop the other's key. */
    private val settingUp = Mutex()

    /** One round of syncing at a time. */
    private val syncing = Mutex()

    /**
     * Signs in with a user name and password, in place of whoever was signed
     * in before, and sets up the devices' channel as far as it can without the
     * reader. An address without "http" in front is taken as https.
     *
     * @throws IllegalArgumentException when [address] is not one a server can be at
     * @throws NotAServerException when there is no bookOrbit server there
     * @throws PasswordLoginDisabledException before the password is sent anywhere
     * @throws WrongPasswordException
     * @throws TooManyAttemptsException
     * @throws IOException when the server cannot be reached
     */
    suspend fun signIn(address: String, user: String, password: String): DeviceChannel {
        val url = serverAddress(address)
        val http = Http(url)
        val (tokens, name, version) = io {
            val options = try {
                http.call("GET", "/auth/login-options").takeIf { it.ok }?.json()
            } catch (e: IOException) {
                // Something answers there, in a language of its own.
                if (e.cause is JSONException) null else throw e
            }
            if (options == null || !options.has("passwordLoginEnabled")) throw NotAServerException(url)
            if (!options.optBoolean("passwordLoginEnabled")) throw PasswordLoginDisabledException()

            val login = http.call(
                "POST", "/auth/login",
                JSONObject().put("username", user).put("password", password).put("clientKind", "native").put("deviceLabel", DEVICE_MODEL),
            )
            when {
                login.ok -> Unit
                login.errorCode == "password_auth_disabled" -> throw PasswordLoginDisabledException()
                login.code == 401 && login.errorCode == "account_locked" -> throw TooManyAttemptsException(login.retryAfter)
                login.code == 401 || login.code == 400 -> throw WrongPasswordException()
                else -> throw failure(login)
            }
            val session = login.json() ?: throw NotAServerException(url)
            val tokens = tokensOf(session) ?: throw NotAServerException(url)
            val info = http.call("GET", "/app-info", headers = bearer(tokens.access))
            Triple(tokens, session.optJSONObject("user")?.optString("name").orEmpty(), info.json()?.optString("version").orEmpty())
        }
        accounts.signIn(url, user, name.ifBlank { user }, tokens, version)
        return deviceChannel()
    }

    /** Ends the session on the server, if it can be reached, and forgets the server either way. */
    suspend fun signOut() {
        val account = accounts.current() ?: return
        val refresh = accounts.tokens()?.refresh.orEmpty()
        if (refresh.isNotEmpty()) {
            io { runCatching { Http(account.url).call("POST", "/auth/logout", JSONObject().put("refreshToken", refresh)) } }
        }
        accounts.signOut()
    }

    /**
     * Asks the server its version anew and remembers it.
     * @return whether the app was made for a server of that version
     */
    suspend fun checkServer(): Boolean {
        val version = jwt("GET", "/app-info").also { if (!it.ok) throw failure(it) }.json()?.optString("version").orEmpty()
        accounts.saveServerVersion(version)
        return supports(version)
    }

    /**
     * Makes the devices' channel ready when that takes nothing from the reader:
     * a reader who has no user name there is given one. Says how things stand.
     */
    suspend fun deviceChannel(): DeviceChannel = settingUp.withLock { setUpDevice() }

    private suspend fun setUpDevice(): DeviceChannel {
        val account = accounts.current() ?: throw SignedOutException()
        if (!supports(account.serverVersion)) return DeviceChannel.Unsupported
        val key = accounts.deviceKey()
        if (key != null) {
            return when (val state = probe(account, account.deviceUser, key)) {
                DeviceChannel.PasswordNeeded -> state.also { accounts.saveDevice(account.deviceUser, "") }
                else -> state
            }
        }
        val existing = jwt("GET", "/koreader/credentials")
        if (existing.code == 403) return DeviceChannel.NotAllowed
        if (!existing.ok) throw failure(existing)
        val named = existing.json()?.optString("username").orEmpty()
        if (named.isNotEmpty()) {
            // The password was the reader's to choose, and the server does not give it back.
            accounts.saveDevice(named, "")
            return DeviceChannel.PasswordNeeded
        }
        val password = randomHex(16)
        // Names are the server's, not the reader's alone: the plain one may be taken.
        for (name in listOf(deviceName(account.user, ""), deviceName(account.user, "-" + randomHex(3)))) {
            val created = jwt("POST", "/koreader/credentials", JSONObject().put("username", name).put("password", password))
            when {
                created.ok -> {
                    accounts.saveDevice(name, md5(password))
                    return DeviceChannel.Ready
                }
                created.code == 403 -> return DeviceChannel.NotAllowed
                created.code == 409 && "taken" in created.message -> continue
                // Made meanwhile from elsewhere, as by the reader in the browser.
                created.code == 409 -> return setUpDevice()
                else -> throw failure(created)
            }
        }
        throw ServerResponseException(409, "no user name is free for the devices' channel")
    }

    /**
     * The reader gave the password of the user name they have in the devices' channel.
     * @throws WrongPasswordException when the server does not take it
     */
    suspend fun devicePassword(password: String): DeviceChannel = settingUp.withLock {
        val account = accounts.current() ?: throw SignedOutException()
        if (account.deviceUser.isEmpty()) return setUpDevice()
        val key = md5(password)
        val state = probe(account, account.deviceUser, key)
        if (state == DeviceChannel.PasswordNeeded) throw WrongPasswordException()
        accounts.saveDevice(account.deviceUser, key)
        state
    }

    private suspend fun probe(account: Account, user: String, key: String): DeviceChannel {
        val reply = io { Http(account.url).call("GET", "/koreader/users/auth", headers = deviceHeaders(user, key)) }
        return when (reply.code) {
            200 -> DeviceChannel.Ready
            401 -> if ("permission" in reply.message) DeviceChannel.NotAllowed else DeviceChannel.PasswordNeeded
            403 -> DeviceChannel.SwitchedOff
            else -> throw failure(reply)
        }
    }

    override suspend fun books(query: String, page: Int): RemotePage {
        val body = JSONObject()
            .put("pagination", JSONObject().put("page", page).put("size", PAGE_SIZE))
            .put("sort", JSONArray().put(JSONObject().put("field", "title").put("dir", "asc")))
        if (query.isNotBlank()) body.put("q", query.trim().take(MAX_QUERY))
        val reply = jwt("POST", "/books/query", body)
        if (!reply.ok) throw failure(reply)
        val json = reply.json() ?: throw ServerResponseException(reply.code, "no list of books")
        val items = json.optJSONArray("items") ?: JSONArray()
        return RemotePage((0 until items.length()).mapNotNull { bookOf(items.optJSONObject(it)) }, json.optInt("total"), json.optInt("page", page))
    }

    private fun bookOf(json: JSONObject?): RemoteBook? {
        val id = json?.optLong("id") ?: return null
        val files = json.optJSONArray("files") ?: JSONArray()
        return RemoteBook(
            id = id,
            title = json.textOf("title"),
            authors = json.optJSONArray("authors")?.let { names -> (0 until names.length()).map { names.optString(it) } }.orEmpty()
                .filter { it.isNotBlank() },
            series = json.textOf("seriesName"),
            seriesNumber = json.textOf("seriesIndex"),
            files = (0 until files.length()).mapNotNull { i ->
                val file = files.optJSONObject(i) ?: return@mapNotNull null
                RemoteFile(id, file.optLong("id"), file.textOf("format").lowercase(), file.optLong("sizeBytes"))
            },
            hasCover = json.optBoolean("hasCover"),
        )
    }

    override suspend fun cover(book: RemoteBook): ByteArray? {
        if (!book.hasCover) return null
        return try {
            fetch("/books/${book.id}/thumbnail") { connection ->
                connection.inputStream.use { input ->
                    val bytes = input.readNBytesUpTo(MAX_COVER + 1)
                    bytes.takeIf { it.size <= MAX_COVER }
                }
            }
        } catch (e: ServerResponseException) {
            null
        }
    }

    override suspend fun download(file: RemoteFile, title: String, dir: File): File =
        fetch("/books/files/${file.id}/download") { connection ->
            val ending = if (file.format.isEmpty()) "" else ".${file.format}"
            val name = fileName(connection.getHeaderField("Content-Disposition"))?.takeIf { it.endsWith(ending, ignoreCase = true) }
                ?: (safeName(title).ifEmpty { "book" } + ending)
            dir.mkdirs()
            val target = File(dir, name)
            val partial = File(dir, "$name.part")
            try {
                connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
                // Only a whole file takes the name a book is known by.
                if (!partial.renameTo(target)) throw IOException("cannot keep $name")
            } finally {
                partial.delete()
            }
            target
        }

    override suspend fun match(hashes: Collection<String>): Map<String, RemoteFile> {
        val account = accounts.current() ?: throw SignedOutException()
        val found = HashMap<String, RemoteFile>()
        for (part in hashes.map { it.lowercase() }.distinct().chunked(MATCH_BATCH)) {
            val body = JSONObject()
                .put("deviceId", account.deviceId).put("deviceModel", DEVICE_MODEL).put("pluginVersion", clientVersion)
                .put("hashes", JSONArray(part))
            val matches = device("POST", "/koreader/plugin/match-check", body).json()?.optJSONArray("matches") ?: continue
            for (i in 0 until matches.length()) {
                val match = matches.optJSONObject(i) ?: continue
                found[match.optString("hash").lowercase()] = RemoteFile(match.optLong("bookId"), match.optLong("bookFileId"), "", 0)
            }
        }
        return found
    }

    /**
     * Asks the server which books of the library it has, and remembers the
     * answer with each. With [all], about every book it is not known to have;
     * without, only about those it was never asked about.
     * @return how many books were found on the server
     */
    suspend fun matchLibrary(all: Boolean): Int {
        val asked = library.unmatched(all)
        if (asked.isEmpty()) return 0
        // A book is known by the fingerprint the server matches files by.
        val found = match(asked)
        for ((hash, file) in found) library.onServer(hash, file.bookId, file.id)
        library.notOnServer(asked.filter { it.lowercase() !in found })
        return found.size
    }

    /**
     * One round of keeping the server and the library in step: the places the
     * reader got to, status and rating, and the pages read. Nothing is done
     * for a server of a version the app was not made for.
     *
     * A place the reader got to elsewhere, later than here, is not gone to: it
     * is kept as an offer for the reader to decide about, and until they do
     * the place here is not sent over it.
     *
     * @throws IncompatibleServerException
     * @throws DeviceChannelException when the devices' channel is not ready
     * @throws SignedOutException
     * @throws IOException when the server cannot be reached; what was not sent is sent the next time
     */
    suspend fun sync(): SyncReport = syncing.withLock {
        matchLibrary(all = false)
        val books = library.booksOnServer()
        var report = SyncReport()
        for (book in books) {
            if (book.status != ReadingStatus.Reading && !book.placeUnsent && library.pendingOffer(book.id) == null) continue
            report += syncPlaceOf(book)
        }
        report += syncStates(books)
        report + syncPagesRead()
    }

    /**
     * Brings one book's place in step, as when it is opened: there may be a
     * later one from elsewhere to offer. Says nothing of a book the server has not.
     */
    suspend fun syncPlace(bookId: String): SyncReport = syncing.withLock {
        val book = library.book(bookId)?.takeIf { it.serverFileId != null } ?: return SyncReport()
        syncPlaceOf(book)
    }

    private suspend fun syncPlaceOf(book: Book): SyncReport {
        val account = accounts.current() ?: throw SignedOutException()
        val remote = device("GET", "/koreader/syncs/progress/${book.id}").json()?.takeIf { it.has("timestamp") }
        if (remote != null && remote.optString("device_id") != account.deviceId) {
            val at = remote.optLong("timestamp")
            // Later than anything the reader did here or was told of before.
            if (at > book.remoteSeenAt && at * 1000 > book.movedAt) {
                val progress = remote.optDouble("percentage", 0.0).toFloat().coerceIn(0f, 1f)
                if (abs(progress - book.progress) < SAME_PLACE) {
                    library.remoteSeen(book.id, at)
                } else {
                    library.offer(RemotePosition(book.id, remote.textOf("progress").ifEmpty { null }, progress, remote.textOf("device"), at))
                    return SyncReport(offers = 1)
                }
            }
        }
        // The reader has not decided about a place from elsewhere: theirs here is not sent over it.
        if (library.pendingOffer(book.id) != null) return SyncReport()
        val xpointer = book.xpointer
        if (!book.placeUnsent || xpointer == null) return SyncReport()
        val body = JSONObject()
            .put("document", book.id).put("percentage", book.progress.toDouble().coerceIn(0.0, 1.0)).put("progress", xpointer)
            .put("device", deviceName).put("device_id", account.deviceId).put("timestamp", book.movedAt / 1000)
        try {
            device("PUT", "/koreader/syncs/progress", body)
        } catch (e: ServerResponseException) {
            if (e.code != 404) throw e
            // The server has the file no more.
            library.notOnServer(listOf(book.id))
            return SyncReport()
        }
        library.placeSent(book.id, book.movedAt)
        return SyncReport(placesSent = 1)
    }

    /** Status and rating, both ways: what the reader changed here goes up, the rest is taken as the server has it. */
    private suspend fun syncStates(books: List<Book>): SyncReport {
        if (books.isEmpty()) return SyncReport()
        val wanted = books.mapNotNull { it.serverBookId }.toSet()
        val theirs = HashMap<Long, Pair<String, Int?>>()
        var page = 0
        var seen = 0
        while (theirs.size < wanted.size) {
            val reply = jwt("POST", "/books/query", JSONObject().put("pagination", JSONObject().put("page", page).put("size", STATES_PAGE)))
            if (!reply.ok) throw failure(reply)
            val json = reply.json() ?: break
            val items = json.optJSONArray("items") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optLong("id")
                if (id !in wanted) continue
                val rating = if (item.isNull("rating")) null else item.optDouble("rating").roundToInt().takeIf { it in 1..5 }
                theirs[id] = (item.optJSONObject("readStatus")?.textOf("status") ?: "unread") to rating
            }
            seen += items.length()
            if (seen >= json.optInt("total")) break
            page++
        }
        var sent = 0
        var taken = 0
        for (book in books) {
            val (status, rating) = theirs[book.serverBookId] ?: continue
            val known = STATUSES[status]
            if (!book.stateUnsent) {
                // A status the app has no word for, as "on hold", leaves the reader's own as it is.
                val next = known ?: book.status
                if (next != book.status || rating != book.rating) {
                    library.takeState(book.id, next, rating)
                    taken++
                }
                continue
            }
            // Only what the reader changed is sent, so that stars given here do not undo a status set there.
            val mine = STATUSES.entries.first { it.value == book.status }.key
            val base = "/koreader/plugin/catalog/books/${book.serverBookId}"
            if (book.statusUnsent && known != book.status) device("PUT", "$base/read-status", JSONObject().put("status", mine))
            if (rating != book.rating) device("PUT", "$base/rating", JSONObject().put("rating", book.rating ?: JSONObject.NULL))
            library.stateSent(book.id, book.stateChangedAt)
            sent++
        }
        return SyncReport(statesSent = sent, statesTaken = taken)
    }

    /** The pages read go up in parts the server takes; it tells apart what it was sent twice. */
    private suspend fun syncPagesRead(): SyncReport {
        val account = accounts.current() ?: throw SignedOutException()
        library.forgetPageReadsBefore(now() / 1000 - PAGE_READS_KEPT_SECONDS)
        var sent = 0
        while (true) {
            val reads = library.pageReadsToSend(PAGE_READS_BATCH)
            val part = reads.groupBy { it.bookId }.entries.take(PAGE_READS_BOOKS)
            if (part.isEmpty()) break
            val books = JSONArray()
            for ((bookId, events) in part) {
                val list = JSONArray()
                for (read in events) {
                    list.put(
                        JSONObject().put("page", read.page.coerceAtLeast(0)).put("startTime", read.startedAt.coerceAtLeast(1))
                            .put("durationSeconds", read.seconds.coerceIn(0, MAX_PAGE_SECONDS)).put("totalPages", read.totalPages.coerceAtLeast(1)),
                    )
                }
                books.put(JSONObject().put("hash", bookId).put("events", list))
            }
            val body = JSONObject()
                .put("deviceId", account.deviceId).put("deviceModel", DEVICE_MODEL).put("pluginVersion", clientVersion).put("books", books)
            device("POST", "/koreader/plugin/page-stats", body)
            val told = part.flatMap { it.value }
            library.pageReadsSent(told)
            sent += told.size
        }
        return SyncReport(pagesSent = sent)
    }

    /** A request with the session's token, renewed first when it has run out or is not taken. */
    private suspend fun jwt(method: String, path: String, body: JSONObject? = null): Reply {
        var token = access(stale = null)
        repeat(2) { attempt ->
            val reply = io { Http(token.first).call(method, path, body, bearer(token.second)) }
            if (reply.code != 401) return reply
            if (attempt == 0) token = access(stale = token.second)
        }
        throw signedOut()
    }

    private suspend fun <T> fetch(path: String, read: (HttpURLConnection) -> T): T {
        var token = access(stale = null)
        repeat(2) { attempt ->
            val result = io {
                Http(token.first).open("GET", path, bearer(token.second)) { connection ->
                    when (val code = connection.responseCode) {
                        in 200..299 -> Result.success(read(connection))
                        401 -> null
                        else -> throw failure(Reply(code, connection.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()))
                    }
                }
            }
            if (result != null) return result.getOrThrow()
            if (attempt == 0) token = access(stale = token.second)
        }
        throw signedOut()
    }

    /**
     * The server's address and a token to ask it with. The token is renewed
     * when it is about to run out, or is the one the server just refused.
     */
    private suspend fun access(stale: String?): Pair<String, String> = renewing.withLock {
        val account = accounts.current() ?: throw SignedOutException()
        val tokens = accounts.tokens()?.takeIf { it.refresh.isNotEmpty() } ?: throw SignedOutException()
        if (tokens.access != stale && tokens.accessExpiresAt - now() > EARLY_MS) return account.url to tokens.access
        val reply = io { Http(account.url).call("POST", "/auth/refresh", JSONObject().put("refreshToken", tokens.refresh)) }
        val renewed = when {
            reply.ok -> reply.json()?.let(::tokensOf) ?: throw ServerResponseException(reply.code, "no tokens")
            reply.code == 401 || reply.code == 400 -> throw signedOut()
            else -> throw failure(reply)
        }
        accounts.saveTokens(renewed)
        account.url to renewed.access
    }

    /** The session is beyond renewing: its tokens are dropped, the account stays for the reader to sign in to again. */
    private suspend fun signedOut(): SignedOutException {
        accounts.saveTokens(Tokens("", 0, "", 0))
        return SignedOutException()
    }

    /** A request through the devices' channel. */
    private suspend fun device(method: String, path: String, body: JSONObject? = null): Reply {
        val account = accounts.current() ?: throw SignedOutException()
        if (!supports(account.serverVersion)) throw IncompatibleServerException(account.serverVersion)
        val key = accounts.deviceKey() ?: throw DeviceChannelException(DeviceChannel.PasswordNeeded)
        val reply = io { Http(account.url).call(method, path, body, deviceHeaders(account.deviceUser, key)) }
        when {
            reply.ok -> return reply
            reply.code == 401 && "permission" in reply.message -> throw DeviceChannelException(DeviceChannel.NotAllowed)
            reply.code == 401 -> {
                // The password was changed elsewhere; the reader has to give the new one.
                accounts.saveDevice(account.deviceUser, "")
                throw DeviceChannelException(DeviceChannel.PasswordNeeded)
            }
            reply.code == 403 -> throw DeviceChannelException(DeviceChannel.SwitchedOff)
            else -> throw failure(reply)
        }
    }

    private fun failure(reply: Reply): IOException = when (reply.code) {
        403 -> NotAllowedException(reply.message)
        429 -> TooManyAttemptsException(reply.retryAfter)
        else -> ServerResponseException(reply.code, reply.message)
    }

    private fun tokensOf(json: JSONObject): Tokens? {
        val access = json.optString("accessToken")
        val refresh = json.optString("refreshToken")
        if (access.isEmpty() || refresh.isEmpty()) return null
        return Tokens(access, timeOf(json.optString("accessTokenExpiresAt")), refresh, timeOf(json.optString("refreshTokenExpiresAt")))
    }

    /** A time the server names; one it names in no way known is taken as past, so that it is asked anew. */
    private fun timeOf(text: String): Long = try {
        Instant.parse(text).toEpochMilli()
    } catch (e: DateTimeParseException) {
        0
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** What the app calls itself to the server. */
        const val DEVICE_MODEL = "Perigee"
        private const val PAGE_SIZE = 50
        private const val MAX_QUERY = 200
        private const val MATCH_BATCH = 500
        private const val MAX_COVER = 4 * 1024 * 1024

        private const val STATES_PAGE = 200
        private const val PAGE_READS_BATCH = 500
        private const val PAGE_READS_BOOKS = 50
        private const val MAX_PAGE_SECONDS = 86_400
        private const val PAGE_READS_KEPT_SECONDS = 90L * 24 * 3600

        /** Two places this close, as shares of the book, are the same place. */
        private const val SAME_PLACE = 0.001f

        /** The server's statuses the app has a word for. */
        private val STATUSES = mapOf(
            "unread" to ReadingStatus.None, "want_to_read" to ReadingStatus.ToRead,
            "reading" to ReadingStatus.Reading, "read" to ReadingStatus.Read,
        )

        /** A token this close to running out is renewed before it is used. */
        private const val EARLY_MS = 30_000L

        /**
         * Whether the app was made for a server of this version: 3.3 and later
         * of the third. A server that does not name its version in numbers is
         * not one it was made for.
         */
        fun supports(version: String): Boolean {
            val parts = Regex("^v?(\\d+)\\.(\\d+)").find(version.trim())?.groupValues ?: return false
            val major = parts[1].toIntOrNull() ?: return false
            val minor = parts[2].toIntOrNull() ?: return false
            return major == 3 && minor >= 3
        }

        /**
         * The address a server is reached at, from what the reader typed: with
         * https when no scheme is given, without the path of the API or a
         * trailing slash.
         * @throws IllegalArgumentException when it is not an http or https address
         */
        fun serverAddress(typed: String): String {
            val text = typed.trim().let { if ("://" in it) it else "https://$it" }
            val uri = try {
                URI(text)
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("not an address: $typed", e)
            }
            val scheme = uri.scheme?.lowercase()
            require(scheme == "http" || scheme == "https") { "not a web address: $typed" }
            val host = requireNotNull(uri.host?.takeIf { it.isNotEmpty() }) { "no server named in: $typed" }
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = uri.rawPath.orEmpty().trimEnd('/').removeSuffix("/api/v1").trimEnd('/')
            return "$scheme://${host.lowercase()}$port$path"
        }

        /** A user name for the devices' channel, which wants three characters at least. */
        private fun deviceName(user: String, ending: String): String {
            val base = user.filter { it.isLetterOrDigit() || it in "._-" }.take(60)
            return (if (base.length < 3) "perigee-$base" else base) + ending
        }

        private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")

        private fun deviceHeaders(user: String, key: String) = mapOf("x-auth-user" to user, "x-auth-key" to key)

        /** The key of the devices' channel is the MD5 of its password, as the devices it was made for send it. */
        private fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun randomHex(bytes: Int): String =
            ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

        private fun JSONObject.textOf(name: String): String = if (isNull(name)) "" else optString(name)

        /** Reads at most [limit] bytes: a cover is not worth more memory than that. */
        private fun java.io.InputStream.readNBytesUpTo(limit: Int): ByteArray {
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (bytes.size() < limit) {
                val read = read(buffer, 0, minOf(buffer.size, limit - bytes.size()))
                if (read < 0) break
                bytes.write(buffer, 0, read)
            }
            return bytes.toByteArray()
        }

        /** The file name of a Content-Disposition header, without any folders it may name. */
        internal fun fileName(disposition: String?): String? {
            if (disposition == null) return null
            val encoded = Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)").find(disposition)?.groupValues?.get(1)
            val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?").find(disposition)?.groupValues?.get(1)
            val name = encoded?.let { runCatching { URLDecoder.decode(it.replace("+", URLEncoder.encode("+", "UTF-8")), "UTF-8") }.getOrNull() }
                ?: plain ?: return null
            return safeName(name).ifEmpty { null }
        }

        /** A name that stays inside the folder it is put in, whatever the server or a title says. */
        internal fun safeName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.').take(120).trim()
    }
}
