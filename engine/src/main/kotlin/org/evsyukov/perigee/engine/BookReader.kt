package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A drawn page. With two pages side by side [index] is the left one. */
class Page(
    val bitmap: Bitmap,
    val index: Int,
    val pageCount: Int,
    /** Position of the top of the page. */
    val xpointer: String,
    /** How far the page is into the book, 0..1. */
    val progress: Float,
)

/** How a book is laid out: the settings and the page size. */
data class Layout(val settings: EngineSettings, val width: Int, val height: Int)

/**
 * A book being read: the safe way to use [EngineDocument] from any thread.
 * Every call runs on the engine thread. Pages next to the one shown are drawn
 * ahead of time, so turning the page does not wait for the engine.
 *
 * A second copy of the book may be kept laid out for another page size, as
 * for the screen turned the other way ([prepareSpare]): laying a long book out
 * takes most of a second, and a rotation to that size then takes none.
 */
class BookReader private constructor(
    private var document: EngineDocument,
    private var width: Int,
    private var height: Int,
    private var settings: EngineSettings,
    private val context: Context,
    private val file: File,
    private val densityDpi: Int,
) {
    /** The second copy and the layout it has. */
    private class Spare(val document: EngineDocument, val layout: Layout)
    private var spare: Spare? = null

    // Everything below is touched on the engine thread only.
    private val scope = CoroutineScope(SupervisorJob() + Engine.thread)
    private val cache = HashMap<Int, Page>()
    private var highlights: List<Highlight> = emptyList()
    private var layout = 0

    /**
     * Where the reader is: the top of the page they last turned to, or the
     * place they last jumped to. A layout
     * change shows the page this falls on but does not move it, so turning the
     * screen and turning it back returns to the very same page instead of
     * creeping towards the start of the book. This is the position to store.
     */
    @Volatile
    var position: String = document.xpointer()
        private set
    private var closed = false

    /** Screens are one page apart, or two when pages are shown side by side. */
    private val step: Int get() = if (settings.twoPages) 2 else 1

    /** The page the book is open at. */
    suspend fun current(): Page = onEngine { show() }

    /** Moves by [screens] screens, back when negative, stopping at either end of the book. */
    suspend fun turn(screens: Int): Page = onEngine {
        document.goToPage((document.currentPage + screens * step).coerceIn(0, document.pageCount - 1))
        arrived()
    }

    suspend fun goToPage(page: Int): Page = onEngine {
        val bounded = page.coerceIn(0, document.pageCount - 1)
        // Side by side, the left page is always an even one.
        document.goToPage(bounded - bounded % step)
        arrived()
    }

    /** Returns null and stays in place when the xpointer does not resolve in this book. */
    suspend fun goTo(xpointer: String): Page? = onEngine {
        if (document.goToXPointer(xpointer)) {
            // The place asked for, not the top of the page it happens to be on
            // now: a stored position must not move just because it was opened.
            position = xpointer
            show()
        } else {
            null
        }
    }

    /**
     * Re-lays the book out; the reading position is kept. The page size may
     * change in the same go, as when a setting alters how much of the screen
     * the page takes.
     */
    suspend fun apply(settings: EngineSettings, width: Int = this.width, height: Int = this.height): Page =
        onEngine {
            val wanted = Layout(settings, width, height)
            val ready = spare
            if (ready != null && ready.layout == wanted) {
                // Laid out for this already: the copies change places, and the one shown so far waits for the way back.
                spare = Spare(document, Layout(this.settings, this.width, this.height))
                document = ready.document
            } else {
                document.apply(settings, width, height)
            }
            this.settings = settings
            this.width = width
            this.height = height
            relaidOut()
        }

    /**
     * Lays a second copy of the book out as [layout] asks, for [apply] to show
     * at once when it is asked for that. Takes as long as laying the book out
     * does, and keeps the engine from anything else meanwhile: to be called
     * when the reader is not turning pages.
     */
    suspend fun prepareSpare(layout: Layout) = onEngine {
        if (layout == Layout(settings, width, height) || spare?.layout == layout) return@onEngine
        val old = spare
        spare = if (old != null) {
            old.document.apply(layout.settings, layout.width, layout.height)
            Spare(old.document, layout)
        } else {
            val copy = EngineDocument.open(context, file, layout.width, layout.height, layout.settings, densityDpi, cacheable = false)
            Spare(copy, layout)
        }
    }

    /** Whether a second copy is laid out as [layout] asks. */
    suspend fun hasSpare(layout: Layout): Boolean = onEngine { spare?.layout == layout }

    /** Re-lays the book out for a new page size, as after a rotation. */
    suspend fun resize(width: Int, height: Int): Page = onEngine {
        if (width == this.width && height == this.height) return@onEngine show()
        this.width = width
        this.height = height
        relaidOut()
    }

    /** Replaces the ranges tinted on every page. */
    suspend fun setHighlights(highlights: List<Highlight>): Page = onEngine {
        if (highlights == this.highlights) return@onEngine show()
        this.highlights = highlights
        relaidOut()
    }

    /** Keeps the book as laid out now in the engine's cache, for a quick next opening. */
    suspend fun saveCache(): Unit = onEngine { document.saveCache() }

    /** Anything else the document can tell: contents, search, what is under a touch. */
    suspend fun <T> read(block: (EngineDocument) -> T): T = onEngine { block(document) }

    /** Pages drawn and kept, once the drawing ahead asked for so far is done. */
    internal suspend fun cachedPages(): Set<Int> = onEngine { cache.keys.toSet() }

    /**
     * Closes the book. A call that arrives afterwards, as from a screen that is
     * going away, is cancelled rather than failed.
     */
    suspend fun close() {
        scope.cancel()
        // Queued ahead-of-time work is cancelled above; what is running finishes first.
        withContext(Engine.thread) {
            closed = true
            document.close()
            spare?.document?.close()
            spare = null
        }
    }

    private suspend fun <T> onEngine(block: () -> T): T = withContext(Engine.thread) {
        if (closed) throw CancellationException("the book is closed")
        block()
    }

    /** The reader went somewhere: that place is their position from now on. */
    private fun arrived(): Page {
        position = document.xpointer()
        return show()
    }

    private fun relaidOut(): Page {
        cache.clear()
        layout++
        document.goToXPointer(position)
        return show()
    }

    private fun show(): Page {
        val index = document.currentPage
        val page = cache[index] ?: draw().also { cache[index] = it }
        // Keep what is next to the reader; pages further off are cheap to redraw.
        cache.keys.retainAll { it in index - step..index + step }
        drawAhead(index + step)
        drawAhead(index - step)
        return page
    }

    private fun drawAhead(index: Int) {
        if (index < 0 || index >= document.pageCount || index in cache) return
        val forLayout = layout
        // Its own task, so a tap that arrives meanwhile is served between two pages.
        scope.launch {
            val shown = document.currentPage
            if (forLayout != layout || index in cache || index !in shown - step..shown + step) return@launch
            document.goToPage(index)
            cache[index] = draw()
            document.goToPage(shown)
        }
    }

    private fun draw(): Page {
        // A fresh bitmap per page: the screen may still be drawing the previous one.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        document.renderPage(bitmap, highlights)
        return Page(bitmap, document.currentPage, document.pageCount, document.xpointer(), document.progress)
    }

    companion object {
        /**
         * Opens the book at [xpointer] when it is given and still resolves.
         * @throws IllegalArgumentException when the engine cannot read the file
         */
        suspend fun open(
            context: Context,
            file: File,
            width: Int,
            height: Int,
            settings: EngineSettings = EngineSettings(),
            xpointer: String? = null,
            densityDpi: Int = context.resources.displayMetrics.densityDpi,
        ): BookReader = withContext(Engine.thread) {
            val document = EngineDocument.open(context, file, width, height, settings, densityDpi)
            val reader = BookReader(document, width, height, settings, context.applicationContext, file, densityDpi)
            if (xpointer != null && document.goToXPointer(xpointer)) reader.position = xpointer
            reader
        }
    }
}
