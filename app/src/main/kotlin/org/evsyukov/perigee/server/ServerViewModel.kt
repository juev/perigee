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
import org.evsyukov.perigee.sync.AccountChangedException
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.CatalogueQuery
import org.evsyukov.perigee.sync.DeviceChannel
import org.evsyukov.perigee.sync.DeviceChannelException
import org.evsyukov.perigee.sync.IncompatibleServerException
import org.evsyukov.perigee.sync.NameTakenException
import org.evsyukov.perigee.sync.NotAServerException
import org.evsyukov.perigee.sync.NotAddedException
import org.evsyukov.perigee.sync.NotAllowedException
import org.evsyukov.perigee.sync.NotOnServerException
import org.evsyukov.perigee.sync.NotSavedException
import org.evsyukov.perigee.sync.PasswordLoginDisabledException
import org.evsyukov.perigee.sync.Related
import org.evsyukov.perigee.sync.RelatedBooks
import org.evsyukov.perigee.sync.RemoteBook
import org.evsyukov.perigee.sync.RemoteBookDetail
import org.evsyukov.perigee.sync.RemoteCollection
import org.evsyukov.perigee.sync.RemoteFile
import org.evsyukov.perigee.sync.ShelfKind
import org.evsyukov.perigee.sync.SignInCancelledException
import org.evsyukov.perigee.sync.SignInExpiredException
import org.evsyukov.perigee.sync.SignInProvider
import org.evsyukov.perigee.sync.SignInRefusedException
import org.evsyukov.perigee.sync.SignedOutException
import org.evsyukov.perigee.sync.TooManyAttemptsException
import org.evsyukov.perigee.sync.UnsupportedOnServerException
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

    /** The server lets nobody in by password. */
    PasswordLoginDisabled,

    /** The server signs nobody in through a provider. */
    NoProviders,

    /** Signing in through a provider took too long, or the app was closed meanwhile. */
    SignInExpired,

    /** The provider or the server did not let the reader in; their word on it is in [ServerState.signInRefusal]. */
    SignInRefused,

    /** There is no browser to show the provider's page in. */
    NoBrowser,

    /** The server cannot be reached or refuses. */
    Unreachable,

    /** The reader's account on the server is not allowed what was asked for. */
    NotAllowed,

    /** A downloaded file is not a book the app reads. */
    NotABook,

    /** This version of the server does not offer what was asked for; the rest of what it offers still works. */
    Unsupported,

    /** What was asked about is not on the server any more, as a book or a collection removed there. */
    NotFound,

    /** The reader has a collection of that name already. */
    NameTaken,

    /** The server answered and did not take the change, as for a rating it keeps locked. */
    NotSaved,
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
    /** What the provider or the server said when it did not let the reader in. */
    val signInRefusal: String = "",
    /** The server's providers for the reader to choose from; empty while there is no choosing. */
    val providers: List<SignInProvider> = emptyList(),
    /** The provider's page to show the reader, until the screen has shown it. */
    val providerPage: String? = null,
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
    /** The book whose page is open; null while the list alone is shown. */
    val opened: RemoteBook? = null,
    /** What the server told of [opened]; null until it has. */
    val detail: RemoteBookDetail? = null,
    val detailLoading: Boolean = false,
    val detailFailure: ServerFailure? = null,
    /** The part of the screen shown once signed in: the home page's shelves, the catalogue, or the collections. */
    val section: Section = Section.Home,
    /** What narrows and orders the catalogue besides [query], its phrase. */
    val filters: CatalogueQuery = CatalogueQuery(),
    /** The names of the server's libraries the catalogue can be narrowed to; empty until asked, or when it says none. */
    val libraries: List<String> = emptyList(),
    /** The home page's shelves; a shelf not here was not asked for yet. */
    val shelves: Map<ShelfKind, ShelfState> = emptyMap(),
    val collections: CollectionsState = CollectionsState(),
    /** The collection whose books are shown; null while the list of collections is. */
    val collection: CollectionView? = null,
    /** Something done to a collection that the server did not take, told until it is put away. */
    val collectionFailure: ServerFailure? = null,
    val collectionBusy: Boolean = false,
    /** The pages opened before [opened], from one book to another beside it, oldest first: the way back goes through them. */
    val trail: List<RemoteBook> = emptyList(),
    /** The books the server lists beside [opened]; a kind not here is not asked for it. */
    val related: Map<Related, RelatedState> = emptyMap(),
    /** The reader's own collections while they choose which of them [opened] is in; null otherwise. */
    val membership: Membership? = null,
    /**
     * The changes of what the reader keeps of books on their way to the server, by book and what of it, each
     * with the number of the asking waited for: a book's page opened again shows its own as on their way.
     */
    val writing: Map<Pair<Long, Personal>, Long> = emptyMap(),
    /** The last change of a book the server did not take; its page tells it until it is put away. */
    val personalFailure: PersonalFailure? = null,
    /** The server's books whose copies in the library have a status, rating or review not sent yet. */
    val unsentOnDevice: Set<Long> = emptySet(),
    /** The server's books that are in the library, by the server's number, with the book each one is here. */
    val onDeviceByBook: Map<Long, String> = emptyMap(),
    /** Why the libraries to narrow the catalogue to could not be asked for; null when they could, or were not asked. */
    val librariesFailure: ServerFailure? = null,
) {
    /** What of the server's book [bookId] is on its way to the server. */
    fun saving(bookId: Long): Set<Personal> = writing.keys.filter { it.first == bookId }.mapTo(HashSet()) { it.second }
}

