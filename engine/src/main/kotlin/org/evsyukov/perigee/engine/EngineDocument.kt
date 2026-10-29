package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.File

/** A stretch of text between two xpointers; [end] names the first character left out. */
data class TextRange(val start: String, val end: String, val text: String)

/** A table-of-contents line. [depth] is 1 for the top level, [page] is zero-based. */
data class TocEntry(val title: String, val xpointer: String, val depth: Int, val page: Int)

/** A search match with the words around it, for a results list. */
data class SearchHit(val range: TextRange, val before: String, val after: String, val page: Int)

/** A link under a touch. [target] is null when the link leads out of the book. */
data class Link(val href: String, val source: String, val target: String?)

/** What the book says about itself; empty strings for what it leaves out. [authors] are one to a line. */
data class BookInfo(val title: String, val authors: String, val language: String, val series: String)

/**
 * What a book file says about itself, read without opening it for reading.
 * [seriesNumber] is text: books number themselves "2" as well as "2.5".
 * [description] is what the book says it is about, as the file has it: an
 * EPUB may put markup there. [cover] is an image file as the book carries it.
 */
class BookDescription(
    val title: String,
    val authors: List<String>,
    val language: String,
    val series: String,
    val seriesNumber: String,
    val description: String,
    val cover: ByteArray?,
)

/** A range to tint on the page; [color] is 0xRRGGBB. */
data class Highlight(val start: String, val end: String, val color: Int)

/**
 * An open book. The engine is not thread-safe and shares state between books:
 * all calls into this class, for every open document, must be serialized.
 * [BookReader] does that; use it unless the caller owns the engine thread.
 * Positions are crengine xpointers, the same form KOReader exchanges with bookOrbit.
 * Points and rectangles are in pixels of the bitmap the page is drawn into.
 */
