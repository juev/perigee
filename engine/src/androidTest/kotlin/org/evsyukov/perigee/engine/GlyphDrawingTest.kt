package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The six settings of how the letters are drawn reach the engine, and changing any of them keeps the
 * reading position. Most of them are kept by the engine's font manager for every book, so each test puts
 * the defaults back before it closes the book: the next test must not inherit a monochrome page.
 */
@RunWith(AndroidJUnit4::class)
class GlyphDrawingTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = File(context.cacheDir, "books").apply { mkdirs() }
    private val settings = EngineSettings(marginHorizontal = MARGIN, marginTop = MARGIN, marginBottom = MARGIN)

    @Test
    fun smoothingOffDrawsTheTextInOneColourAndOnInMany() = withBook { doc ->
        doc.apply(settings.copy(antialiasing = false))
        val sharp = coloursBetweenMargins(doc)
        doc.apply(settings)
        val smooth = coloursBetweenMargins(doc)
        // The page shows plain text in the default black on white, with no link or picture to add a colour.
        assertEquals("smoothing off: text and page only, got ${sharp.size} colours", 2, sharp.size)
        assertTrue("smoothing on: grey edges as well, got ${smooth.size} colours", smooth.size > 2)
    }

    @Test
    fun theReadingPositionSurvivesEveryChangeOfDrawing() = withBook { doc ->
        val position = doc.xpointer()
        val changes = listOf(
            "smoothing off" to settings.copy(antialiasing = false),
            "hinting off" to settings.copy(hinting = HintingMode.Off),
            "the font's own hinting" to settings.copy(hinting = HintingMode.Native),
            "ligatures" to settings.copy(ligatures = true),
            "sub-pixel placement" to settings.copy(fractionalPositioning = 3),
            "code size" to settings.copy(monospaceSizePercent = 140),
            "no book fonts" to settings.copy(embeddedFonts = false),
        )
        for ((name, changed) in changes) {
            doc.apply(changed)
            // The page opens a chapter, and a chapter always starts a page: re-broken lines cannot move it.
            assertEquals("after $name", position, doc.xpointer())
            doc.apply(settings)
            assertEquals("after $name and back", position, doc.xpointer())
        }
    }

    @Test
    fun theEngineHoldsTheChosenHinting() = withBook { doc ->
        doc.apply(settings.copy(hinting = HintingMode.Off))
        assertEquals(HintingMode.Off.engine, NativeEngine.hintingMode())
        doc.apply(settings.copy(hinting = HintingMode.Native))
        assertEquals(HintingMode.Native.engine, NativeEngine.hintingMode())
        doc.apply(settings)
        assertEquals(HintingMode.Auto.engine, NativeEngine.hintingMode())
    }

    @Test
    fun ligaturesGiveAnEnglishBookFullShapingAndOpeningKeepsIt() {
        withBook { doc ->
            assertFalse("an English book is shaped lightly by default", doc.fullShaping)
            doc.apply(settings.copy(ligatures = true))
            assertTrue("with ligatures on it is shaped in full", doc.fullShaping)
        }
        // The kerning is picked again when a book is loaded; the switch set before must hold through it.
        open(settings.copy(ligatures = true)).use { doc ->
            try {
                assertTrue("a book opened with ligatures on is shaped in full", doc.fullShaping)
            } finally {
                doc.apply(settings)
            }
        }
    }

    /** The book open at the page that starts its third chapter, with the defaults put back afterwards. */
    private fun withBook(block: (EngineDocument) -> Unit) = open(settings).use { doc ->
        val chapter = (0 until doc.pageCount).first { doc.xpointerOfPage(it).orEmpty().contains("DocFragment[3]") }
        assertTrue(doc.goToPage(chapter))
        try {
            block(doc)
        } finally {
            doc.apply(settings)
        }
    }

    private fun open(settings: EngineSettings) = EngineDocument.open(context, TestBooks.epub(dir), WIDTH, HEIGHT, settings)

    /** The distinct ARGB values of the page shown, inside its margins. */
    private fun coloursBetweenMargins(doc: EngineDocument): Set<Int> {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        doc.renderPage(bitmap)
        val width = WIDTH - 2 * MARGIN
        val height = HEIGHT - 2 * MARGIN
        val pixels = IntArray(width * height).also { bitmap.getPixels(it, 0, width, MARGIN, MARGIN, width, height) }
        return pixels.toSet()
    }

    private companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val MARGIN = 24
    }
}
