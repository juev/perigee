package org.evsyukov.perigee

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.engine.EngineDocument
import org.evsyukov.perigee.engine.EngineSettings

/** What the screen shows after each engine call. */
data class PageState(val bitmap: Bitmap, val page: Int, val pageCount: Int)

/**
 * One open book. The engine is not thread-safe, so every call runs on a
 * dedicated thread, which also keeps layout off the main thread.
 */
class ReaderSession private constructor(
    private val document: EngineDocument,
    private val engineThread: ExecutorCoroutineDispatcher,
) {
    suspend fun render(width: Int, height: Int): PageState = withContext(engineThread) { draw(width, height) }

    suspend fun turn(delta: Int, width: Int, height: Int): PageState = withContext(engineThread) {
        document.goToPage((document.currentPage + delta).coerceIn(0, document.pageCount - 1))
        draw(width, height)
    }

    suspend fun apply(settings: EngineSettings, width: Int, height: Int): PageState = withContext(engineThread) {
        document.apply(settings)
        draw(width, height)
    }

    suspend fun close() {
        withContext(engineThread) { document.close() }
        engineThread.close()
    }

    private fun draw(width: Int, height: Int): PageState {
        // A fresh bitmap per page: Compose may still be drawing the previous one.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        document.renderPage(bitmap)
        return PageState(bitmap, document.currentPage, document.pageCount)
    }

    companion object {
        suspend fun open(context: Context, file: File, width: Int, height: Int, settings: EngineSettings): ReaderSession {
            val engineThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            return try {
                ReaderSession(withContext(engineThread) { EngineDocument.open(context, file, width, height, settings) }, engineThread)
            } catch (e: IllegalArgumentException) {
                engineThread.close()
                throw e
            }
        }
    }
}