class EngineDocument private constructor(
    private var handle: Long,
    private val stylesheet: File,
    private val renderDpi: Int,
) : AutoCloseable {
    private var colors = PageColors.Day

    val pageCount: Int get() = NativeEngine.pageCount(checked())

    /** Zero-based. */
    val currentPage: Int get() = NativeEngine.currentPage(checked())

    fun goToPage(page: Int): Boolean = NativeEngine.goToPage(checked(), page)

    /** Position of the top of the current page. */
    fun xpointer(): String = NativeEngine.xpointer(checked())

    /** Returns false when the xpointer does not resolve in this book. */
    fun goToXPointer(xpointer: String): Boolean = NativeEngine.goToXPointer(checked(), xpointer)

    /** The page an xpointer falls on, or null when it does not resolve in this book. */
    fun pageOf(xpointer: String): Int? = NativeEngine.pageOf(checked(), xpointer).takeIf { it >= 0 }

    /** Position of the top of a page, or null when there is no such page. */
    fun xpointerOfPage(page: Int): String? = NativeEngine.xpointerOfPage(checked(), page)

    /**
     * About how many characters a line holds: the width between the side margins of one page over the
     * average width of a character, measured in the regular font on the book's text from the page shown on.
     * Null when the pages there have too little text to tell, as pictures and title pages do.
     */
    fun charsPerLine(): Int? = NativeEngine.charsPerLine(checked()).takeIf { it > 0 }

    /** Applies [settings], keeping the reading position; with a [width] and [height] the book is laid out at that page size, once. */
    fun apply(settings: EngineSettings, width: Int = 0, height: Int = 0) {
        NativeEngine.applySettings(
            checked(), stylesheet.path, settings.extraCss, settings.fontFace, settings.fontSize,
            settings.fontWeight, settings.lineSpacingPercent, settings.marginHorizontal, settings.marginTop,
            settings.marginBottom,
            settings.hyphenation, settings.footnotes, settings.twoPages,
            settings.colors.text, settings.colors.background, renderDpi, width, height, settings.boldWordStarts,
        )
        NativeEngine.setGamma(settings.gamma)
        colors = settings.colors
    }

    /**
     * Paints the pages in the colours and contrast of [settings]; nothing moves, and the book is not laid out
     * again. The rest of [settings] is left as it was applied.
     */
    fun repaint(settings: EngineSettings) {
        NativeEngine.setColors(checked(), settings.colors.text, settings.colors.background)
        NativeEngine.setGamma(settings.gamma)
        this.colors = settings.colors
    }

    /** Whether the book's text is shaped in full, ligatures and all, as its script needs. */
    internal val fullShaping: Boolean get() = NativeEngine.kerningMode(checked()) == 3

    /** Text between two xpointers, or null when either does not resolve in this book. */
    fun textBetween(start: String, end: String): String? = NativeEngine.textBetween(checked(), start, end)

    /** How far the current page is into the book, 0..1. */
    val progress: Float get() = NativeEngine.positionPermyriad(checked()) / 10000f

    fun info(): BookInfo = NativeEngine.metadata(checked()).let { (title, authors, language, series) ->
        BookInfo(title, Engine.authorNames(authors).joinToString("\n"), language, series)
    }

    /** Table of contents in reading order; empty when the book has none. */
    fun toc(): List<TocEntry> = NativeEngine.toc(checked()).asList().chunked(4) { (title, xpointer, depth, page) ->
        TocEntry(title, xpointer, depth.toInt(), page.toInt())
    }

    /**
     * Whole words between two points of the page shown, in reading order
     * whichever point comes first. The same point twice selects the word under
     * it. Null when there is no text there.
     */
    fun select(x0: Int, y0: Int, x1: Int = x0, y1: Int = y0): TextRange? =
        NativeEngine.selectText(checked(), x0, y0, x1, y1)?.let { (start, end, text) -> TextRange(start, end, text) }

    /** Where a range lies on the page shown, one rectangle per text line; empty when it is on another page. */
    fun rects(start: String, end: String): List<Rect> =
        NativeEngine.rangeRects(checked(), start, end).asList().chunked(4) { (l, t, r, b) -> Rect(l, t, r, b) }

    /** Matches of a phrase in the whole book, in reading order, at most [maxHits]. */
    fun search(pattern: String, caseInsensitive: Boolean = true, maxHits: Int = 500): List<SearchHit> =
        NativeEngine.search(checked(), pattern, caseInsensitive, maxHits, SEARCH_CONTEXT_WORDS).asList()
            .chunked(6) { SearchHit(TextRange(it[0], it[1], it[3]), before = it[2], after = it[4], page = it[5].toInt()) }

    /** The link at a point of the page shown, if any. */
    fun linkAt(x: Int, y: Int): Link? = NativeEngine.linkAt(checked(), x, y)?.let { (href, source, target) ->
        Link(href, source, target.ifEmpty { null })
    }

    /** Draws the current page with the highlights that fall on it; the bitmap size becomes the page size. */
    fun renderPage(bitmap: Bitmap, highlights: List<Highlight> = emptyList()) {
        NativeEngine.renderPage(checked(), bitmap)
        if (highlights.isEmpty()) return
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (highlight in highlights) {
            val rects = rects(highlight.start, highlight.end)
            if (rects.isEmpty()) continue
            if (colors.isDark) {
                // Multiplying into a dark page would leave nothing to see.
                paint.blendMode = BlendMode.SRC_OVER
                paint.color = DARK_PAGE_ALPHA or (highlight.color and 0xFFFFFF)
            } else {
                // Like a marker on paper: the page takes the colour, the text stays as dark as it was.
                paint.blendMode = BlendMode.MULTIPLY
                paint.color = OPAQUE or (highlight.color and 0xFFFFFF)
            }
            for (rect in rects) canvas.drawRect(rect, paint)
        }
    }

    /**
     * Keeps the parsed and laid out book in the engine's cache, so the next
     * opening with the same settings skips both steps. The engine also does
     * this on [close], but an app is rarely closed: it is put away and killed.
     */
    fun saveCache() = NativeEngine.saveCache(checked())

    override fun close() {
        if (handle != 0L) {
            NativeEngine.close(handle)
            handle = 0
        }
    }

    private fun checked(): Long {
        check(handle != 0L) { "document is closed" }
        return handle
    }

    companion object {
        private const val SEARCH_CONTEXT_WORDS = 6
        private const val OPAQUE = 0xFF shl 24
        private const val DARK_PAGE_ALPHA = 0x66 shl 24

        /**
         * @param densityDpi screen density the book is laid out for; CSS lengths
         *   follow the web, one CSS px to one dp.
         */
        fun open(
            context: Context,
            file: File,
            width: Int,
            height: Int,
            settings: EngineSettings = EngineSettings(),
            densityDpi: Int = context.resources.displayMetrics.densityDpi,
            /** False for a second copy of a book that is open already: the engine's cache is the first one's. */
            cacheable: Boolean = true,
        ): EngineDocument {
            Engine.init(context)
            val document = EngineDocument(
                NativeEngine.create(width, height, cacheable), Engine.stylesheetFor(file), densityDpi * 96 / 160,
            )
            document.apply(settings)
            if (!NativeEngine.load(document.handle, file.path)) {
                document.close()
                throw IllegalArgumentException("cannot open ${file.name}")
            }
            return document
        }
    }
}
