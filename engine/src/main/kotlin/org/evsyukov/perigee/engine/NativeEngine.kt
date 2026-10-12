package org.evsyukov.perigee.engine

import android.graphics.Bitmap

internal object NativeEngine {
    init {
        System.loadLibrary("perigee_engine")
    }

    /** DOM version crengine assigns to newly opened books; xpointers depend on it. */
    external fun domVersion(): Int

    external fun init(hyphDir: String, cacheDir: String, cacheBytes: Long, fontFiles: Array<String>)

    external fun fontFaces(): Array<String>

    /** Creates an empty view; load a book into it after applySettings. */
    external fun create(width: Int, height: Int): Long

    external fun applySettings(
        handle: Long, cssFile: String, extraCss: String, fontFace: String, fontSize: Int,
        lineSpacingPercent: Int, marginHorizontal: Int, marginVertical: Int,
        hyphenation: Boolean, twoPages: Boolean, renderDpi: Int,
    )

    /** Returns false when the file cannot be opened. */
    external fun load(handle: Long, path: String): Boolean

    external fun close(handle: Long)
    external fun pageCount(handle: Long): Int
    external fun currentPage(handle: Long): Int
    external fun goToPage(handle: Long, page: Int): Boolean
    external fun xpointer(handle: Long): String
    external fun goToXPointer(handle: Long, xpointer: String): Boolean
    external fun textBetween(handle: Long, start: String, end: String): String?

    /** Position in the book in 1/10000 units. */
    external fun positionPermyriad(handle: Long): Int
    external fun renderPage(handle: Long, bitmap: Bitmap)
}
