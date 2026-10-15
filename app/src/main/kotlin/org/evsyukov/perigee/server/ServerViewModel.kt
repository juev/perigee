package org.evsyukov.perigee.server

import android.app.Application
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.data.Account
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.library.Arrivals
import org.evsyukov.perigee.library.localCopy
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.DeviceChannel
import org.evsyukov.perigee.sync.DeviceChannelException
import org.evsyukov.perigee.sync.IncompatibleServerException
import org.evsyukov.perigee.sync.NotAServerException
import org.evsyukov.perigee.sync.NotAllowedException
import org.evsyukov.perigee.sync.PasswordLoginDisabledException
import org.evsyukov.perigee.sync.RemoteBook
import org.evsyukov.perigee.sync.RemoteFile
import org.evsyukov.perigee.sync.SignedOutException
import org.evsyukov.perigee.sync.TooManyAttemptsException
import org.evsyukov.perigee.sync.WrongPasswordException

/** Why the server did not answer as hoped. */
enum class ServerFailure {
    /** The address is not one a server can be at. */
    BadAddress,

    /** There is something at the address, but not a bookOrbit server. */
    NotAServer,
    WrongPassword,

    /** The server asks to wait before the next try. */
    TooManyAttempts,

    /** The server lets nobody in by password, and the app knows no other way yet. */
    PasswordLoginDisabled,

    /** The server cannot be reached or refuses. */
    Unreachable,

    /** The reader's account on the server is not allowed what was asked for. */
    NotAllowed,

    /** A downloaded file is not a book the app reads. */
    NotABook,
}

/** How far a book is on its way from the server into the library. */
sealed interface Fetched {
    data object Running : Fetched
    data class Failed(val why: ServerFailure) : Fetched
}

data class ServerState(
    /** False until it is known whether the reader is signed in; nothing is shown before that. */
    val loaded: Boolean = false,
    /** Null while the reader is signed in nowhere. */
    val account: Account? = null,
    /** The session ended and cannot be renewed; the reader has to sign in again. */
    val sessionOver: Boolean = false,
    val signingIn: Boolean = false,
    val signInFailure: ServerFailure? = null,
    /** How things stand with the channel reading is synced through; null until it is known. */
    val channel: DeviceChannel? = null,
    val channelBusy: Boolean = false,
    val channelPasswordWrong: Boolean = false,
    /** What the list shown was looked up by; empty for all the books. */
    val query: String = "",
    val books: List<RemoteBook> = emptyList(),
    /** Whether the server has more of the list than is shown. */
    val more: Boolean = false,
    val loading: Boolean = false,
    val failure: ServerFailure? = null,
    /** By the id of the file on the server. */
    val downloads: Map<Long, Fetched> = emptyMap(),
    /** Files of the server that are in the library, by their id there, with the book each one is. */
    val onDevice: Map<Long, String> = emptyMap(),
    /** A download that waits for the reader to say where downloaded books should go. */
    val asking: Pair<RemoteBook, RemoteFile>? = null,
    /** How many books have something the reader did in them still to reach the server. */
    val unsent: Int = 0,
)

