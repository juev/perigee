package org.evsyukov.perigee.reader

import android.app.Application
import android.graphics.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
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
import org.evsyukov.perigee.engine.BookReader
import org.evsyukov.perigee.engine.Engine
import org.evsyukov.perigee.engine.EngineSettings
import org.evsyukov.perigee.engine.Highlight
import org.evsyukov.perigee.engine.Page
import org.evsyukov.perigee.engine.PageColors
import org.evsyukov.perigee.engine.SearchHit
import org.evsyukov.perigee.engine.TextAlign
import org.evsyukov.perigee.engine.TextRange
import org.evsyukov.perigee.engine.TocEntry

/** The size of the area the page is shown in, in pixels, and the screen density. */
data class Viewport(val width: Int, val height: Int, val density: Float, val densityDpi: Int)

/** Text the reader is selecting, with where it lies on the page shown. */
data class Selection(val range: TextRange, val rects: List<Rect>, val anchorX: Int, val anchorY: Int)

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
) {
    /** The contents entry the page shown belongs to. */
    val chapter: TocEntry? get() = page?.let { shown -> toc.lastOrNull { it.page <= shown.index } }
}

/**
 * One book on the screen. Outlives rotations: the screen comes back to the
 * same open book and only tells its new size.
 */
class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val library = Library.get(application)
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
    private val history = ArrayDeque<String>()
    private var searching: Job? = null
    private var turnsWaiting = 0

    // A finger moves faster than the engine answers: only its latest place matters.
    private data class SelectRequest(val x: Int, val y: Int, val extend: Boolean)
    private val selectRequests = Channel<SelectRequest>(Channel.CONFLATED)
    private var watching: Job? = null

    init {
        viewModelScope.launch {
            // Not collectLatest: a book being opened must not be dropped halfway
            // because another preference arrived.
            preferences.prefs.collect { stored ->
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
        viewModelScope.launch { closeBook() }
    }

    private suspend fun closeBook() = layout.withLock {
        watching?.cancel()
        searching?.cancel()
        reader?.close()
        reader = null
        applied = null
        history.clear()
        _state.update { ReaderState(fontFaces = it.fontFaces) }
    }

    /** The screen reports the area it has for the page. */
    fun setViewport(viewport: Viewport) {
        if (this.viewport == viewport) return
        this.viewport = viewport
        viewModelScope.launch { sync() }
    }

    /** The app is going to the background: a good moment to keep the laid out book for next time. */
    fun saveCache() = act { open -> open.saveCache() }

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
        if (from != null && page.index != _state.value.page?.index) history.addLast(from)
        show(page)
    }

    /** Jumps to a share of the book, 0..1, as from the progress line. */
    fun goToProgress(share: Float) = act { open ->
        val shown = _state.value.page ?: return@act
        val target = (share * (shown.pageCount - 1)).toInt()
        if (target == shown.index) return@act
        history.addLast(shown.xpointer)
        show(open.goToPage(target))
    }

    /** Returns to where the last jump was made from. */
    fun goBack() = act { open ->
        val from = history.removeLastOrNull() ?: return@act
        show(open.goTo(from) ?: return@act)
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

    private suspend fun select(request: SelectRequest) {
        val open = reader ?: return
        val (x, y, extend) = request
        val anchor = _state.value.selection?.takeIf { extend }
        val fromX = anchor?.anchorX ?: x
        val fromY = anchor?.anchorY ?: y
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
        } finally {
            _state.update { it.copy(busy = false) }
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
            val book = library.open(file, info.title.ifBlank { file.nameWithoutExtension }, info.authors)
            book.xpointer?.let { open.goTo(it) }
            val toc = open.read { it.toc() }
            _state.update { it.copy(book = book, toc = toc, failed = false) }
            watch(book)
            show(open.current())
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(failed = true) }
        }
    }

    private suspend fun relayOut(open: BookReader, viewport: Viewport) {
        val target = laidOut(_prefs.value, viewport)
        // Most preferences are about controls and leave the pages as they are.
        if (target == applied) return
        _state.update { it.copy(busy = true) }
        val (width, height, settings) = target
        val page = open.apply(settings, width, height)
        applied = target
        // Page numbers of the contents moved with the layout.
        val toc = open.read { it.toc() }
        _state.update { it.copy(toc = toc, selection = null, quoteTouch = null) }
        show(page)
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
                library.quotes(book.id).collectLatest { quotes ->
                    _state.update { it.copy(quotes = quotes) }
                    val open = reader ?: return@collectLatest
                    show(open.setHighlights(quotes.map { Highlight(it.start, it.end, it.color) }), _state.value.turned)
                }
            }
        }
    }

    private suspend fun show(page: Page, turned: Int = 0) {
        _state.update {
            it.copy(page = page, turned = turned, canGoBack = history.isNotEmpty(), selection = null, quoteTouch = null)
        }
        markBookmark(page)
        val open = reader ?: return
        _state.value.book?.let { library.savePosition(it.id, open.position, page.progress) }
    }

    private suspend fun markBookmark(page: Page) {
        val open = reader ?: return
        val bookmarks = _state.value.bookmarks
        val screen = page.index until page.index + screenPages()
        val onPage = open.read { document -> bookmarks.firstOrNull { document.pageOf(it.xpointer) in screen } }
        _state.update { if (it.page === page) it.copy(pageBookmark = onPage) else it }
    }

    private fun screenPages(): Int = viewport?.let { if (twoUp(_prefs.value, it)) 2 else 1 } ?: 1

    override fun onCleared() {
        // Closing waits for the engine thread, which outlives this model.
        reader?.let { open -> GlobalScope.launch { open.close() } }
    }

    companion object {
        private const val BOOKMARK_TEXT_LENGTH = 120

        // One turn in flight and one behind it: enough for a quick double tap.
        private const val MAX_TURNS_WAITING = 2

        /** Two pages side by side only make sense on a screen wider than it is tall. */
        fun twoUp(prefs: ReaderPrefs, viewport: Viewport) =
            prefs.twoPagesInLandscape && viewport.width > viewport.height

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
            val side = px(if (prefs.pageMargins) 24 else 8)
            val overdraw = if (twoPages) side else 0
            val settings = EngineSettings(
                fontFace = prefs.fontFace,
                fontSize = px(prefs.fontSize),
                fontWeight = prefs.fontWeight,
                lineSpacingPercent = prefs.lineSpacingPercent,
                marginHorizontal = side + overdraw,
                marginVertical = px(if (prefs.pageMargins) 28 else 12),
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
