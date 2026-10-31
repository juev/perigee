package org.evsyukov.perigee.engine

import android.graphics.Bitmap

internal object NativeEngine {
    init {
        System.loadLibrary("perigee_engine")
    }

    /** DOM version crengine assigns to newly opened books; xpointers depend on it. */
    external fun domVersion(): Int

    external fun init(hyphDir: String, cacheDir: String, cacheBytes: Long)

    /** Files the engine cannot read are skipped. */
    external fun registerFonts(fontFiles: Array<String>)

    external fun fontFaces(): Array<String>

    /** Families to take a glyph from when the font of the text has none, separated by '|', in order. */
    external fun setFallbackFontFaces(faces: String): Boolean

    external fun fallbackFontFaces(): String

    /** Creates an empty view; load a book into it after applySettings. */
    /** [cacheable] false keeps the view off the engine's cache, for a second copy of a book that is open already. */
    external fun create(width: Int, height: Int, cacheable: Boolean): Long

    /** Text and page colours of the pages drawn from now on; the book is not laid out again. */
    external fun setColors(handle: Long, textColor: Int, backgroundColor: Int)

    /** Gamma of the glyphs drawn from now on, for every book: the engine keeps one for all. */
    external fun setGamma(gamma: Double)

    external fun applySettings(
        handle: Long, cssFile: String, extraCss: String, fontFace: String, fontSize: Int, fontWeight: Int,
        lineSpacingPercent: Int, marginHorizontal: Int, marginTop: Int, marginBottom: Int,
        hyphenation: Boolean, footnotes: Boolean, twoPages: Boolean, textColor: Int, backgroundColor: Int,
        renderDpi: Int,
        /** The page size to lay the book out at; 0 keeps the size it has. */
        width: Int, height: Int,
        boldWordStarts: Boolean,
        antialiasing: Boolean, hinting: Int, ligatures: Boolean, fractionalPositioning: Int,
        monospaceSizePercent: Int, embeddedFonts: Boolean,
    )

    /** Returns false when the file cannot be opened. */
    external fun load(handle: Long, path: String): Boolean

    /** How the loaded book's text is shaped: 2 is HarfBuzz without ligatures, 3 is HarfBuzz with everything. */
    external fun kerningMode(handle: Long): Int

    /** The hinting mode the font manager draws with, for every book: 0 none, 1 the font's own, 2 autohint. */
    external fun hintingMode(): Int

    /** Writes the parsed and laid out book to the engine's cache; small files are skipped. */
    external fun saveCache(handle: Long)

    external fun close(handle: Long)
    external fun pageCount(handle: Long): Int
    external fun currentPage(handle: Long): Int
    external fun goToPage(handle: Long, page: Int): Boolean
    external fun xpointer(handle: Long): String
    external fun goToXPointer(handle: Long, xpointer: String): Boolean
    external fun textBetween(handle: Long, start: String, end: String): String?

    /** Position in the book in 1/10000 units. */
    external fun positionPermyriad(handle: Long): Int

    /** Title, authors one to a line, language, series; "" for what the book does not say. */
    external fun metadata(handle: Long): Array<String>

    /**
     * Reads what a book file says about itself without laying it out. Two
     * items: title, authors one to a line, language, series and its number as
     * strings, then the cover image file as bytes or null. Null when the
     * engine cannot read the file.
     */
    external fun describe(fd: Int, name: String): Array<Any?>?

    /** -1 when the xpointer does not resolve. */
    external fun pageOf(handle: Long, xpointer: String): Int
    external fun xpointerOfPage(handle: Long, page: Int): String?

    /** Characters of the book's text that fit a line from the page shown on; 0 when there is too little text to tell. */
    external fun charsPerLine(handle: Long): Int

    /** Four strings per entry: title, xpointer, depth (1 is the top level), page. */
    external fun toc(handle: Long): Array<String>

    /** Start xpointer, end xpointer and text; null when the points hit no text. */
    external fun selectText(handle: Long, x0: Int, y0: Int, x1: Int, y1: Int): Array<String>?

    /** Four ints per text line of the range on the page shown: left, top, right, bottom. */
    external fun rangeRects(handle: Long, start: String, end: String): IntArray

    /** Six strings per hit: start, end, text before, matched text, text after, page. */
    external fun search(
        handle: Long, pattern: String, caseInsensitive: Boolean, maxHits: Int, contextWords: Int,
    ): Array<String>

    /** Href, xpointer of the link and xpointer of its target ("" when outside the book); null when there is no link. */
    external fun linkAt(handle: Long, x: Int, y: Int): Array<String>?

    external fun renderPage(handle: Long, bitmap: Bitmap)
}
