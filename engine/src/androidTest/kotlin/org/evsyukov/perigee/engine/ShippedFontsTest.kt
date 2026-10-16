package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The fonts that come with the app, and where a glyph a font lacks is taken from. */
@RunWith(AndroidJUnit4::class)
class ShippedFontsTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = File(context.cacheDir, "books").apply { mkdirs() }

    @Test
    fun fontsThatComeWithTheAppAreOffered() {
        val faces = runBlocking { Engine.fontFaces(context) }
        assertTrue("offered: $faces", faces.containsAll(SHIPPED))
    }

    /** A family that did not register would be drawn in the engine's default font, the same for all of them. */
    @Test
    fun eachFontThatComesWithTheAppDrawsRussianTextItsOwnWay() {
        val book = TestBooks.russianFb2(dir)
        val pages = SHIPPED.associateWith { face ->
            EngineDocument.open(context, book, WIDTH, HEIGHT, EngineSettings(fontFace = face), DENSITY).use { doc ->
                val page = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
                doc.renderPage(page, emptyList())
                ByteBuffer.allocate(page.byteCount).also { page.copyPixelsToBuffer(it) }.array().contentHashCode()
            }
        }
        assertEquals("every family draws the page differently: $pages", SHIPPED.size, pages.values.toSet().size)
    }

    @Test
    fun glyphAFontLacksIsLookedForInNotoSerifFirst() {
        val fallback = runBlocking { Engine.fallbackFaces(context) }
        assertEquals("then the fonts of single scripts: $fallback", Engine.FALLBACK_FACE, fallback.first())
    }

    private companion object {
        val SHIPPED = listOf("Comfortaa", "Droid Serif", "Literata", "Merriweather", "Noto Serif", "PT Sans", "PT Serif", "Roboto")
        const val WIDTH = 600
        const val HEIGHT = 800
        const val DENSITY = 320
    }
}
