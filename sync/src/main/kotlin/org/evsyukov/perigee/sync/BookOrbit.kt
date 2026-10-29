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
import java.util.Base64
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.data.Account
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.Bookmark
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.ReadingStatus
import org.evsyukov.perigee.data.RemotePosition
import org.evsyukov.perigee.data.Stamps
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

    /** One book sent at a time: the second asking for the same book finds it there. */
    private val sending = Mutex()

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
        val session = io {
            val options = loginOptions(http, url)
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
            sessionOf(http, url, login, user)
        }
        return signedIn(url, session)
    }

    /**
     * How the server at [address] lets readers in: by password or not, and
     * through which providers.
     *
     * @throws IllegalArgumentException when [address] is not one a server can be at
     * @throws NotAServerException when there is no bookOrbit server there
     * @throws IOException when the server cannot be reached
     */
    suspend fun signInOptions(address: String): SignInOptions {
        val url = serverAddress(address)
        val options = io { loginOptions(Http(url), url) }
        val providers = buildList {
            options.optJSONArray("oidcProviders").each { provider ->
                val slug = provider.optString("slug")
                if (slug.isNotEmpty() && provider.optBoolean("enabled", true)) {
                    add(SignInProvider(slug, provider.optString("displayName").ifBlank { slug }, provider.optString("clientId"), provider.optString("scopes")))
                }
            }
        }
        return SignInOptions(options.optBoolean("passwordLoginEnabled"), providers)
    }

    /**
     * Starts signing in through [provider] and returns the address of the
     * provider's page the reader signs in on. The provider sends the reader
     * back to [REDIRECT_URI], which goes to [finishSignIn]. What the way in
     * needs to be finished is kept in memory only: the server waits for it
     * five minutes, and an app closed meanwhile starts again.
     *
     * @throws SignInRefusedException when the server cannot reach the provider
     * @throws IOException when the server cannot be reached
     */
    suspend fun startSignIn(address: String, provider: SignInProvider): String {
        val url = serverAddress(address)
        val reply = io { Http(url).call("POST", "/auth/oidc/${URLEncoder.encode(provider.slug, "UTF-8")}/state") }
        if (!reply.ok) throw failure(reply)
        val json = reply.json() ?: throw NotAServerException(url)
        val state = json.optString("state").ifEmpty { throw NotAServerException(url) }
        val endpoint = json.optString("authorizationEndpoint").ifEmpty { throw SignInRefusedException("the server cannot reach ${provider.name}") }
        val verifier = randomBase64(32)
        val nonce = randomBase64(16)
        pending = PendingSignIn(url, state, verifier, nonce)
        val challenge = base64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val query = listOf(
            "response_type" to "code", "client_id" to provider.clientId, "redirect_uri" to REDIRECT_URI, "scope" to provider.scopes,
            "code_challenge" to challenge, "code_challenge_method" to "S256", "state" to state, "nonce" to nonce,
        ).joinToString("&") { (name, value) -> name + "=" + URLEncoder.encode(value, "UTF-8") }
        return endpoint + (if ('?' in endpoint) "&" else "?") + query
    }

    /**
     * Finishes signing in with the address the provider sent the reader back
     * to, in place of whoever was signed in before, and sets up the devices'
     * channel as [signIn] does.
     *
     * @throws SignInExpiredException when no way in waits for this answer, or the server stopped waiting for it
     * @throws SignInCancelledException when the reader turned back at the provider
     * @throws SignInRefusedException when the provider or the server did not let the reader in
     * @throws IOException when the server cannot be reached
     */
    suspend fun finishSignIn(redirect: String): DeviceChannel {
        val answer = queryOf(redirect)
        val waiting = pending
        // An answer to a way in the app did not start, or started anew since, is not taken.
        if (waiting == null || answer["state"] != waiting.state) throw SignInExpiredException()
        pending = null
        answer["error"]?.let { error ->
            if (error == "access_denied") throw SignInCancelledException()
            throw SignInRefusedException(answer["error_description"] ?: error)
        }
        val code = answer["code"].orEmpty().ifEmpty { throw SignInRefusedException("the provider sent no code") }
        val http = Http(waiting.url)
        val session = io {
            val reply = http.call(
                "POST", "/auth/oidc/callback",
                JSONObject().put("code", code).put("codeVerifier", waiting.verifier).put("redirectUri", REDIRECT_URI)
                    .put("nonce", waiting.nonce).put("state", waiting.state).put("clientKind", "native").put("deviceLabel", DEVICE_MODEL),
            )
            when {
                reply.ok -> Unit
                reply.errorCode == "oidc_state_expired" -> throw SignInExpiredException()
                reply.code == 400 || reply.code == 401 || reply.code == 403 -> throw SignInRefusedException(reply.message)
                else -> throw failure(reply)
            }
            sessionOf(http, waiting.url, reply, user = "")
        }
        return signedIn(waiting.url, session)
    }

    /** A way in through a provider that waits for the provider's answer. */
    private class PendingSignIn(val url: String, val state: String, val verifier: String, val nonce: String)

    @Volatile
    private var pending: PendingSignIn? = null

    private class Session(val tokens: Tokens, val user: String, val name: String, val version: String)

    /** What the server says of how it lets readers in; asked first, so that nothing is sent where there is no server. */
    private fun loginOptions(http: Http, url: String): JSONObject {
        val options = try {
            http.call("GET", "/auth/login-options").takeIf { it.ok }?.json()
        } catch (e: IOException) {
            // Something answers there, in a language of its own.
            if (e.cause is JSONException) null else throw e
        }
        if (options == null || !options.has("passwordLoginEnabled")) throw NotAServerException(url)
        return options
    }

    /** The session the server opened, with the reader's names and the server's version; [user] is the user name typed, empty when the server is to name it. */
    private fun sessionOf(http: Http, url: String, reply: Reply, user: String): Session {
        val session = reply.json() ?: throw NotAServerException(url)
        val tokens = tokensOf(session) ?: throw NotAServerException(url)
        val named = session.optJSONObject("user")
        val name = user.ifEmpty { named?.optString("username").orEmpty() }.ifEmpty { throw NotAServerException(url) }
        val info = http.call("GET", "/app-info", headers = bearer(tokens.access))
        return Session(tokens, name, named?.optString("name").orEmpty(), info.json()?.optString("version").orEmpty())
    }

    private suspend fun signedIn(url: String, session: Session): DeviceChannel {
        accounts.signIn(url, session.user, session.name.ifBlank { session.user }, session.tokens, session.version)
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

    /**
     * Gives the reader's user name in the devices' channel a new password, for
     * a reader who does not know the one it has: the app made it up before it
     * was installed anew, or they forgot. Their other reading devices have to
     * be given the new one too.
     *
     * @throws IllegalArgumentException when the password is shorter than the server takes
     * @throws NotAllowedException when the reader may not sync devices
     */
    suspend fun newDevicePassword(password: String): DeviceChannel = settingUp.withLock {
        require(password.length >= MIN_DEVICE_PASSWORD) { "the server wants $MIN_DEVICE_PASSWORD characters at least" }
        val account = accounts.current() ?: throw SignedOutException()
        if (account.deviceUser.isEmpty()) return setUpDevice()
        val reply = jwt("PATCH", "/koreader/credentials", JSONObject().put("password", password))
        if (!reply.ok) throw failure(reply)
        val key = md5(password)
        accounts.saveDevice(account.deviceUser, key)
        probe(account, account.deviceUser, key)
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

    override suspend fun books(query: String, page: Int): RemotePage = books(query, page, PAGE_SIZE, readableOnly = true)

    /**
     * With [readableOnly], the server leaves out the books with no file the app reads, so that
     * a page and the total count only what can be downloaded.
     */
    private suspend fun books(query: String, page: Int, size: Int, readableOnly: Boolean = false): RemotePage {
        val body = JSONObject()
            .put("pagination", JSONObject().put("page", page).put("size", size))
            .put("sort", JSONArray().put(JSONObject().put("field", "title").put("dir", "asc")))
        if (query.isNotBlank()) body.put("q", query.trim().take(MAX_QUERY))
        if (readableOnly) body.put("filter", JSONObject(READABLE_FILTER))
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
        findLikes()
        return found.size
    }

    /**
     * Looks among the server's books for ones that go by the title and authors
     * of books here whose files the server has not, and marks those here. Such
     * a book is only pointed out to the reader: it may be another edition, or
     * another book altogether, and nothing is synced by it.
     * @return how many books here have a likeness on the server
     */
    suspend fun findLikes(): Int {
        val mine = library.booksNotOnServer()
        if (mine.isEmpty()) return 0
        val theirs = ArrayList<Pair<RemoteBook, Described>>()
        for (page in 0 until MAX_CATALOG_PAGES) {
            val part = books("", page, STATES_PAGE)
            for (book in part.books) theirs += book to Described(book.title, book.authors, book.series, book.seriesNumber)
            if (!part.hasMore(shown = theirs.size - part.books.size)) break
        }
        val likes = Likeness.pairs(mine.associate { it.id to Described(it.title, it.authors, it.series, it.seriesNumber) }, theirs)
        for (book in mine) {
            val like = likes[book.id]
            val named = like?.let { listOf(it.title, it.authors.joinToString(", ")).filter(String::isNotBlank).joinToString(" — ") }.orEmpty()
            if (like?.id != book.serverLikeId || named != book.serverLikeName) library.likeOnServer(book.id, like?.id, named)
        }
        return likes.size
    }

    /**
     * What the server takes from the reader: whether they may send books, how
     * large, in which formats, and into which libraries.
     */
    suspend fun uploadOptions(): UploadOptions {
        val reply = jwt("GET", "/uploads/capabilities")
        if (!reply.ok) throw failure(reply)
        val json = reply.json() ?: throw ServerResponseException(reply.code, "nothing said of uploads")
        val libraries = ArrayList<UploadLibrary>()
        json.optJSONArray("libraries").each { library ->
            val folders = ArrayList<UploadFolder>()
            library.optJSONArray("folders").each { folders += UploadFolder(it.optLong("id"), it.textOf("name")) }
            libraries += UploadLibrary(library.optLong("id"), library.textOf("name"), library.optJSONArray("allowedFormats").words(), folders)
        }
        return UploadOptions(
            allowed = json.optBoolean("canUploadToLibrary"), maxBytes = json.optLong("maxFileSizeBytes"),
            formats = json.optJSONArray("supportedFormats").words(), libraries = libraries,
        )
    }

    /**
     * Sends a book's [file] to the server, into [place], for the server to keep
     * and the book to be synced with it from then on. The server is asked first
     * whether it has the file: one that got there meanwhile is not sent again.
     *
     * The file goes in parts through an upload session of the server, which the
     * book keeps until the file is there. A file cut off on its way, by the
     * network or by the app being closed, goes on from what the server got when
     * it is sent again; [place] is then the one it was first sent to.
     *
     * The server files the book away by what the file says of itself, and by
     * the file's name when it says nothing, as FB2 does to this server: [name]
     * when it ends as the book's format does, a name made of the title otherwise.
     *
     * Cancelling the coroutine stops the file between two of its bytes, and
     * leaves the session to go on with or to call off, see [cancelUpload].
     *
     * @param progress told how many bytes of the file have gone, as they go
     * @throws UploadRefusedException when the server does not take the file
     * @throws NotAllowedException when the reader may not send books, or not into this library
     * @throws DeviceChannelException when the server cannot be asked which files it has
     * @throws IOException when the server cannot be reached; what it got so far is kept, and the rest is sent the next time
     */
    suspend fun upload(book: Book, file: File, place: UploadPlace, name: String? = null, progress: (Long) -> Unit = {}): Uploaded =
        sending.withLock {
            if (alreadyThere(book.id)) return Uploaded.WasThere
            val size = file.length()
            val stored = library.book(book.id)?.uploadSession
            var session = stored?.let { uploadSession(it) }
            if (session != null && session.status in FINISHED) return finished(book.id)
            if (session != null && (session.status != "receiving" || session.size != size)) session = null
            if (session == null) {
                // Gone, called off, run out, or for a file that has changed since: begun anew.
                if (stored != null) library.uploadEnded(book.id)
                session = openUpload(book, size, place, uploadName(book, name))
                library.uploadStarted(book.id, session.id)
            }

            val job = currentCoroutineContext().job
            var offset = session.received
            while (offset < size) {
                val from = offset
                val count = minOf(session.chunk, size - from)
                val reply = withToken { token ->
                    Http(token.first).upload("/uploads/${session.id}/chunks", bearer(token.second) + ("Upload-Offset" to "$from"), file, "part", from, count) {
                        // Told from the thread the file goes from: a cancelled send stops there, not at the end of the part.
                        job.ensureActive()
                        progress(from + it)
                    }
                }
                offset = when {
                    reply.ok -> reply.json()?.optLong("receivedBytes", from + count) ?: (from + count)
                    // The server got more, or less, of the part than was known here: it says from where to go on.
                    reply.errorCode == "UPLOAD_OFFSET_MISMATCH" ->
                        reply.json()?.optLong("expectedOffset", -1)?.takeIf { it in 0..size } ?: throw failure(reply)
                    else -> throw givenUp(book.id, session.id, reply)
                }
            }
            val reply = jwt("POST", "/uploads/${session.id}/complete")
            if (!reply.ok) throw givenUp(book.id, session.id, reply)
            finished(book.id)
        }

    /**
     * The reader called off a book's file on its way to the server: the server
     * lets go of what it got. Once the server has the whole file it is filed
     * away all the same, and the book turns out to be there.
     */
    suspend fun cancelUpload(bookId: String) = sending.withLock {
        val session = library.book(bookId)?.uploadSession ?: return@withLock
        try {
            jwt("DELETE", "/uploads/$session")
        } catch (e: IOException) {
            // What the server got runs out there by itself.
        }
        library.uploadEnded(bookId)
    }

    /**
     * Looks up the files that were on their way to the server when the app was
     * last closed or the network went: one the server filed away is marked as
     * there, one it let go of is forgotten here, one it still waits for the
     * rest of stays for the reader to send again or call off. Nothing is done
     * while a file is being sent.
     */
    suspend fun settleUploads() {
        if (!sending.tryLock()) return
        try {
            for (book in library.booksSending()) {
                val session = uploadSession(book.uploadSession ?: continue)
                when {
                    session?.status in FINISHED -> finished(book.id)
                    session?.status != "receiving" -> library.uploadEnded(book.id)
                }
            }
        } finally {
            sending.unlock()
        }
    }

    /** An upload session as the server has it; null when it has none such. */
    private class UploadSession(val id: String, val status: String, val size: Long, val received: Long, val chunk: Long)

    private suspend fun uploadSession(id: String): UploadSession? {
        val reply = jwt("GET", "/uploads/$id")
        if (reply.code == 404 || reply.code == 400) return null
        if (!reply.ok) throw failure(reply)
        return sessionOf(reply)
    }

    private fun sessionOf(reply: Reply): UploadSession {
        val json = reply.json() ?: throw ServerResponseException(reply.code, "no upload session")
        val id = json.textOf("id").ifEmpty { throw ServerResponseException(reply.code, "no upload session") }
        return UploadSession(
            id, json.textOf("status"), json.optLong("sizeBytes"), json.optLong("receivedBytes"),
            json.optLong("chunkSizeBytes").takeIf { it > 0 } ?: DEFAULT_CHUNK,
        )
    }

    /** Asks the server where the file goes, after seeing for itself that it takes such files there. */
    private suspend fun openUpload(book: Book, size: Long, place: UploadPlace, name: String): UploadSession {
        val options = uploadOptions()
        if (!options.allowed) throw NotAllowedException("the account may not send books to the server")
        if (book.format !in options.formats) throw UploadRefusedException(UploadRefusal.Format, "")
        val target = options.libraries.firstOrNull { it.id == place.libraryId } ?: throw UploadRefusedException(UploadRefusal.NoPlace, "")
        if (!target.takes(book.format)) throw UploadRefusedException(UploadRefusal.Format, "")
        if (place.folderId != null && target.folders.none { it.id == place.folderId }) throw UploadRefusedException(UploadRefusal.NoPlace, "")
        if (size > options.maxBytes) throw UploadRefusedException(UploadRefusal.TooLarge, "")

        val into = JSONObject().put("kind", "library").put("libraryId", place.libraryId)
        place.folderId?.let { into.put("folderId", it) }
        val body = JSONObject().put("filename", name).put("sizeBytes", size).put("target", into)
            // The same key again, as when the answer was lost on the way, is the same session.
            .put("idempotencyKey", "perigee-" + randomHex(16))
        val reply = jwt("POST", "/uploads", body)
        if (!reply.ok) throw refusal(reply)
        return sessionOf(reply)
    }

    /** A request with the session's token, asked again with a renewed one when it is not taken. */
    private suspend fun withToken(request: (Pair<String, String>) -> Reply): Reply {
        var token = access(stale = null)
        repeat(2) { attempt ->
            val reply = io { request(token) }
            if (reply.code != 401) return reply
            if (attempt == 0) token = access(stale = token.second)
        }
        throw signedOut()
    }

    /**
     * The server would not take the rest of the file. Unless the trouble is its
     * own, which passes, the session is let go of and the reason given.
     */
    private suspend fun givenUp(bookId: String, session: String, reply: Reply): IOException {
        if (reply.code < 500) {
            try {
                jwt("DELETE", "/uploads/$session")
            } catch (e: IOException) {
                // Left to run out on the server.
            }
            library.uploadEnded(bookId)
        }
        return refusal(reply)
    }

    /** The server took the whole file; which file of its book it is, it tells by the fingerprint. */
    private suspend fun finished(bookId: String): Uploaded {
        val known = try {
            alreadyThere(bookId)
        } catch (e: IOException) {
            // The file is there all the same; which one it is will be asked in the next round.
            false
        }
        if (!known) {
            library.uploadEnded(bookId)
            library.askServerAgain(bookId)
        }
        return Uploaded.Sent
    }

    /** Asks the server whether it has the file of a book, and remembers it when it has. */
    private suspend fun alreadyThere(bookId: String): Boolean {
        val file = match(listOf(bookId))[bookId.lowercase()] ?: return false
        library.onServer(bookId, file.bookId, file.id)
        return true
    }

    private fun refusal(reply: Reply): IOException = when {
        reply.errorCode == "UPLOAD_TOO_LARGE" || reply.code == 413 -> UploadRefusedException(UploadRefusal.TooLarge, reply.message)
        reply.errorCode == "UPLOAD_FORMAT_NOT_ALLOWED" || reply.errorCode == "UPLOAD_FORMAT_UNSUPPORTED" ->
            UploadRefusedException(UploadRefusal.Format, reply.message)
        reply.errorCode == "UPLOAD_DESTINATION_CONFLICT" || reply.errorCode == "UPLOAD_DUPLICATE" ->
            UploadRefusedException(UploadRefusal.NameTaken, reply.message)
        reply.errorCode == "UPLOAD_CONTENT_INVALID" || reply.errorCode == "UPLOAD_EMPTY" ->
            UploadRefusedException(UploadRefusal.NotABook, reply.message)
        reply.errorCode == "UPLOAD_STORAGE_FULL" || reply.code == 507 -> UploadRefusedException(UploadRefusal.NoRoom, reply.message)
        // A library that is gone, or a folder that is not of it.
        reply.errorCode == "UPLOAD_TARGET_INVALID" || reply.code == 404 || (reply.code == 400 && reply.message.contains("folder", ignoreCase = true)) ->
            UploadRefusedException(UploadRefusal.NoPlace, reply.message)
        else -> failure(reply)
    }

    /**
     * One round of keeping the server and the library in step: the places the
     * reader got to, status, rating and review, and the pages read. Nothing is done
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
        settleUploads()
        matchLibrary(all = false)
        val books = library.booksOnServer()
        var report = SyncReport()
        for (book in books) {
            if (book.status != ReadingStatus.Reading && !book.placeUnsent && library.pendingOffer(book.id) == null) continue
            report += syncPlaceOf(book)
        }
        for (book in books) {
            // A book never synced may have marks from before the reader signed in.
            val neverSynced = book.marksSyncedAt == 0L && library.marksOf(book.id).let { it.first.isNotEmpty() || it.second.isNotEmpty() }
            if (book.status == ReadingStatus.Reading || book.marksUnsent || neverSynced) report += syncMarksOf(book)
        }
        report += syncStates(books)
        report += syncReviews(books)
        report + syncPagesRead()
    }

    /**
     * Brings the quotes and bookmarks of one book in step, as when it is
     * opened. Asked twice: a quote made in the web reader is given a place the
     * engine knows on the first asking and handed over on the second.
     */
    suspend fun syncMarks(bookId: String): SyncReport = syncing.withLock {
        val book = library.book(bookId)?.takeIf { it.serverFileId != null } ?: return SyncReport()
        syncMarksOf(book, starsAndTitles = false) + syncMarksOf(checkNotNull(library.book(bookId)))
    }

    /**
     * Quotes and bookmarks of a book, both ways. The server is told of what was
     * made or changed here, and learns of what was removed from the list of
     * all that is here; so the list is said to be whole only when it is.
     */
    private suspend fun syncMarksOf(book: Book, starsAndTitles: Boolean = true): SyncReport {
        val changedAt = book.marksChangedAt
        val report = try {
            exchangeQuotes(book) + exchangeBookmarks(book) + if (starsAndTitles) starsAndTitlesOf(book) else SyncReport()
        } catch (e: BookGone) {
            library.notOnServer(listOf(book.id))
            return SyncReport()
        }
        library.marksSynced(book.id, maxOf(changedAt, 1))
        return report
    }

    /** The server has the file no more. */
    private class BookGone : IOException()

    /**
     * What the devices' channel does not carry: stars on quotes, both ways,
     * and titles given to bookmarks on the server. They are read through the
     * reader's session, where the server knows marks by its own numbers; a
     * mark here is tied to its number when the server hands it over, or else
     * by what it was sent as, see [Pairing].
     */
    private suspend fun starsAndTitlesOf(book: Book): SyncReport {
        val serverBookId = book.serverBookId ?: return SyncReport()
        val (bookmarks, quotes) = library.marksOf(book.id)
        var sent = 0
        var taken = 0

        val annotations = serverList("/books/$serverBookId/annotations") ?: return SyncReport()
        val tied = quotes.mapNotNull { it.serverId }.toSet()
        val loose = annotations.filter { it.optLong("id") !in tied }.sortedBy { it.optLong("id") }
        val ties = HashMap<Long, Long>()
        // Only a quote the server has heard of can be one of its own.
        for ((quote, annotation) in Pairing.pair(
            quotes.filter { it.serverId == null && it.sentAt > 0 }, loose,
            { Pairing.quoteWords(it.text, MAX_QUOTE_TEXT) }, { it.textOf("text").trim() },
        )) {
            library.linkQuote(quote.id, annotation.optLong("id"))
            ties[quote.id] = annotation.optLong("id")
        }
        val byId = annotations.associateBy { it.optLong("id") }
        for (quote in quotes) {
            val id = quote.serverId ?: ties[quote.id] ?: continue
            val theirs = byId[id] ?: continue
            val starred = !theirs.isNull("starredAt")
            if (quote.starUnsent) {
                if (starred != quote.starred) {
                    val reply = jwt("PATCH", "/books/$serverBookId/annotations/$id", JSONObject().put("starred", quote.starred))
                    if (!reply.ok && reply.code != 404) throw failure(reply)
                }
                library.starSent(quote.id, quote.starred)
                sent++
            } else if (starred != quote.starred) {
                library.takeStar(quote.id, starred)
                taken++
            }
        }

        val marked = serverList("/books/$serverBookId/bookmarks") ?: return SyncReport(marksSent = sent, marksTaken = taken)
        val sentAs = { bookmark: Bookmark -> Pairing.bookmarkTitle(bookmark.text, bookmark.chapter, MAX_BOOKMARK_TEXT, MAX_CHAPTER) }
        val tiedMarks = bookmarks.mapNotNull { it.serverId }.toSet()
        val titles = HashMap<Long, Long>()
        for ((bookmark, theirs) in Pairing.pair(
            bookmarks.filter { it.serverId == null && it.sent }, marked.filter { it.optLong("id") !in tiedMarks }.sortedBy { it.optLong("id") },
            sentAs, { it.textOf("title") },
        )) {
            library.linkBookmark(bookmark.id, theirs.optLong("id"))
            titles[bookmark.id] = theirs.optLong("id")
        }
        val markById = marked.associateBy { it.optLong("id") }
        for (bookmark in bookmarks) {
            val title = markById[bookmark.serverId ?: titles[bookmark.id] ?: continue]?.textOf("title") ?: continue
            // A title the server made of what it was sent is no title of the reader's.
            if (title.isBlank() || title == sentAs(bookmark) || title == bookmark.text) continue
            library.titleBookmark(bookmark.id, title)
            taken++
        }
        return SyncReport(marksSent = sent, marksTaken = taken)
    }

    /** A list the reader's session reads, or null for a book the server has no more. */
    private suspend fun serverList(path: String): List<JSONObject>? {
        val reply = jwt("GET", path)
        if (reply.code == 404) return null
        if (!reply.ok) throw failure(reply)
        val items = runCatching { JSONArray(reply.body) }.getOrNull() ?: throw ServerResponseException(reply.code, "no list")
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
    }

    private suspend fun exchangeBody(account: Account, book: JSONObject): JSONObject = JSONObject()
        .put("deviceId", account.deviceId).put("deviceModel", DEVICE_MODEL).put("pluginVersion", clientVersion)
        // The device's wall clock, for the server to stamp what it hands over in the device's own time.
        .put("deviceTime", Stamps.of(now())).put("books", JSONArray().put(book))

    private suspend fun exchangeQuotes(book: Book): SyncReport {
        val account = accounts.current() ?: throw SignedOutException()
        var sent = 0
        var taken = 0
        repeat(MAX_EXCHANGES) {
            val quotes = library.marksOf(book.id).second
            val changes = quotes.filter { it.unsent }.take(CHANGES_PER_EXCHANGE)
            val keys = JSONArray()
            for (quote in quotes.take(MAX_QUOTE_KEYS)) keys.put(JSONObject().put("k", Stamps.key(quote.stamp, quote.start)).put("dt", quote.stamp))
            val told = JSONArray()
            for (quote in changes) {
                val change = JSONObject()
                    .put("datetime", quote.stamp).put("drawer", "lighten").put("color", "#%06X".format(quote.color and 0xFFFFFF))
                    .put("text", quote.text.take(MAX_QUOTE_TEXT)).put("posFormat", "xpointer").put("pos0", quote.start).put("pos1", quote.end)
                if (quote.updatedAt != quote.createdAt) change.put("datetimeUpdated", Stamps.of(quote.updatedAt))
                quote.note?.let { change.put("note", it.take(MAX_NOTE)) }
                quote.chapter?.let { change.put("chapter", it.take(MAX_CHAPTER)) }
                told.put(change)
            }
            val request = JSONObject().put("hash", book.id).put("keys", keys)
                // A list cut short must not be taken for all there is: what it leaves out would be removed.
                .put("keysComplete", quotes.size <= MAX_QUOTE_KEYS).put("changes", told)
            val result = device("POST", "/koreader/plugin/annotations/exchange", exchangeBody(account, request))
                .json()?.optJSONArray("results")?.optJSONObject(0) ?: throw BookGone()
            for (quote in changes) library.quoteSent(quote.id, quote.updatedAt)
            sent += changes.size

            val toApply = result.optJSONObject("toApply") ?: JSONObject()
            val applied = JSONArray()
            val deleted = JSONArray()
            toApply.optJSONArray("add").each { add ->
                val start = add.textOf("pos0")
                library.takeQuote(
                    book.id, add.textOf("datetime"), start, add.textOf("pos1").ifEmpty { start }, add.textOf("text"),
                    add.textOf("note"), colorOf(add.textOf("color")), add.textOf("chapter").ifEmpty { null }, add.optLong("serverId").takeIf { it > 0 },
                )
                applied.put(JSONObject().put("serverId", add.optLong("serverId")).put("version", add.optInt("version", 1)).put("status", "applied"))
                taken++
            }
            val mine = library.marksOf(book.id).second
            toApply.optJSONArray("edit").each { edit ->
                val quote = mine.firstOrNull { Stamps.key(it.stamp, it.start) == edit.textOf("key") }
                    ?: mine.firstOrNull { it.stamp == edit.textOf("datetime") }
                if (quote != null) {
                    edit.optLong("serverId").takeIf { it > 0 }?.let { library.linkQuote(quote.id, it) }
                    library.takeQuoteChange(quote.id, edit.textOf("text").ifEmpty { quote.text }, edit.textOf("note"), colorOf(edit.textOf("color")), edit.textOf("chapter").ifEmpty { quote.chapter })
                    taken++
                }
                applied.put(
                    JSONObject().put("serverId", edit.optLong("serverId")).put("version", edit.optInt("version", 1))
                        .put("status", if (quote != null) "applied" else "failed"),
                )
            }
            toApply.optJSONArray("delete").each { gone ->
                val quote = mine.firstOrNull { Stamps.key(it.stamp, it.start) == gone.textOf("key") }
                    ?: mine.firstOrNull { it.stamp == gone.textOf("datetime") }
                if (quote != null) {
                    library.dropQuote(quote.id)
                    taken++
                }
                // Removed already is removed all the same.
                deleted.put(JSONObject().put("serverId", gone.optLong("serverId")).put("status", "applied"))
            }
            if (applied.length() + deleted.length() > 0) {
                val ack = JSONObject().put("hash", book.id).put("applied", applied).put("deleted", deleted)
                device("POST", "/koreader/plugin/annotations/exchange-ack", exchangeBody(account, ack))
            }
            if (!result.optBoolean("more") && quotes.count { it.unsent } <= CHANGES_PER_EXCHANGE) return SyncReport(marksSent = sent, marksTaken = taken)
        }
        return SyncReport(marksSent = sent, marksTaken = taken)
    }

    private suspend fun exchangeBookmarks(book: Book): SyncReport {
        val account = accounts.current() ?: throw SignedOutException()
        var sent = 0
        var taken = 0
        repeat(MAX_EXCHANGES) {
            val bookmarks = library.marksOf(book.id).first
            val changes = bookmarks.filter { !it.sent }.take(CHANGES_PER_EXCHANGE)
            val keys = JSONArray()
            for (bookmark in bookmarks.take(MAX_BOOKMARK_KEYS)) {
                keys.put(JSONObject().put("k", Stamps.key(bookmark.stamp, bookmark.xpointer)).put("dt", bookmark.stamp))
            }
            val told = JSONArray()
            for (bookmark in changes) {
                val change = JSONObject().put("datetime", bookmark.stamp).put("pos", bookmark.xpointer)
                // The words of the page are what the server titles the bookmark by.
                if (bookmark.text.isNotBlank()) change.put("note", bookmark.text.take(MAX_BOOKMARK_TEXT))
                bookmark.chapter?.let { change.put("chapter", it.take(MAX_CHAPTER)) }
                told.put(change)
            }
            val request = JSONObject().put("hash", book.id).put("keys", keys)
                .put("keysComplete", bookmarks.size <= MAX_BOOKMARK_KEYS).put("changes", told)
            val result = device("POST", "/koreader/plugin/bookmarks/exchange", exchangeBody(account, request))
                .json()?.optJSONArray("results")?.optJSONObject(0) ?: throw BookGone()
            // Taken or not: one the server cannot place, as in a format it has no places for, will not fare better again.
            for (bookmark in changes) library.bookmarkSent(bookmark.id)
            sent += changes.size

            val toApply = result.optJSONObject("toApply") ?: JSONObject()
            val applied = JSONArray()
            val deleted = JSONArray()
            toApply.optJSONArray("add").each { add ->
                val bookmark = library.takeBookmark(book.id, add.textOf("pos"), add.textOf("title"), add.optLong("serverId").takeIf { it > 0 })
                applied.put(
                    JSONObject().put("serverId", add.optLong("serverId")).put("status", "applied")
                        .put("key", Stamps.key(bookmark.stamp, bookmark.xpointer)).put("datetime", bookmark.stamp).put("pos", bookmark.xpointer),
                )
                taken++
            }
            val mine = library.marksOf(book.id).first
            toApply.optJSONArray("delete").each { gone ->
                val bookmark = mine.firstOrNull { Stamps.key(it.stamp, it.xpointer) == gone.textOf("key") }
                if (bookmark != null) {
                    library.dropBookmark(bookmark.id)
                    taken++
                }
                deleted.put(JSONObject().put("serverId", gone.optLong("serverId")).put("status", "applied"))
            }
            if (applied.length() + deleted.length() > 0) {
                val ack = JSONObject().put("hash", book.id).put("applied", applied).put("deleted", deleted)
                device("POST", "/koreader/plugin/bookmarks/exchange-ack", exchangeBody(account, ack))
            }
            if (!result.optBoolean("more") && bookmarks.count { !it.sent } <= CHANGES_PER_EXCHANGE) return SyncReport(marksSent = sent, marksTaken = taken)
        }
        return SyncReport(marksSent = sent, marksTaken = taken)
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
        // The server (3.4 on) drops a place said to be older than the newest it knows. A reader who was shown a
        // later place from elsewhere and stayed has decided for the one here: it is sent as the later of the two.
        val at = maxOf(book.movedAt / 1000, book.remoteSeenAt + 1)
        val body = JSONObject()
            .put("document", book.id).put("percentage", book.progress.toDouble().coerceIn(0.0, 1.0)).put("progress", xpointer)
            .put("device", deviceName).put("device_id", account.deviceId).put("timestamp", at)
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

    /**
     * The reader's reviews, both ways. One written here goes up through the
     * reader's session, as the web reader sends it: the devices' channel would
     * weigh it against the server's by the day alone. The server's are asked
     * for through the devices' channel, many books at once, and taken unless
     * the reader changed theirs since the server last took it.
     */
    private suspend fun syncReviews(books: List<Book>): SyncReport {
        val account = accounts.current() ?: throw SignedOutException()
        var sent = 0
        for (book in books) {
            val id = book.serverBookId ?: continue
            if (!book.reviewUnsent) continue
            val reply = jwt("PATCH", "/books/$id/personal-note", JSONObject().put("note", book.review ?: JSONObject.NULL))
            // A book the server has no more is found out by the next matching; the review waits here meanwhile.
            if (reply.code == 404) continue
            if (!reply.ok) throw failure(reply)
            library.reviewSent(book.id, book.reviewChangedAt)
            sent++
        }
        var taken = 0
        for (part in books.chunked(STATES_PAGE)) {
            val list = JSONArray()
            for (book in part) list.put(JSONObject().put("hash", book.id))
            val body = JSONObject()
                .put("deviceId", account.deviceId).put("deviceModel", DEVICE_MODEL).put("pluginVersion", clientVersion).put("books", list)
            device("POST", "/koreader/plugin/book-states", body).json()?.optJSONArray("results").each { result ->
                val book = library.book(result.optString("hash")) ?: return@each
                val theirs = result.textOf("reviewNote").ifEmpty { null }
                if (book.reviewUnsent || theirs == book.review) return@each
                library.takeReview(book.id, theirs)
                taken++
            }
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

        /**
         * The books with a file, any of theirs, in a format the engine reads. The server takes up to
         * twenty formats in one rule and leaves the ones it does not know unmatched.
         */
        private val READABLE_FILTER: String = JSONObject()
            .put("type", "group").put("join", "AND")
            .put("rules", JSONArray().put(JSONObject().put("type", "rule").put("field", "format").put("operator", "includesAny").put("value", JSONArray(Library.FORMATS))))
            .toString()
        private const val MATCH_BATCH = 500
        private const val MAX_COVER = 4 * 1024 * 1024

        /** The part of a file sent at a time, when the server does not say; what is cut off is sent again at most. */
        private const val DEFAULT_CHUNK = 16L * 1024 * 1024

        /** An upload session past these has the whole file, and the server files it away or has done so. */
        private val FINISHED = setOf("processing", "completed")

        /** The shortest password the server takes for the devices' channel. */
        const val MIN_DEVICE_PASSWORD = 6
        private const val STATES_PAGE = 200

        /** As far as the server's list of books is read to look for likenesses: twenty thousand books. */
        private const val MAX_CATALOG_PAGES = 100
        private const val MAX_EXCHANGES = 40
        private const val CHANGES_PER_EXCHANGE = 50
        private const val MAX_QUOTE_KEYS = 5000
        private const val MAX_BOOKMARK_KEYS = 500
        private const val MAX_QUOTE_TEXT = 10_000
        private const val MAX_NOTE = 5_000
        private const val MAX_CHAPTER = 500
        private const val MAX_BOOKMARK_TEXT = 255

        /** The colours KOReader names, as the server's web pages show them. */
        private val NAMED_COLORS = mapOf(
            "red" to 0xF87171, "orange" to 0xFB923C, "yellow" to 0xFACC15, "green" to 0x4ADE80, "olive" to 0x84CC16,
            "cyan" to 0x22D3EE, "blue" to 0x38BDF8, "purple" to 0xC084FC, "gray" to 0x9CA3AF,
        )

        /** A colour as the server tells it to a device: by name, or as #RRGGBB; yellow when it is neither. */
        internal fun colorOf(told: String): Int {
            val text = told.trim().lowercase()
            NAMED_COLORS[text]?.let { return it }
            return text.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16) ?: NAMED_COLORS.getValue("yellow")
        }

        private inline fun JSONArray?.each(block: (JSONObject) -> Unit) {
            if (this == null) return
            for (i in 0 until length()) optJSONObject(i)?.let(block)
        }
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

        /** Where the provider sends the reader back: the one address of the server's own apps, which it takes from every native client. */
        const val REDIRECT_URI = "bookorbit://oauth2-callback"

        private fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun randomBase64(bytes: Int): String = base64(ByteArray(bytes).also(SecureRandom()::nextBytes))

        /** The parameters of an address's query, decoded; empty for an address that is not one. */
        private fun queryOf(address: String): Map<String, String> {
            val query = try {
                URI(address).rawQuery
            } catch (e: URISyntaxException) {
                null
            } ?: return emptyMap()
            return query.split('&').filter { it.isNotEmpty() }.associate { part ->
                val name = part.substringBefore('=')
                val value = part.substringAfter('=', "")
                fun decode(text: String) = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
                decode(name) to decode(value)
            }
        }

        private fun randomHex(bytes: Int): String =
            ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

        private fun JSONObject.textOf(name: String): String = if (isNull(name)) "" else optString(name)

        /** The words of a list, in lower case. */
        private fun JSONArray?.words(): Set<String> {
            if (this == null) return emptySet()
            return (0 until length()).map { optString(it).trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        }

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

        /**
         * The name a book's file goes to the server under: [given], the name the
         * file had where it came from, when it ends as the book's format does;
         * one made of the title otherwise.
         */
        internal fun uploadName(book: Book, given: String?): String {
            val ending = "." + book.format
            val stem = given?.takeIf { it.endsWith(ending, ignoreCase = true) }?.dropLast(ending.length)?.let(::safeName)?.ifEmpty { null }
            return (stem ?: safeName(book.title).ifEmpty { "book" }) + ending
        }
    }
}