/** The bookOrbit server: signing in to it, looking through its books, downloading them into the library. */
class ServerViewModel(
    application: Application,
    private val orbit: BookOrbit,
    private val accounts: Accounts,
    private val library: Library,
    private val arrivals: Arrivals,
) : AndroidViewModel(application) {

    @Suppress("unused") // The constructor the system makes the model with.
    constructor(application: Application) : this(
        application, Server.get(application), Accounts.get(application), Library.get(application), Arrivals(application),
    )

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private var fetching: Job? = null
    private var page = 0
    private val pictures = LruCache<Long, ImageBitmap>(64)

    init {
        viewModelScope.launch {
            var signedInTo: Any? = Unit
            accounts.account().collect { account ->
                // Renewed tokens and a channel set up change the account too; only another server or reader starts anew.
                val identity = account?.let { Triple(it.url, it.user, it.addedAt) }
                if (identity == signedInTo) {
                    _state.update { it.copy(account = account) }
                    return@collect
                }
                signedInTo = identity
                val over = account != null && accounts.tokens()?.refresh.isNullOrEmpty()
                _state.update {
                    ServerState(
                        loaded = true, account = account, sessionOver = over, signingIn = it.signingIn,
                        signInFailure = it.signInFailure, onDevice = it.onDevice, unsent = it.unsent,
                    )
                }
                if (account != null && !over) open()
            }
        }
        viewModelScope.launch {
            library.books().collect { books ->
                val there = books.mapNotNull { book -> book.serverFileId?.let { it to book.id } }.toMap()
                _state.update { it.copy(onDevice = there, unsent = books.count { book -> book.syncUnsent }) }
            }
        }
    }

    /** Signs in, in place of whoever was signed in before. */
    fun signIn(address: String, user: String, password: String) {
        if (_state.value.signingIn) return
        _state.update { it.copy(signingIn = true, signInFailure = null) }
        viewModelScope.launch {
            val failure = try {
                orbit.signIn(address, user.trim(), password)
                null
            } catch (e: IllegalArgumentException) {
                ServerFailure.BadAddress
            } catch (e: IOException) {
                failureOf(e)
            }
            _state.update { it.copy(signingIn = false, signInFailure = failure) }
        }
    }

    fun signOut() {
        fetching?.cancel()
        viewModelScope.launch { orbit.signOut() }
    }

    /** The reader gave the password of the user name their reading devices sync under. */
    fun devicePassword(password: String) {
        if (_state.value.channelBusy) return
        _state.update { it.copy(channelBusy = true, channelPasswordWrong = false) }
        viewModelScope.launch {
            try {
                val channel = orbit.devicePassword(password)
                _state.update { it.copy(channelBusy = false, channel = channel) }
                if (channel == DeviceChannel.Ready) matchLibrary()
            } catch (e: WrongPasswordException) {
                _state.update { it.copy(channelBusy = false, channelPasswordWrong = true) }
            } catch (e: IOException) {
                _state.update { it.copy(channelBusy = false) }
                failed(e)
            }
        }
    }

    /** The reader does not know that password and sets a new one, which their other devices will need too. */
    fun newDevicePassword(password: String) {
        if (_state.value.channelBusy) return
        _state.update { it.copy(channelBusy = true, channelPasswordWrong = false) }
        viewModelScope.launch {
            try {
                val channel = orbit.newDevicePassword(password)
                _state.update { it.copy(channelBusy = false, channel = channel) }
                if (channel == DeviceChannel.Ready) matchLibrary()
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(channelBusy = false, channelPasswordWrong = true) }
            } catch (e: IOException) {
                _state.update { it.copy(channelBusy = false) }
                failed(e)
            }
        }
    }

    /** Looks the server's books up by a phrase; an empty one shows them all. */
    fun search(query: String) {
        _state.update { it.copy(query = query.trim(), books = emptyList(), more = false) }
        page = 0
        fetch { show() }
    }

    /** Fetches the part of the list that follows, when the reader has reached its end. */
    fun more() {
        val state = _state.value
        if (!state.more || state.loading) return
        fetch {
            page++
            show()
        }
    }

    /** Asks the server again from the start, as after it could not be reached. */
    fun retry() {
        if (_state.value.account != null && !_state.value.sessionOver) open()
    }

    /** What is asked of the server when its screen is come to: its version, the channel, the reader's books, its own. */
    private fun open() {
        page = 0
        _state.update { it.copy(books = emptyList(), more = false) }
        fetch {
            if (!orbit.checkServer()) {
                _state.update { it.copy(channel = DeviceChannel.Unsupported) }
            } else {
                val channel = orbit.deviceChannel()
                _state.update { it.copy(channel = channel) }
                if (channel == DeviceChannel.Ready) matchLibrary()
            }
            show()
        }
    }

    /** Which books of the library the server has. A channel that stopped working says so in its own place. */
    private suspend fun matchLibrary() {
        try {
            orbit.matchLibrary(all = true)
        } catch (e: DeviceChannelException) {
            _state.update { it.copy(channel = e.state) }
        } catch (e: IncompatibleServerException) {
            _state.update { it.copy(channel = DeviceChannel.Unsupported) }
        }
    }

    private suspend fun show() {
        val asked = _state.value.query
        val found = orbit.books(asked, page)
        _state.update { state ->
            // The reader may have asked for something else while this was on the way.
            if (state.query != asked) return@update state
            // A list that shifted between its parts brings a book twice; and one that brings nothing new has ended.
            val known = if (page == 0) emptyList() else state.books
            val fresh = found.books.filter { book -> known.none { it.id == book.id } }
            state.copy(books = known + fresh, more = fresh.isNotEmpty() && found.hasMore(shown = known.size))
        }
    }

    private fun fetch(block: suspend () -> Unit) {
        fetching?.cancel()
        _state.update { it.copy(loading = true, failure = null) }
        fetching = viewModelScope.launch {
            try {
                block()
                _state.update { it.copy(loading = false) }
            } catch (e: IOException) {
                _state.update { it.copy(loading = false) }
                failed(e)
            }
        }
    }

    private fun failed(e: IOException) {
        if (e is SignedOutException) {
            _state.update { it.copy(sessionOver = true, books = emptyList(), more = false) }
        } else {
            _state.update { it.copy(failure = failureOf(e)) }
        }
    }

    /**
     * Downloads a file and puts the book into the library. The first time, the
     * reader is asked where downloaded books should go, and the download
     * waits for the answer.
     */
    fun download(book: RemoteBook, file: RemoteFile) {
        if (_state.value.downloads[file.id] is Fetched.Running) return
        viewModelScope.launch {
            if (!arrivals.asked()) {
                _state.update { it.copy(asking = book to file) }
                return@launch
            }
            mark(file, Fetched.Running)
            // Its own folder: two books may come under one name.
            val landing = File(getApplication<Application>().cacheDir, "server/${UUID.randomUUID()}")
            val failure = try {
                val arrived = orbit.download(file, book.title, landing)
                val id = arrivals.keep(arrived, Library.formatOf(arrived.name).ifEmpty { file.format })
                // Known to be the server's without asking it: it has just come from there.
                if (id != null) library.onServer(id, file.bookId, file.id)
                if (id == null) ServerFailure.NotABook else null
            } catch (e: SignedOutException) {
                failed(e)
                ServerFailure.Unreachable
            } catch (e: IOException) {
                failureOf(e)
            } finally {
                withContext(Dispatchers.IO) { landing.deleteRecursively() }
            }
            mark(file, failure?.let(Fetched::Failed))
        }
    }

    /** The reader chose a folder for downloaded books; the download that waited for it goes on. */
    fun downloadInto(uri: String, name: String) = answer { arrivals.into(uri, name) }

    /** The reader would rather keep downloaded books in the app's own storage. */
    fun downloadIntoApp() = answer(arrivals::intoApp)

    /** The question was put away unanswered; it will be asked again. */
    fun dismissAsking() = _state.update { it.copy(asking = null) }

    private fun answer(remember: suspend () -> Unit) {
        val waiting = _state.value.asking
        _state.update { it.copy(asking = null) }
        viewModelScope.launch {
            remember()
            if (waiting != null) download(waiting.first, waiting.second)
        }
    }

    private fun mark(file: RemoteFile, fetched: Fetched?) = _state.update {
        it.copy(downloads = if (fetched == null) it.downloads - file.id else it.downloads + (file.id to fetched))
    }

    /** The file to read a book of the library from; null when it cannot be got at any more. */
    suspend fun fileOf(bookId: String): File? = library.book(bookId)?.let { localCopy(getApplication(), library, it) }

    /** The cover of a book, fetched once and kept while the server is looked through. */
    suspend fun cover(book: RemoteBook): ImageBitmap? {
        pictures.get(book.id)?.let { return it }
        val bytes = try {
            orbit.cover(book)
        } catch (e: IOException) {
            null
        } ?: return null
        return withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            ?.also { pictures.put(book.id, it) }
    }

    private fun failureOf(e: IOException): ServerFailure = when (e) {
        is NotAServerException -> ServerFailure.NotAServer
        is WrongPasswordException -> ServerFailure.WrongPassword
        is TooManyAttemptsException -> ServerFailure.TooManyAttempts
        is PasswordLoginDisabledException -> ServerFailure.PasswordLoginDisabled
        is NotAllowedException -> ServerFailure.NotAllowed
        else -> ServerFailure.Unreachable
    }
}
