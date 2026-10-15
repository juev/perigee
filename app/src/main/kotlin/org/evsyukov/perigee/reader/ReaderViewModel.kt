package org.evsyukov.perigee.reader

import android.app.Application
import android.util.Log
import android.graphics.Point
import android.graphics.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.data.Alignment
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.Bookmark
import org.evsyukov.perigee.data.ColorScheme
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.Quote
import org.evsyukov.perigee.data.ReaderPreferences
import org.evsyukov.perigee.data.ReaderPrefs
import org.evsyukov.perigee.data.RemotePosition
import org.evsyukov.perigee.engine.BookReader
import org.evsyukov.perigee.engine.Engine
import org.evsyukov.perigee.engine.EngineSettings
import org.evsyukov.perigee.engine.Highlight
import org.evsyukov.perigee.engine.Layout
import org.evsyukov.perigee.engine.Page
import org.evsyukov.perigee.engine.PageColors
import org.evsyukov.perigee.engine.SearchHit
import org.evsyukov.perigee.engine.TextAlign
import org.evsyukov.perigee.engine.TextRange
import org.evsyukov.perigee.engine.TocEntry
import org.evsyukov.perigee.server.Server
import org.evsyukov.perigee.server.SyncWork

/** The size of the area the page is shown in, in pixels, and the screen density. */
data class Viewport(
    val width: Int, val height: Int, val density: Float, val densityDpi: Int,
    /** The strip above the area that the page keeps clear of, as under a camera cutout, in pixels. */
    val clearAbove: Int = 0,
)

/** Text the reader is selecting, with where it lies on the page shown. */
data class Selection(val range: TextRange, val rects: List<Rect>, val anchorX: Int, val anchorY: Int) {
    /** A point on the first glyph selected: the selection stretched from it keeps its start. */
    val startPoint: Point? get() = rects.firstOrNull()?.let { Point(it.left, it.centerY()) }

    /** A point on the last glyph selected: the selection stretched from it keeps its end. */
    val endPoint: Point? get() = rects.lastOrNull()?.let { Point(it.right - 1, it.centerY()) }
}

/** A quote the reader touched, with where it lies on the page shown. */
data class QuoteTouch(val quote: Quote, val rects: List<Rect>)

data class SearchState(val query: String = "", val running: Boolean = false, val hits: List<SearchHit>? = null)

/** What a touch on the page turned out to be, for the screen to act on. */
sealed interface TapResult {
    /** The touch was used up: a link was followed, a selection dropped. */
    data object Handled : TapResult

    /** A link that leads out of the book. */
    data class ExternalLink(val url: String) : TapResult

    /** Nothing under the touch; the screen decides by where it was. */
    data object Nothing : TapResult
}

data class ReaderState(
    val book: Book? = null,
    val failed: Boolean = false,
    /** The book is being opened or laid out again; touches wait for nothing and are dropped. */
    val busy: Boolean = false,
    val page: Page? = null,
    /** Which way the page shown was reached, for the turn animation: 1 forward, -1 back, 0 a jump. */
    val turned: Int = 0,
    val toc: List<TocEntry> = emptyList(),
    val fontFaces: List<String> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val quotes: List<Quote> = emptyList(),
    /** The bookmark on the page shown, if it has one. */
    val pageBookmark: Bookmark? = null,
    val selection: Selection? = null,
    val quoteTouch: QuoteTouch? = null,
    val canGoBack: Boolean = false,
    val search: SearchState = SearchState(),
    /** A place the reader got to somewhere else, later than here, and has not decided about. */
    val offer: RemotePosition? = null,
) {
    /** The contents entry the page shown belongs to. */
    val chapter: TocEntry? get() = page?.let { shown -> toc.lastOrNull { it.page <= shown.index } }
}

/**
 * One book on the screen. Outlives rotations: the screen comes back to the
 * same open book and only tells its new size.
 */