/**
 * The bookOrbit server: signing in to it, its home page's shelves, its catalogue and collections, a book's
 * page with what the reader keeps of it there, and downloading books into the library.
 */
class ServerViewModel(
    application: Application,
    private val orbit: BookOrbit,
    private val accounts: Accounts,
    private val library: Library,
    private val arrivals: Arrivals,
    private val redirects: MutableStateFlow<String?> = Server.redirects,
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

    /**
     * Counts the readers and servers signed in to while the screen lives. An answer is taken only for the one
     * it was asked under: another server numbers its books and collections its own way.
     */
    private var epoch = 0

    /** What the server told of a book, kept while the screen lives: a page opened again asks nothing. */
    private val details = HashMap<Long, RemoteBookDetail>()
    private var detailing: Job? = null

    /** The books the server listed beside a book, kept as [details] are: the way back to a page asks nothing. */
    private val relatedKept = HashMap<Pair<Long, Related>, RelatedBooks>()
    private val relating = HashMap<Related, Job>()

    private var shelving: Job? = null
    private val shelfRetries = HashMap<ShelfKind, Job>()
    private var listingCollections: Job? = null
    private var collectionPaging: Job? = null
    private var memberships: Job? = null

    /**
     * What was asked of the server under the reader signed in now and has still to come: changes, and what
     * follows them. Another reader or server signed in lets go of all of it.
     */
    private val inFlight: MutableSet<Job> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** The number of the last change asked for; the answer to an earlier one does not end a later one's waiting. */
    private var askings = 0L

    private fun tracked(block: suspend () -> Unit): Job {
        val job = viewModelScope.launch { block() }
        inFlight += job
        job.invokeOnCompletion { inFlight.remove(job) }
        return job
    }

    /** The session ended while something was asked under the reader signed in at [at]; for another one, nothing ended. */
    private fun ended(at: Int) {
        if (at == epoch) sessionEnded()
    }

    init {
        viewModelScope.launch {
            var signedInTo: Any? = Unit
            accounts.account().collect { account ->
                // Renewed tokens and a channel set up change the account too; only another server or reader starts anew.
                val identity = account?.deviceId
                if (identity == signedInTo) {
                    _state.update { it.copy(account = account) }
                    return@collect
                }
                signedInTo = identity
                startAnew()
                val over = account != null && accounts.tokens()?.refresh.isNullOrEmpty()
                _state.update {
                    ServerState(
                        loaded = true, account = account, sessionOver = over, signingIn = it.signingIn,
                        signInFailure = it.signInFailure, onDevice = it.onDevice, unsent = it.unsent, unsentOnDevice = it.unsentOnDevice,
                        onDeviceByBook = it.onDeviceByBook,
                    )
                }
                if (account != null && !over) open()
            }
        }
        viewModelScope.launch {
            redirects.collect { redirect ->
                // Taken once, even with two screens of the server about.
                if (redirect != null && redirects.compareAndSet(redirect, null)) finishSignIn(redirect)
            }
        }
        viewModelScope.launch {
            library.books().collect { books ->
                val there = books.mapNotNull { book -> book.serverFileId?.let { it to book.id } }.toMap()
                val waiting = books.filter { it.serverFileId != null && (it.stateUnsent || it.reviewUnsent) }.mapNotNull { it.serverBookId }.toSet()
                val byBook = books.filter { it.serverFileId != null }.mapNotNull { book -> book.serverBookId?.let { it to book.id } }.toMap()
                _state.update {
                    it.copy(onDevice = there, unsent = books.count { book -> book.syncUnsent }, unsentOnDevice = waiting, onDeviceByBook = byBook)
                }
            }
        }
    }

    /** Another server or reader: nothing asked of the one before is taken, and nothing kept of it is shown. */
    private fun startAnew() {
        epoch++
        fetching?.cancel()
        detailing?.cancel()
        shelving?.cancel()
        listingCollections?.cancel()
        collectionPaging?.cancel()
        memberships?.cancel()
        relating.values.forEach(Job::cancel)
        relating.clear()
        shelfRetries.values.forEach(Job::cancel)
        shelfRetries.clear()
        inFlight.toList().forEach(Job::cancel)
        inFlight.clear()
        details.clear()
        relatedKept.clear()
        pictures.evictAll()
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

    /**
     * Signs in through a provider of the server at [address]: the only one is
     * gone to straight away, several are offered to choose from.
     */
    fun signInThroughProvider(address: String) {
        if (_state.value.signingIn) return
        _state.update { it.copy(signingIn = true, signInFailure = null, providers = emptyList()) }
        viewModelScope.launch {
            try {
                val providers = orbit.signInOptions(address).providers
                when (providers.size) {
                    0 -> _state.update { it.copy(signingIn = false, signInFailure = ServerFailure.NoProviders) }
                    1 -> startSignIn(address, providers.single())
                    else -> _state.update { it.copy(signingIn = false, providers = providers) }
                }
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(signingIn = false, signInFailure = ServerFailure.BadAddress) }
            } catch (e: IOException) {
                signInFailed(e)
            }
        }
    }

    /** The reader chose one of the server's providers to sign in through. */
    fun chooseProvider(address: String, provider: SignInProvider) {
        if (_state.value.signingIn) return
        _state.update { it.copy(signingIn = true, providers = emptyList()) }
        viewModelScope.launch { startSignIn(address, provider) }
    }

    /** The reader chose none of the providers. */
    fun dismissProviders() = _state.update { it.copy(providers = emptyList()) }

    /** The provider's page is shown, or could not be: there is no browser when [opened] is false. */
    fun providerPageShown(opened: Boolean) =
        _state.update { it.copy(providerPage = null, signInFailure = if (opened) null else ServerFailure.NoBrowser) }

    private suspend fun startSignIn(address: String, provider: SignInProvider) {
        try {
            val page = orbit.startSignIn(address, provider)
            // Nothing is shown as busy while the reader is at the provider: they may come back without signing in.
            _state.update { it.copy(signingIn = false, providerPage = page) }
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(signingIn = false, signInFailure = ServerFailure.BadAddress) }
        } catch (e: IOException) {
            signInFailed(e)
        }
    }

    private fun finishSignIn(redirect: String) {
        _state.update { it.copy(signingIn = true, signInFailure = null) }
        viewModelScope.launch {
            try {
                orbit.finishSignIn(redirect)
                _state.update { it.copy(signingIn = false) }
            } catch (e: SignInCancelledException) {
                _state.update { it.copy(signingIn = false) }
            } catch (e: IOException) {
                signInFailed(e)
            }
        }
    }

    private fun signInFailed(e: IOException) = _state.update {
        it.copy(signingIn = false, signInFailure = failureOf(e), signInRefusal = (e as? SignInRefusedException)?.message.orEmpty())
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

    /** Shows another part of the screen. What each part was looked up by, and how far it was read, stays. */
    fun show(section: Section) {
        _state.update { it.copy(section = section) }
        val state = _state.value
        if (section == Section.Collections && !state.collections.loaded && !state.collections.loading) loadCollections()
    }

    /** Looks the server's books up by a phrase; an empty one shows them all. The catalogue's filters stay. */
    fun search(query: String) {
        _state.update { it.copy(query = query.trim(), books = emptyList(), more = false) }
        page = 0
        fetch { show() }
    }

    /** Narrows and orders the catalogue anew; its phrase stays. */
    fun filter(filters: CatalogueQuery) {
        _state.update { it.copy(filters = filters.copy(text = ""), books = emptyList(), more = false) }
        page = 0
        fetch { show() }
    }

    /** The catalogue as the shelf's query has it, for a shelf there is one for; see [catalogueOf]. */
    fun seeAll(kind: ShelfKind) {
        val filters = catalogueOf(kind) ?: return
        _state.update { it.copy(section = Section.Catalogue, query = "") }
        filter(filters)
    }

    /** The libraries the catalogue can be narrowed to, asked once a reader goes to narrow it. */
    fun loadLibraries() {
        if (_state.value.libraries.isNotEmpty()) return
        val at = epoch
        _state.update { it.copy(librariesFailure = null) }
        tracked {
            try {
                val names = orbit.area.libraries()
                if (at == epoch) _state.update { it.copy(libraries = names) }
            } catch (e: SignedOutException) {
                ended(at)
            } catch (e: IOException) {
                // Said in the filters, apart from the rest of them: the catalogue is narrowed by those meanwhile.
                if (at == epoch) _state.update { it.copy(librariesFailure = failureOf(e)) }
            }
        }
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

    /** What is asked of the server when its screen is come to: its version, the channel, its shelves, the reader's books, its own. */
    private fun open() {
        page = 0
        _state.update { it.copy(books = emptyList(), more = false) }
        loadShelves()
        if (_state.value.collections.loaded) loadCollections()
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
        val filters = _state.value.filters
        val found = orbit.books(filters.copy(text = asked), page)
        _state.update { state ->
            // The reader may have asked for something else while this was on the way.
            if (state.query != asked || state.filters != filters) return@update state
            // A list that shifted between its parts brings a book twice; and one that brings nothing new has ended.
            val known = if (page == 0) emptyList() else state.books
            val fresh = found.books.filter { book -> known.none { it.id == book.id } }
            state.copy(books = known + fresh, more = fresh.isNotEmpty() && found.hasMore(shown = known.size))
        }
    }

    /**
     * Asks for the catalogue's parts shown so far anew, as after the status of a book changed under a filter by
     * status: the book is in the list or out of it now, and what follows is asked for from where the rest are.
     */
    private suspend fun showAgain() {
        val asked = _state.value.query
        val filters = _state.value.filters
        val upTo = page
        val books = ArrayList<RemoteBook>()
        var more = false
        var last = 0
        for (part in 0..upTo) {
            val found = orbit.books(filters.copy(text = asked), part)
            val fresh = found.books.filter { book -> books.none { it.id == book.id } }
            more = fresh.isNotEmpty() && found.hasMore(shown = books.size)
            books += fresh
            last = part
            if (!more) break
        }
        if (_state.value.query != asked || _state.value.filters != filters) return
        page = last
        _state.update { it.copy(books = books, more = more) }
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

    /** The session ended while something was asked; the screen asks to sign in again. */
    private fun sessionEnded() = _state.update { it.copy(sessionOver = true, books = emptyList(), more = false, opened = null, trail = emptyList()) }

    /**
     * Asks for the home page's shelves, all of them, or [only] one, as when it is tried again. With [quietly],
     * the books shown stay until the new ones come, as after a book's status changed.
     */
    private fun loadShelves(only: ShelfKind? = null, quietly: Boolean = false) {
        val at = epoch
        val kinds = only?.let(::listOf) ?: ShelfKind.DEFAULT
        if (!quietly) _state.update { it.copy(shelves = it.shelves + kinds.associateWith { ShelfState.Loading }) }
        val job = viewModelScope.launch {
            try {
                val got = if (only != null) mapOf(only to orbit.area.shelf(only)) else orbit.area.shelves(kinds)
                if (at != epoch) return@launch
                _state.update { state -> state.copy(shelves = state.shelves + got.mapValues { (_, shelf) -> shelfStateOf(shelf) }) }
            } catch (e: SignedOutException) {
                ended(at)
            } catch (e: IOException) {
                if (at != epoch) return@launch
                val why = failureOf(e)
                _state.update { state ->
                    state.copy(
                        shelves = state.shelves + kinds.associateWith { kind ->
                            state.shelves[kind]?.takeIf { quietly && it is ShelfState.Shown } ?: ShelfState.Failed(why)
                        },
                    )
                }
            }
        }
        if (only == null) {
            shelving?.cancel()
            shelfRetries.values.forEach(Job::cancel)
            shelfRetries.clear()
            shelving = job
        } else {
            shelfRetries.put(only, job)?.cancel()
        }
    }

    /** Asks the server again for a shelf it could not fill; the rest of the home page stays as it is. */
    fun retryShelf(kind: ShelfKind) = loadShelves(kind)

    /** Asks the server again for all of the home page. */
    fun refreshShelves() = loadShelves()

    /** Asks for the reader's collections and those shared with them. */
    fun loadCollections() {
        val at = epoch
        listingCollections?.cancel()
        _state.update { it.copy(collections = it.collections.copy(loading = true, failure = null)) }
        listingCollections = viewModelScope.launch {
            try {
                val list = orbit.area.collections()
                if (at == epoch) _state.update { it.copy(collections = CollectionsState(list, loading = false, loaded = true)) }
            } catch (e: SignedOutException) {
                ended(at)
            } catch (e: IOException) {
                if (at == epoch) _state.update { it.copy(collections = it.collections.copy(loading = false, failure = failureOf(e))) }
            }
        }
    }

    /** Shows the books of a collection. */
    fun openCollection(collection: RemoteCollection) {
        _state.update { it.copy(collection = CollectionView(collection)) }
        pageOfCollection(0)
    }

    /** Back to the list of collections. */
    fun closeCollection() {
        collectionPaging?.cancel()
        _state.update { it.copy(collection = null) }
    }

    /** Looks the open collection's books up by a phrase, within the collection; an empty one shows them all. */
    fun searchCollection(query: String) {
        val view = _state.value.collection ?: return
        _state.update { it.copy(collection = CollectionView(view.collection, query = query.trim())) }
        pageOfCollection(0)
    }

    /** The part of the open collection's books that follows, when the reader has reached its end. */
    fun moreOfCollection() {
        val view = _state.value.collection ?: return
        if (!view.more || view.loading) return
        // The places of the books shown have moved: what follows them is not where it was.
        if (view.stale) return reloadCollection()
        pageOfCollection(view.pages)
    }

    /** Asks the server again for the open collection's books, as after it could not be reached. */
    fun retryCollection() {
        val view = _state.value.collection ?: return
        when {
            view.stale -> reloadCollection()
            else -> pageOfCollection(if (view.books.isEmpty()) 0 else view.pages)
        }
    }

    private fun pageOfCollection(page: Int) {
        val view = _state.value.collection ?: return
        val at = epoch
        val id = view.collection.id
        val query = view.query
        collectionPaging?.cancel()
        _state.update { it.copy(collection = view.copy(loading = true, failure = null)) }
        // Asked for the collection and the phrase it was asked with, under the reader it was asked for.
        fun same(state: ServerState) = at == epoch && state.collection?.collection?.id == id && state.collection?.query == query
        collectionPaging = viewModelScope.launch {
            try {
                val part = orbit.area.collectionBooks(id, query, page)
                _state.update { state ->
                    val now = state.collection
                    if (!same(state) || now == null) return@update state
                    val known = if (page == 0) emptyList() else now.books
                    val fresh = part.books.filter { book -> known.none { it.id == book.id } }
                    state.copy(
                        collection = now.copy(books = known + fresh, more = part.more, pages = page + 1, loading = false, loaded = true),
                    )
                }
            } catch (e: SignedOutException) {
                ended(at)
            } catch (e: IOException) {
                _state.update { state ->
                    val now = state.collection
                    if (!same(state) || now == null) state else state.copy(collection = now.copy(loading = false, failure = failureOf(e)))
                }
            }
        }
    }

    /**
     * Asks for the open collection's parts shown so far anew, as after a book went into it or out of it: the
     * places of the books after it have moved, and what follows is asked for from where they are now.
     */
    private fun reloadCollection() {
        val view = _state.value.collection ?: return
        val at = epoch
        val id = view.collection.id
        val query = view.query
        val upTo = (view.pages - 1).coerceAtLeast(0)
        collectionPaging?.cancel()
        // Stale until the parts are all asked for anew: a failure meanwhile leaves the next try to begin again.
        _state.update { it.copy(collection = view.copy(loading = true, failure = null, stale = true)) }
        fun same(state: ServerState) = at == epoch && state.collection?.collection?.id == id && state.collection?.query == query
        collectionPaging = viewModelScope.launch {
            try {
                val books = ArrayList<RemoteBook>()
                var more = false
                var pages = 0
                for (page in 0..upTo) {
                    val part = orbit.area.collectionBooks(id, query, page)
                    books += part.books.filter { book -> books.none { it.id == book.id } }
                    more = part.more
                    pages = page + 1
                    if (!more) break
                }
                _state.update { state ->
                    val now = state.collection
                    if (!same(state) || now == null) {
                        state
                    } else {
                        state.copy(collection = now.copy(books = books, more = more, pages = pages, loading = false, loaded = true, stale = false))
                    }
                }
            } catch (e: SignedOutException) {
                ended(at)
            } catch (e: IOException) {
                _state.update { state ->
                    val now = state.collection
                    if (!same(state) || now == null) state else state.copy(collection = now.copy(loading = false, failure = failureOf(e)))
                }
            }
        }
    }

    /**
     * Makes a collection of the reader's, private. With [withOpenBook], from the open book's choice of
     * collections, the book goes into it, and how that went is told there: the name stays until the
     * collection is made, and a collection made without the book in it is kept, with why the book is not.
     */
    fun createCollection(name: String, withOpenBook: Boolean = false) {
        if (name.isBlank()) return
        val book = _state.value.opened?.takeIf { withOpenBook }
        if (book == null) {
            collectionChange { at ->
                val made = orbit.area.createCollection(name)
                if (at == epoch) _state.update { it.withNewCollection(made) }
            }
            return
        }
        val membership = _state.value.membership?.takeIf { it.bookId == book.id } ?: return
        if (membership.creating) return
        val at = epoch
        _state.update { state -> state.withMembership(book.id) { it.copy(creating = true, createFailure = null, failure = null) } }
        tracked {
            try {
                val filled = orbit.area.createCollection(name, withBook = book.id)
                if (at != epoch) return@tracked
                _state.update { state ->
                    state.withNewCollection(filled).withMembership(book.id) {
                        it.copy(creating = false, created = it.created + 1, owned = it.owned + filled, memberOf = it.memberOf + filled.id)
                    }
                }
            } catch (e: NotAddedException) {
                if (at != epoch) return@tracked
                val why = failureOf(e.cause as? IOException ?: e)
                _state.update { state ->
                    state.withNewCollection(e.collection).withMembership(book.id) {
                        it.copy(creating = false, created = it.created + 1, owned = it.owned + e.collection, failure = why)
                    }
                }
            } catch (e: SignedOutException) {
                ended(at)
            } catch (e: IOException) {
                if (at == epoch) _state.update { state -> state.withMembership(book.id) { it.copy(creating = false, createFailure = failureOf(e)) } }
            }
        }
    }

    private fun ServerState.withNewCollection(made: RemoteCollection) =
        copy(collections = collections.copy(list = collections.list.filter { it.id != made.id } + made))

    /** Renames a collection of the reader's; nothing else of it changes. */
    fun renameCollection(collection: RemoteCollection, name: String) {
        if (name.isBlank() || name.trim() == collection.name) return
        collectionChange { at ->
            val renamed = orbit.area.renameCollection(collection.id, name)
            if (at == epoch) _state.update { it.withCollection(renamed) }
        }
    }

    /** Deletes a collection of the reader's. The books in it stay, on the server and in the library. */
    fun deleteCollection(collection: RemoteCollection) = collectionChange { at ->
        orbit.area.deleteCollection(collection.id)
        if (at != epoch) return@collectionChange
        _state.update { state ->
            state.copy(
                collections = state.collections.copy(list = state.collections.list.filter { it.id != collection.id }),
                collection = state.collection?.takeIf { it.collection.id != collection.id },
            )
        }
    }

    fun dismissCollectionFailure() = _state.update { it.copy(collectionFailure = null) }

    private fun collectionChange(change: suspend (Int) -> Unit) {
        if (_state.value.collectionBusy) return
        val at = epoch
        _state.update { it.copy(collectionBusy = true, collectionFailure = null) }
        tracked {
            try {
                change(at)
                if (at == epoch) _state.update { it.copy(collectionBusy = false) }
            } catch (e: SignedOutException) {
                if (at == epoch) _state.update { it.copy(collectionBusy = false) }
                ended(at)
            } catch (e: IOException) {
                if (at == epoch) _state.update { it.copy(collectionBusy = false, collectionFailure = failureOf(e)) }
            }
        }
    }

    /** Opens the page of [book] from a list or a shelf; what the server tells of it is asked for once while the screen lives. */
    fun openBook(book: RemoteBook) {
        _state.update { it.copy(trail = emptyList()) }
        showPage(book)
    }

    /** Opens the page of a book listed beside the open one; the way back returns to the open one. */
    fun openRelated(book: RemoteBook) {
        val from = _state.value.opened ?: return openBook(book)
        if (from.id == book.id) return
        _state.update { it.copy(trail = it.trail + from) }
        showPage(book)
    }

    private fun showPage(book: RemoteBook) {
        val known = details[book.id]
        detailing?.cancel()
        relating.values.forEach(Job::cancel)
        relating.clear()
        memberships?.cancel()
        _state.update {
            it.copy(
                opened = book, detail = known, detailLoading = false, detailFailure = null, related = emptyMap(),
                membership = null, personalFailure = it.personalFailure?.takeIf { failure -> failure.bookId == book.id },
            )
        }
        if (known == null) askDetail(book)
        askRelated(book, known)
    }

    /** Asks the server again for the book whose page is open, as after it could not be reached. */
    fun retryDetail() {
        _state.value.opened?.let(::askDetail)
    }

    /** Back from a book's page: to the page before it, or to the list it was opened from. */
    fun closeBook() {
        val trail = _state.value.trail
        if (trail.isEmpty()) return leaveBooks()
        _state.update { it.copy(trail = trail.dropLast(1)) }
        showPage(trail.last())
    }

    /** Closes every book's page at once, as when the reader goes to read the book. */
    fun leaveBooks() {
        detailing?.cancel()
        relating.values.forEach(Job::cancel)
        relating.clear()
        memberships?.cancel()
        _state.update {
            it.copy(
                opened = null, trail = emptyList(), detail = null, detailFailure = null, detailLoading = false, related = emptyMap(),
                membership = null, personalFailure = null,
            )
        }
    }

    private fun askDetail(book: RemoteBook) {
        val at = epoch
        detailing?.cancel()
        _state.update { it.copy(detailLoading = true, detailFailure = null) }
        detailing = viewModelScope.launch {
            try {
                val detail = orbit.detail(book.id)
                if (at != epoch) return@launch
                details[book.id] = detail
                // The reader may have closed the page, or opened another, while this was on the way.
                _state.update { if (it.opened?.id == book.id) it.copy(detail = detail, detailLoading = false) else it }
                // A book suggested beside another names its series only here.
                if (book.series.isEmpty() && detail.series.isNotEmpty()) askRelated(book, detail, listOf(Related.Series))
            } catch (e: SignedOutException) {
                if (at != epoch) return@launch
                _state.update { it.copy(opened = null, trail = emptyList(), detail = null, detailLoading = false) }
                failed(e)
            } catch (e: IOException) {
                if (at != epoch) return@launch
                _state.update { if (it.opened?.id == book.id) it.copy(detailLoading = false, detailFailure = failureOf(e)) else it }
            }
        }
    }

    /**
     * Asks for the books the server lists beside [book]: like it, by its author, and of its series when it is
     * in one. Asked once per book while the screen lives, and only for the page that is open.
     */
    private fun askRelated(book: RemoteBook, detail: RemoteBookDetail?, kinds: List<Related> = Related.entries) {
        val at = epoch
        for (kind in kinds) {
            if (kind == Related.Series && book.series.isEmpty() && detail?.series.isNullOrEmpty()) continue
            val kept = relatedKept[book.id to kind]
            if (kept != null) {
                _state.update { it.copy(related = it.related + (kind to RelatedState.Shown(kept.books))) }
                continue
            }
            _state.update { it.copy(related = it.related + (kind to RelatedState.Loading)) }
            relating.put(
                kind,
                viewModelScope.launch {
                    val shown = try {
                        val found = orbit.area.related(book.id, kind)
                        if (at == epoch) relatedKept[book.id to kind] = found
                        RelatedState.Shown(found.books)
                    } catch (e: UnsupportedOnServerException) {
                        RelatedState.Unsupported
                    } catch (e: SignedOutException) {
                        ended(at)
                        return@launch
                    } catch (e: IOException) {
                        RelatedState.Failed(failureOf(e))
                    }
                    if (at == epoch) _state.update { if (it.opened?.id == book.id) it.copy(related = it.related + (kind to shown)) else it }
                },
            )?.cancel()
        }
    }

    /** Asks the server again for the books of [kind] beside the open book. */
    fun retryRelated(kind: Related) {
        val book = _state.value.opened ?: return
        askRelated(book, _state.value.detail, listOf(kind))
    }

    /** Sets the reader's status of the open book on the server, in the server's word. */
    fun setStatus(status: String) = personal(Personal.Status, { id -> orbit.area.setStatus(id, status) }) { id, confirmed ->
        update(id, status = confirmed) { it.copy(status = confirmed) }
        // The shelves of books wanted and being read are the server's selections by status.
        loadShelves(quietly = true)
        // A catalogue narrowed by status has the book in it or not by now: its parts shown are asked for again.
        if (_state.value.filters.status.isNotEmpty()) fetch { showAgain() }
    }

    /** Gives the open book stars on the server; null takes them off. */
    fun rate(stars: Int?) = personal(Personal.Rating, { id -> orbit.area.rate(id, stars) }) { id, confirmed ->
        update(id) { it.copy(rating = confirmed) }
    }

    /** Writes what the reader has to say of the open book on the server; empty takes it off. */
    fun writeNote(note: String) = personal(Personal.Note, { id -> orbit.area.writeNote(id, note) }) { id, confirmed ->
        update(id) { it.copy(note = confirmed) }
    }

    fun dismissPersonalFailure() = _state.update { it.copy(personalFailure = null) }

    /**
     * Something the reader keeps of the open book on the server. It is shown as set only once the server
     * confirmed it; until then the book's page says it is on its way, wherever the reader went meanwhile and
     * however often the page is opened, and the same change of the same book is not asked for twice. An answer
     * is only taken for the reader and server it was asked of, and only the asking it answers ends the waiting.
     */
    private fun <T> personal(what: Personal, change: suspend (Long) -> T, confirmed: (Long, T) -> Unit) {
        val book = _state.value.opened ?: return
        val key = book.id to what
        if (key in _state.value.writing) return
        val at = epoch
        val token = ++askings
        _state.update { state ->
            state.copy(writing = state.writing + (key to token), personalFailure = state.personalFailure?.takeIf { it.bookId != book.id })
        }
        fun done(state: ServerState) = if (state.writing[key] == token) state.copy(writing = state.writing - key) else state
        tracked {
            var value: T? = null
            val failure = try {
                value = change(book.id)
                null
            } catch (e: IOException) {
                e
            }
            // Asked of another reader or server: nothing of it is taken, nor does its refusal end this one's session.
            if (at != epoch) return@tracked
            when (failure) {
                null -> {
                    _state.update(::done)
                    @Suppress("UNCHECKED_CAST")
                    confirmed(book.id, value as T)
                }
                is AccountChangedException -> _state.update(::done)
                is SignedOutException -> {
                    _state.update(::done)
                    sessionEnded()
                }
                else -> failure?.let { e -> _state.update { done(it).copy(personalFailure = PersonalFailure(book.id, what, failureOf(e))) } }
            }
        }
    }

    /** A change the server confirmed, wherever the book is shown: its page, the lists, the shelves; a [status] on every line of it. */
    private fun update(bookId: Long, status: String? = null, change: (RemoteBookDetail) -> RemoteBookDetail) {
        val detail = details[bookId]?.let(change)?.also { details[bookId] = it }
        val confirmed = status
        fun RemoteBook.renewed() = if (id == bookId && confirmed != null) copy(status = confirmed) else this
        fun List<RemoteBook>.allRenewed() = map { it.renewed() }
        _state.update { state ->
            state.copy(
                detail = if (state.opened?.id == bookId && detail != null) detail else state.detail,
                opened = state.opened?.renewed(),
                trail = state.trail.allRenewed(),
                books = state.books.allRenewed(),
                shelves = state.shelves.mapValues { (_, shelf) -> if (shelf is ShelfState.Shown) shelf.copy(books = shelf.books.allRenewed()) else shelf },
                collection = state.collection?.let { it.copy(books = it.books.allRenewed()) },
                related = state.related.mapValues { (_, related) -> if (related is RelatedState.Shown) RelatedState.Shown(related.books.allRenewed()) else related },
            )
        }
        for ((key, kept) in relatedKept.entries.toList()) relatedKept[key] = kept.copy(books = kept.books.map { it.renewed() })
    }

    /** Shows the reader's own collections, each said to have the open book in it or not, for them to change. */
    fun chooseCollections() {
        val book = _state.value.opened ?: return
        val at = epoch
        memberships?.cancel()
        _state.update { it.copy(membership = Membership(book.id)) }
        memberships = tracked {
            try {
                val owned = orbit.area.collections().filter { it.isOwner }
                // Nothing more is asked once another reader or server is signed in to.
                if (at != epoch) return@tracked
                val memberOf = orbit.area.collectionsOf(book.id)
                if (at == epoch) _state.update { state -> state.withMembership(book.id) { it.copy(owned = owned, memberOf = memberOf, loading = false) } }
            } catch (e: SignedOutException) {
                ended(at)
            } catch (e: IOException) {
                if (at == epoch) _state.update { state -> state.withMembership(book.id) { it.copy(loading = false, failure = failureOf(e)) } }
            }
        }
    }

    /** Puts the open book into one of the reader's collections, or takes it out; the book stays on the server and here. */
    fun setInCollection(collection: RemoteCollection, inIt: Boolean) {
        val membership = _state.value.membership ?: return
        if (collection.id in membership.busy) return
        val bookId = membership.bookId
        val at = epoch
        _state.update { state -> state.withMembership(bookId) { it.copy(busy = it.busy + collection.id, failure = null) } }
        tracked {
            try {
                val changed = orbit.area.setInCollection(collection.id, bookId, inIt)
                if (at != epoch) return@tracked
                _state.update { state ->
                    state.withCollection(changed).withMembership(bookId) {
                        it.copy(
                            busy = it.busy - collection.id, memberOf = if (inIt) it.memberOf + collection.id else it.memberOf - collection.id,
                            owned = it.owned.map { own -> if (own.id == changed.id) changed else own },
                        )
                    }
                }
                // The collection looked through has a book more or less: its parts shown are asked for again,
                // so that the books after the place it took or left are neither skipped nor shown twice.
                if (_state.value.collection?.collection?.id == collection.id) reloadCollection()
            } catch (e: SignedOutException) {
                ended(at)
            } catch (e: IOException) {
                if (at == epoch) _state.update { state -> state.withMembership(bookId) { it.copy(busy = it.busy - collection.id, failure = failureOf(e)) } }
            }
        }
    }

    fun closeMembership() {
        memberships?.cancel()
        _state.update { it.copy(membership = null) }
    }

    private fun ServerState.withMembership(bookId: Long, change: (Membership) -> Membership) =
        copy(membership = membership?.takeIf { it.bookId == bookId }?.let(change) ?: membership)

    /** A collection as the server last answered with it, wherever it is shown. */
    private fun ServerState.withCollection(changed: RemoteCollection) = copy(
        collections = collections.copy(list = collections.list.map { if (it.id == changed.id) changed else it }),
        collection = collection?.let { if (it.collection.id == changed.id) it.copy(collection = changed) else it },
    )

    /**
     * Downloads a file and puts the book into the library. The first time, the
     * reader is asked where downloaded books should go, and the download
     * waits for the answer. A file in the library already, or on its way, is not downloaded again,
     * wherever else the book is shown.
     */
    fun download(book: RemoteBook, file: RemoteFile) {
        if (_state.value.downloads[file.id] is Fetched.Running || file.id in _state.value.onDevice) return
        // Marked at once: the same book tapped on another shelf meanwhile is not downloaded a second time.
        mark(file, Fetched.Running)
        val at = epoch
        viewModelScope.launch {
            if (!arrivals.asked()) {
                mark(file, null)
                _state.update { it.copy(asking = book to file) }
                return@launch
            }
            // Its own folder: two books may come under one name.
            val landing = File(getApplication<Application>().cacheDir, "server/${UUID.randomUUID()}")
            val from = accounts.current()?.deviceId
            val failure = try {
                val arrived = orbit.download(file, book.title, landing)
                val id = arrivals.keep(arrived, Library.formatOf(arrived.name).ifEmpty { file.format })
                // Known to be the server's without asking it: it has just come from there. Under another reader or
                // server signed in meanwhile it is not marked by the numbers of the one before; it is asked about.
                val still = accounts.current()?.deviceId == from
                if (id != null && still) library.onServer(id, file.bookId, file.id)
                if (id == null) ServerFailure.NotABook else null
            } catch (e: SignedOutException) {
                if (at == epoch) failed(e)
                ServerFailure.Unreachable
            } catch (e: IOException) {
                failureOf(e)
            } finally {
                withContext(Dispatchers.IO) { landing.deleteRecursively() }
            }
            // The book is in the library either way; the screen of another reader is not told of this download.
            if (at == epoch) mark(file, failure?.let(Fetched::Failed))
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
        val at = epoch
        val bytes = try {
            orbit.cover(book)
        } catch (e: IOException) {
            null
        } ?: return null
        return withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            ?.also { if (at == epoch) pictures.put(book.id, it) }
    }

    private fun failureOf(e: IOException): ServerFailure = when (e) {
        is NotAServerException -> ServerFailure.NotAServer
        is WrongPasswordException -> ServerFailure.WrongPassword
        is TooManyAttemptsException -> ServerFailure.TooManyAttempts
        is PasswordLoginDisabledException -> ServerFailure.PasswordLoginDisabled
        is NotAllowedException -> ServerFailure.NotAllowed
        is SignInExpiredException -> ServerFailure.SignInExpired
        is SignInRefusedException -> ServerFailure.SignInRefused
        is UnsupportedOnServerException -> ServerFailure.Unsupported
        is NotOnServerException -> ServerFailure.NotFound
        is NameTakenException -> ServerFailure.NameTaken
        is NotSavedException -> ServerFailure.NotSaved
        else -> ServerFailure.Unreachable
    }
}
