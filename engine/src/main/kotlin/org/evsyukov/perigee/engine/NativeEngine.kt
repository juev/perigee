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

    /** Creates an empty view; load a book into it after applySettings. */
    external fun create(width: Int, height: Int): Long

    external fun applySettings(
        handle: Long, cssFile: String, extraCss: String, fontFace: String, fontSize: Int, fontWeight: Int,
        lineSpacingPercent: Int, marginHorizontal: Int, marginVertical: Int,
        hyphenation: Boolean, footnotes: Boolean, twoPages: Boolean, textColor: Int, backgroundColor: Int,
        renderDpi: Int,
    )

    /** Returns false when the file cannot be opened. */
    external fun load(handle: Long, path: String): Boolean

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

    /** Title, authors, language, series; "" for what the book does not say. */
    external fun metadata(handle: Long): Array<String>

    /** -1 when the xpointer does not resolve. */
    external fun pageOf(handle: Long, xpointer: String): Int
    external fun xpointerOfPage(handle: Long, page: Int): String?

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
