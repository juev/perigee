package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * An open book. Not thread-safe: use it from one thread at a time.
 * Positions are crengine xpointers, the same form KOReader exchanges with bookOrbit.
 */
class EngineDocument private constructor(private var handle: Long) : AutoCloseable {

    val pageCount: Int get() = NativeEngine.pageCount(checked())

    /** Zero-based. */
    val currentPage: Int get() = NativeEngine.currentPage(checked())

    fun goToPage(page: Int): Boolean = NativeEngine.goToPage(checked(), page)

    /** Position of the top of the current page. */
    fun xpointer(): String = NativeEngine.xpointer(checked())

    /** Returns false when the xpointer does not resolve in this book. */
    fun goToXPointer(xpointer: String): Boolean = NativeEngine.goToXPointer(checked(), xpointer)

    /** Text between two xpointers, or null when either does not resolve in this book. */
    fun textBetween(start: String, end: String): String? = NativeEngine.textBetween(checked(), start, end)

    /** How far the current page is into the book, 0..1. */
    val progress: Float get() = NativeEngine.positionPermyriad(checked()) / 10000f

    /** Draws the current page; the bitmap size becomes the page size. */
    fun renderPage(bitmap: Bitmap) = NativeEngine.renderPage(checked(), bitmap)

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
        const val DEFAULT_FONT_FACE = "Noto Serif"

        fun open(
            context: Context,
            file: File,
            width: Int,
            height: Int,
            fontSize: Int,
            fontFace: String = DEFAULT_FONT_FACE,
        ): EngineDocument {
            Engine.init(context)
            val handle = NativeEngine.open(
                file.path, Engine.stylesheetFor(file).path, fontFace, fontSize, width, height,
            )
            require(handle != 0L) { "cannot open ${file.name}" }
            return EngineDocument(handle)
        }
    }
}
