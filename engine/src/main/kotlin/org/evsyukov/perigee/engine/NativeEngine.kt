package org.evsyukov.perigee.engine

import android.graphics.Bitmap

internal object NativeEngine {
    init {
        System.loadLibrary("perigee_engine")
    }

    /** DOM version crengine assigns to newly opened books; xpointers depend on it. */
    external fun domVersion(): Int

    external fun init(hyphDir: String, cacheDir: String, cacheBytes: Long, fontFiles: Array<String>)

    /** Returns 0 when the file cannot be opened. */
    external fun open(path: String, cssFile: String, fontFace: String, fontSize: Int, width: Int, height: Int): Long

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