class ReaderViewModel(
    application: Application,
    private val library: Library,
    /** Asks the server about a book just opened, see [Server.bookOpened]. */
    private val bookOpened: suspend (bookId: String) -> Unit,
) : AndroidViewModel(application) {

    @Suppress("unused") // The constructor the system makes the model with.
    constructor(application: Application) : this(application, Library.get(application), { Server.bookOpened(application, it) })

    private val preferences = ReaderPreferences.get(application)

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val _prefs = MutableStateFlow(ReaderPrefs())
    val prefs: StateFlow<ReaderPrefs> = _prefs.asStateFlow()

    private var reader: BookReader? = null
    private var file: File? = null
    private var viewport: Viewport? = null
    private var applied: Triple<Int, Int, EngineSettings>? = null

    // Opening, re-laying out and closing must not overlap one another.
    private val layout = Mutex()
    private val viewports = KnownViewports.of(application)
    private var spareJob: Job? = null
    private val history = ArrayDeque<String>()
    private var searching: Job? = null
    private var turnsWaiting = 0

    // A finger moves faster than the engine answers: only its latest place matters.
    // [from] is where the stretch starts when it is not where the selection was started.
    private data class SelectRequest(val x: Int, val y: Int, val extend: Boolean, val from: Point? = null)
    private val selectRequests = Channel<SelectRequest>(Channel.CONFLATED)
    private var watching: Job? = null

    /** The page the reader is looking at and since when, for the time spent on it. */
    private data class Looking(val bookId: String, val page: Int, val pageCount: Int, val since: Long)
    private var looking: Looking? = null

    init {
        viewModelScope.launch {
            // Not collectLatest: a book being opened must not be dropped halfway
            // because another preference arrived.
            preferences.prefs.collect { stored ->
                // A copy about to be laid out with the settings before must not hold up the new layout.
                if (stored != _prefs.value) spareJob?.cancel()
                _prefs.value = stored
                sync()
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(fontFaces = Engine.fontFaces(application)) }
        }
        viewModelScope.launch {
            for (request in selectRequests) select(request)
        }
    }

    /** Shows [file]; the same file again changes nothing. */
    fun open(file: File) {
        if (this.file == file) return
        this.file = file
        viewModelScope.launch {
            closeBook()
            sync()
        }
    }

    /** Puts the book away; the screen is free to show another. */
    fun close() {
        file = null
        viewModelScope.launch {
            closeBook()
            SyncWork.soon(getApplication())
        }
    }

    /**
     * The reader goes to the place they got to elsewhere. It is a jump like
     * any other: Back returns to where they were.
     */
    fun acceptOffer() = act { open ->
        val offer = _state.value.offer ?: return@act
        val shown = _state.value.page ?: return@act
        // A place that cannot be found in this copy of the book is gone to by how far into the book it is.
        val page = offer.xpointer?.let { open.goTo(it) } ?: open.goToPage((offer.progress * (shown.pageCount - 1)).roundToInt())
        if (page.index != shown.index) jumpedFrom(shown.xpointer)
        show(page)
        library.settleOffer(offer.bookId)
    }

    /** The reader stays where they are; the place from elsewhere is not offered again. */
    fun declineOffer() {
        val offer = _state.value.offer ?: return
        viewModelScope.launch { library.settleOffer(offer.bookId) }
    }

    /** The app is put away: the page is looked at no more, and what was read is to be sent. */
    fun paused() {
        act { open -> open.saveCache() }
        viewModelScope.launch {
            stopLooking()
            SyncWork.soon(getApplication())
        }
    }

    /** The app is back: the page shown is looked at again. */
    fun resumed() {
        val book = _state.value.book ?: return
        val page = _state.value.page ?: return
        if (looking == null) looking = Looking(book.id, page.index, page.pageCount, clock())
    }

    /**
     * Counts the time spent on the page that is left. A page glanced at on the
     * way to another is not read, and one left open for long was not read all
     * that time; the bounds are those KOReader counts by.
     */
    private suspend fun stopLooking(now: Long = clock()) {
        val was = looking ?: return
        looking = null
        val seconds = ((now - was.since) / 1000).toInt()
        if (seconds < MIN_PAGE_SECONDS) return
        library.addPageRead(was.bookId, was.page + 1, was.pageCount, was.since / 1000, seconds.coerceAtMost(MAX_PAGE_SECONDS))
    }

    private suspend fun closeBook() = layout.withLock {
        stopLooking()
        watching?.cancel()
        searching?.cancel()
        reader?.close()
        reader = null
        applied = null
        history.clear()
        _state.update { ReaderState(fontFaces = it.fontFaces) }
    }

    /** The screen reports the area it has for the page. */
    /**
     * Whether a rotation brought the screen to the very area it settled at
     * before in that orientation: the book may be laid out for it already, and
     * there is nothing to wait for. A size changing within one orientation, as
     * with a split screen or a keyboard, passes through others on the way.
     */
    fun isSettled(viewport: Viewport): Boolean {
        val shown = this.viewport ?: return false
        return (shown.width > shown.height) != (viewport.width > viewport.height) && viewports.isKnown(viewport)
    }

    fun setViewport(viewport: Viewport) {
        Log.d(TAG, "page area $viewport${if (this.viewport == viewport) ", as before" else ""}")
        if (this.viewport == viewport) return
        this.viewport = viewport
        // A copy about to be laid out for a size no longer next must not hold up the page for this one.
        spareJob?.cancel()
        viewModelScope.launch { sync() }
    }

    fun updatePrefs(change: (ReaderPrefs) -> ReaderPrefs) {
        viewModelScope.launch { preferences.update(change) }
    }

    /** One screen forward or back. */
    fun turn(screens: Int) {
        // Touches made while the book is laid out, or faster than pages come,
        // must not pile up and then fire all at once.
        val open = reader ?: return
        if (_state.value.busy || turnsWaiting >= MAX_TURNS_WAITING) return
        turnsWaiting++
        // The reader is reading: the second copy waits for the next pause.
        prepareSpareLater(SPARE_AFTER_TURN_MILLIS)
        viewModelScope.launch {
            try {
                show(open.turn(screens), turned = screens.coerceIn(-1, 1))
            } finally {
                turnsWaiting--
            }
        }
    }

    /** Jumps to a place, remembering where the reader came from. */
    fun goTo(xpointer: String) = act { open ->
        val from = _state.value.page?.xpointer
        val page = open.goTo(xpointer) ?: return@act
        if (from != null && page.index != _state.value.page?.index) jumpedFrom(from)
        show(page)
    }

    /** Jumps to a share of the book, 0..1, as from the progress line. */
    fun goToProgress(share: Float) = act { open ->
        val shown = _state.value.page ?: return@act
        val target = (share * (shown.pageCount - 1)).toInt()
        if (target == shown.index) return@act
        jumpedFrom(shown.xpointer)
        show(open.goToPage(target))
    }

    /** Returns to where the last jump was made from. */
    fun goBack() = act { open ->
        val from = history.removeLastOrNull() ?: return@act
        saveHistory()
        show(open.goTo(from) ?: return@act)
    }

    /** Remembers a place a jump is made from, here and in the library, for Back after a restart too. */
    private suspend fun jumpedFrom(xpointer: String) {
        history.addLast(xpointer)
        while (history.size > Library.MAX_JUMPS) history.removeFirst()
        saveHistory()
    }

    private suspend fun saveHistory() {
        val book = _state.value.book ?: return
        library.saveJumps(book.id, history.toList())
    }

    /**
     * Sorts out a touch at a point of the page: a link, a quote, a selection to
     * drop, or nothing. Answers on the main thread whatever dispatcher the caller
     * runs on: the screen changes its state with the answer.
     */
    suspend fun tap(x: Int, y: Int): TapResult = withContext(Dispatchers.Main.immediate) { sortOut(x, y) }

    private suspend fun sortOut(x: Int, y: Int): TapResult {
        val open = reader ?: return TapResult.Handled
        if (_state.value.busy) return TapResult.Handled
        val current = _state.value
        if (current.selection != null || current.quoteTouch != null) {
            _state.update { it.copy(selection = null, quoteTouch = null) }
            return TapResult.Handled
        }
        val link = open.read { it.linkAt(x, y) }
        if (link != null) {
            val target = link.target ?: return TapResult.ExternalLink(link.href)
            goTo(target)
            return TapResult.Handled
        }
        val touched = open.read { document ->
            current.quotes.firstNotNullOfOrNull { quote ->
                val rects = document.rects(quote.start, quote.end)
                if (rects.any { it.contains(x, y) }) QuoteTouch(quote, rects) else null
            }
        }
        if (touched != null) {
            _state.update { it.copy(quoteTouch = touched) }
            return TapResult.Handled
        }
        return TapResult.Nothing
    }

    /** Starts a selection with the word at a point, or stretches it from where it started to a new point. */
    fun select(x: Int, y: Int, extend: Boolean) {
        selectRequests.trySend(SelectRequest(x, y, extend))
    }

    /**
     * Stretches the selection from a point to another, as a handle at one of its
     * ends is dragged: [from] is a point on the end that stays.
     */
    fun stretch(from: Point, x: Int, y: Int) {
        selectRequests.trySend(SelectRequest(x, y, extend = true, from = from))
    }

    private suspend fun select(request: SelectRequest) {
        val open = reader ?: return
        val (x, y, extend) = request
        val anchor = _state.value.selection?.takeIf { extend }
        val fromX = request.from?.x ?: anchor?.anchorX ?: x
        val fromY = request.from?.y ?: anchor?.anchorY ?: y
        val selection = open.read { document ->
            document.select(fromX, fromY, x, y)?.let { range ->
                Selection(range, document.rects(range.start, range.end), fromX, fromY)
            }
        }
        // Dragging over a gap keeps what was selected so far.
        if (selection != null || !extend) _state.update { it.copy(selection = selection, quoteTouch = null) }
    }

    fun clearSelection() = _state.update { it.copy(selection = null, quoteTouch = null) }

    /** Turns the selection into a quote of the given colour. */
    fun quoteSelection(color: Int, note: String? = null) = act {
        val current = _state.value
        val book = current.book ?: return@act
        val range = current.selection?.range ?: return@act
        library.addQuote(book.id, range.start, range.end, range.text, color, current.chapter?.title, note)
        _state.update { it.copy(selection = null) }
    }

    fun updateQuote(quote: Quote, note: String?, color: Int) = act {
        library.updateQuote(quote.id, note, color)
        _state.update { it.copy(quoteTouch = null) }
    }

    fun removeQuote(quote: Quote) = act {
        library.removeQuote(quote.id)
        _state.update { it.copy(quoteTouch = null) }
    }

    /** Gives the quote a star, or takes it off. */
    fun toggleStar(quote: Quote) = act {
        library.starQuote(quote.id, !quote.starred)
        _state.update { it.copy(quoteTouch = null) }
    }

    /** Marks the page shown, or takes its mark away. */
    fun toggleBookmark() = act { open ->
        val current = _state.value
        val book = current.book ?: return@act
        val page = current.page ?: return@act
        val existing = current.pageBookmark
        if (existing != null) {
            library.removeBookmark(existing.id)
        } else {
            val text = open.read { document ->
                val next = document.xpointerOfPage(page.index + 1)
                next?.let { document.textBetween(page.xpointer, it) }.orEmpty()
            }
            library.addBookmark(book.id, page.xpointer, text.trim().take(BOOKMARK_TEXT_LENGTH), current.chapter?.title)
        }
    }

    fun removeBookmark(bookmark: Bookmark) = act { library.removeBookmark(bookmark.id) }

    /** Looks a phrase up in the whole book; a new search drops the one running. */
    fun search(query: String) {
        searching?.cancel()
        val open = reader
        if (query.isBlank() || open == null) {
            _state.update { it.copy(search = SearchState()) }
            return
        }
        _state.update { it.copy(search = SearchState(query, running = true)) }
        searching = viewModelScope.launch {
            val hits = open.read { it.search(query.trim()) }
            _state.update { it.copy(search = SearchState(query, running = false, hits = hits)) }
        }
    }

    private fun act(block: suspend (BookReader) -> Unit) {
        val open = reader ?: return
        viewModelScope.launch { block(open) }
    }

    /**
     * Brings the engine in line with what was asked for last: the file, the
     * viewport, the preferences. Requests that arrive while a book is opening
     * wait their turn and then see the book that is open.
     */
    private suspend fun sync() = layout.withLock {
        val file = file ?: return
        val viewport = viewport ?: return
        val open = reader
        try {
            if (open == null) openBook(file, viewport) else relayOut(open, viewport)
            if (reader != null) viewports.remember(viewport)
        } finally {
            _state.update { it.copy(busy = false) }
        }
        // Just opened or turned, the reader looks for their place before turning a page: the copy for
        // the screen turned back is laid out now, as a reader who turns the screen may soon turn it back.
        prepareSpareLater(SPARE_AFTER_LAYOUT_MILLIS)
    }

    /**
     * A second copy of the book is laid out for the screen turned the other
     * way, so that turning it shows the book at once. Laying it out holds the
     * engine for as long as a rotation would: it waits [after] milliseconds,
     * and starts waiting anew when the reader turns a page meanwhile.
     */
    private fun prepareSpareLater(after: Long) {
        spareJob?.cancel()
        spareJob = viewModelScope.launch {
            delay(after)
            val open = reader ?: return@launch
            val shown = viewport ?: return@launch
            val other = viewports.otherThan(shown) ?: return@launch
            val (width, height, settings) = laidOut(_prefs.value, other)
            open.prepareSpare(Layout(settings, width, height))
        }
    }

    private suspend fun openBook(file: File, viewport: Viewport) {
        _state.update { it.copy(busy = true) }
        try {
            val prefs = preferences.prefs.first().also { _prefs.value = it }
            val target = laidOut(prefs, viewport)
            val (width, height, settings) = target
            val open = BookReader.open(
                getApplication(), file, width, height, settings, densityDpi = viewport.densityDpi,
            )
            reader = open
            applied = target
            val info = open.read { it.info() }
            val book = library.open(file, info.title, info.authors)
            book.xpointer?.let { open.goTo(it) }
            history.addAll(book.jumpedFrom)
            val toc = open.read { it.toc() }
            _state.update { it.copy(book = book, toc = toc, failed = false) }
            watch(book)
            show(open.current())
            // Not waited for: the reader reads meanwhile, and is told if there is a later place.
            viewModelScope.launch { bookOpened(book.id) }
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(failed = true) }
        }
    }

    private suspend fun relayOut(open: BookReader, viewport: Viewport) {
        val target = laidOut(_prefs.value, viewport)
        // Most preferences are about controls and leave the pages as they are.
        if (target == applied) return
        _state.update { it.copy(busy = true) }
        val started = System.nanoTime()
        val (width, height, settings) = target
        val page = open.apply(settings, width, height)
        applied = target
        _state.update { it.copy(selection = null, quoteTouch = null) }
        show(page)
        // The page is there: touches count from now on, not once the contents are counted too.
        _state.update { it.copy(busy = false) }
        Log.d(TAG, "new layout shown in ${(System.nanoTime() - started) / 1_000_000} ms")
        // Page numbers of the contents moved with the layout; the page is not kept waiting for them.
        val toc = open.read { it.toc() }
        _state.update { it.copy(toc = toc) }
    }

    /** Keeps the lists of the book's marks current and the quotes painted on the pages. */
    private fun watch(book: Book) {
        watching?.cancel()
        watching = viewModelScope.launch {
            launch {
                library.bookmarks(book.id).collectLatest { bookmarks ->
                    _state.update { it.copy(bookmarks = bookmarks) }
                    _state.value.page?.let { markBookmark(it) }
                }
            }
            launch {
                library.offerOf(book.id).collect { offer -> _state.update { it.copy(offer = offer) } }
            }
            launch {
                library.quotes(book.id).collectLatest { quotes ->
                    _state.update { it.copy(quotes = quotes) }
                    val open = reader ?: return@collectLatest
                    show(open.setHighlights(quotes.map { Highlight(it.start, it.end, it.color) }), _state.value.turned)
                }
            }
        }
    }

    private suspend fun show(page: Page, turned: Int = 0) {
        // The page is looked at from the moment it is shown, not from when the place is written down.
        val now = clock()
        // The page and its bookmark appear together: the ribbon of the page
        // before must not stay on the new one for a moment.
        val bookmark = bookmarkOn(page)
        _state.update {
            it.copy(
                page = page, turned = turned, pageBookmark = bookmark, canGoBack = history.isNotEmpty(),
                selection = null, quoteTouch = null,
            )
        }
        val open = reader ?: return
        val book = _state.value.book ?: return
        library.savePosition(book.id, open.position, page.progress)
        val was = looking
        if (was == null || was.page != page.index || was.pageCount != page.pageCount) {
            stopLooking(now)
            looking = Looking(book.id, page.index, page.pageCount, now)
        }
    }

    private suspend fun markBookmark(page: Page) {
        val bookmark = bookmarkOn(page)
        _state.update { if (it.page === page) it.copy(pageBookmark = bookmark) else it }
    }

    private suspend fun bookmarkOn(page: Page): Bookmark? {
        val open = reader ?: return null
        val bookmarks = _state.value.bookmarks
        if (bookmarks.isEmpty()) return null
        val screen = page.index until page.index + screenPages()
        return open.read { document -> bookmarks.firstOrNull { document.pageOf(it.xpointer) in screen } }
    }

    private fun screenPages(): Int = viewport?.let { if (twoUp(_prefs.value, it)) 2 else 1 } ?: 1

    override fun onCleared() {
        // Closing waits for the engine thread, which outlives this model.
        reader?.let { open -> GlobalScope.launch { open.close() } }
    }

    companion object {
        private const val BOOKMARK_TEXT_LENGTH = 120
        private const val MIN_PAGE_SECONDS = 5
        private const val MAX_PAGE_SECONDS = 120

        /** The time pages are timed by; the tests set their own. */
        internal var clock: () -> Long = System::currentTimeMillis

        // One turn in flight and one behind it: enough for a quick double tap.
        private const val MAX_TURNS_WAITING = 2

        /** Two pages side by side only make sense on a screen wider than it is tall. */
        fun twoUp(prefs: ReaderPrefs, viewport: Viewport) =
            prefs.twoPagesInLandscape && viewport.width > viewport.height

        private const val TAG = "Perigee.Reader"

        // How long after a layout, and after a turned page, the second copy of the book is laid out.
        private const val SPARE_AFTER_LAYOUT_MILLIS = 300L
        private const val SPARE_AFTER_TURN_MILLIS = 3_000L

        // Margins in dp: at the sides, at the top and at the bottom of the screen; narrow and wide. They are
        // those measured in ReadEra on a 560 dpi phone: its wide margins widen the sides (30 px to 120 px) and
        // leave the top and bottom nearly as they were. Over seven pages its last line ended 75 to 161 px from
        // the bottom edge; with 12 dp there the last line here ended 49 to 141 px from it.
        private const val SIDE_NARROW = 8
        private const val SIDE_WIDE = 34
        private const val TOP_NARROW = 12
        private const val TOP_WIDE = 16
        private const val BOTTOM_NARROW = 20
        private const val BOTTOM_WIDE = 23
        private const val BELOW_STRIP_NARROW = 1
        private const val BELOW_STRIP_WIDE = 2

        /**
         * Page size and engine settings for the reader's preferences.
         *
         * The engine makes the gap between two pages as wide as one side margin,
         * which reads as too tight. With two pages the book is therefore laid out
         * with doubled side margins on a canvas wider than the screen, and the
         * screen crops the surplus off both edges: the gap doubles while the
         * outer margins stay as chosen.
         */
        fun laidOut(prefs: ReaderPrefs, viewport: Viewport): Triple<Int, Int, EngineSettings> {
            fun px(dp: Int) = (dp * viewport.density).toInt()
            val twoPages = twoUp(prefs, viewport)
            val side = px(if (prefs.pageMargins) SIDE_WIDE else SIDE_NARROW)
            val top = px(if (prefs.pageMargins) TOP_WIDE else TOP_NARROW)
            val overdraw = if (twoPages) side else 0
            val settings = EngineSettings(
                fontFace = prefs.fontFace,
                fontSize = px(prefs.fontSize),
                fontWeight = prefs.fontWeight,
                lineSpacingPercent = prefs.lineSpacingPercent,
                marginHorizontal = side + overdraw,
                // A strip left clear above the page, as under a camera cutout, is margin already: only what
                // it falls short of is added, and a little so that the text does not sit on its very edge.
                marginTop = maxOf(top - viewport.clearAbove, px(if (prefs.pageMargins) BELOW_STRIP_WIDE else BELOW_STRIP_NARROW)),
                marginBottom = px(if (prefs.pageMargins) BOTTOM_WIDE else BOTTOM_NARROW),
                textAlign = when (prefs.alignment) {
                    Alignment.Justify -> TextAlign.Justify
                    Alignment.Left -> TextAlign.Left
                    Alignment.Center -> TextAlign.Center
                    Alignment.Right -> TextAlign.Right
                },
                hyphenation = prefs.hyphenation,
                paragraphSpacingPercent = prefs.paragraphSpacingPercent,
                paragraphIndent = prefs.paragraphIndent,
                colors = colorsOf(prefs.colorScheme),
                twoPages = twoPages,
            )
            return Triple(viewport.width + 2 * overdraw, viewport.height, settings)
        }

        fun colorsOf(scheme: ColorScheme): PageColors = when (scheme) {
            ColorScheme.Day -> PageColors.Day
            ColorScheme.Night -> PageColors.Night
            ColorScheme.Sepia -> PageColors.Sepia
        }
    }
}
