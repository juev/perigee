package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * An open book. Not thread-safe: use it from one thread at a time.
 * Positions are crengine xpointers, the same form KOReader exchanges with bookOrbit.
 */
class EngineDocument private constructor(
    private var handle: Long,
    private val stylesheet: File,
    private val renderDpi: Int,
) : AutoCloseable {

    val pageCount: Int get() = NativeEngine.pageCount(checked())

    /** Zero-based. */
    val currentPage: Int get() = NativeEngine.currentPage(checked())

    fun goToPage(page: Int): Boolean = NativeEngine.goToPage(checked(), page)

    /** Position of the top of the current page. */
    fun xpointer(): String = NativeEngine.xpointer(checked())

    /** Returns false when the xpointer does not resolve in this book. */
    fun goToXPointer(xpointer: String): Boolean = NativeEngine.goToXPointer(checked(), xpointer)

    /** Re-lays the book out; the reading position is kept. */
    fun apply(settings: EngineSettings) = NativeEngine.applySettings(
        checked(), stylesheet.path, settings.extraCss, settings.fontFace, settings.fontSize,
        settings.lineSpacingPercent, settings.marginHorizontal, settings.marginVertical,
        settings.hyphenation, settings.twoPages, renderDpi,
    )

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
        fun open(
            context: Context,
            file: File,
            width: Int,
            height: Int,
            settings: EngineSettings = EngineSettings(),
        ): EngineDocument {
            Engine.init(context)
            // CSS lengths are laid out as on the web: one CSS px is one dp.
            val renderDpi = context.resources.displayMetrics.densityDpi * 96 / 160
            val document = EngineDocument(NativeEngine.create(width, height), Engine.stylesheetFor(file), renderDpi)
            document.apply(settings)
            if (!NativeEngine.load(document.handle, file.path)) {
                document.close()
                throw IllegalArgumentException("cannot open ${file.name}")
            }
            return document
        }
    }
}
