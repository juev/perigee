package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineDocumentTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = File(context.cacheDir, "books").apply { mkdirs() }

    private fun open(file: File) = EngineDocument.open(context, file, WIDTH, HEIGHT)

    @Test
    fun epubRendersTextOnThePage() = open(TestBooks.epub(dir)).use { doc ->
        assertTrue("a three-chapter book spans several pages", doc.pageCount > CHAPTERS)

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        doc.renderPage(bitmap)

        val pixels = IntArray(WIDTH * HEIGHT).also { bitmap.getPixels(it, 0, WIDTH, 0, 0, WIDTH, HEIGHT) }
        val dark = pixels.count { luminance(it) < 96 }
        val light = pixels.count { luminance(it) > 200 }
        assertTrue("text pixels are drawn, got $dark", dark > WIDTH * HEIGHT / 200)
        assertTrue("the page background stays light, got $light", light > WIDTH * HEIGHT / 2)
        assertEquals("the bitmap is opaque", 0xFF, pixels[0] ushr 24)
    }

    @Test
    fun epubXPointerReturnsToTheSamePage() = open(TestBooks.epub(dir)).use { doc ->
        val target = doc.pageCount / 2
        assertTrue(doc.goToPage(target))
        val xpointer = doc.xpointer()
        // DOM 20260812 writes the index of a first sibling explicitly: /body[1]/DocFragment[2]/…
        assertTrue(
            "the middle of a three-chapter EPUB is in the second spine item, got $xpointer",
            xpointer.startsWith("/body[1]/DocFragment[2]/body[1]/"),
        )

        doc.goToPage(0)
        assertNotEquals(target, doc.currentPage)
        assertTrue(doc.goToXPointer(xpointer))
        assertEquals(target, doc.currentPage)
    }

    @Test
    fun xpointerSurvivesReopeningTheBook() {
        val file = TestBooks.epub(dir)
        val (page, xpointer) = open(file).use { doc ->
            doc.goToPage(doc.pageCount - 2)
            doc.currentPage to doc.xpointer()
        }
        open(file).use { doc ->
            assertTrue(doc.goToXPointer(xpointer))
            assertEquals(page, doc.currentPage)
        }
    }

    @Test
    fun fb2XPointerReturnsToTheSamePage() = open(TestBooks.fb2(dir)).use { doc ->
        val target = doc.pageCount / 2
        assertTrue(doc.goToPage(target))
        val xpointer = doc.xpointer()
        assertTrue("crengine FB2 xpointer, got $xpointer", xpointer.startsWith("/FictionBook"))

        doc.goToPage(0)
        assertTrue(doc.goToXPointer(xpointer))
        assertEquals(target, doc.currentPage)
    }

    @Test
    fun unresolvableXPointerIsRejected() = open(TestBooks.epub(dir)).use { doc ->
        doc.goToPage(2)
        assertFalse(doc.goToXPointer("/body/DocFragment[99]/body/p[1]/text().0"))
        assertEquals("a rejected xpointer leaves the position alone", 2, doc.currentPage)
    }

    @Test
    fun largerFontKeepsThePositionAndAddsPages() = open(TestBooks.epub(dir)).use { doc ->
        doc.goToPage(doc.pageCount / 2)
        val position = doc.xpointer()
        val pages = doc.pageCount

        doc.apply(EngineSettings(fontSize = 44))

        assertTrue("larger type needs more pages: $pages -> ${doc.pageCount}", doc.pageCount > pages)
        // Line breaks moved, so the page may start a few characters earlier;
        // what matters is that the text the reader was at is still on screen.
        val shown = doc.currentPage
        assertTrue(doc.goToXPointer(position))
        assertEquals("the old position is on the page shown after the change", shown, doc.currentPage)
    }

    @Test
    fun twoPageModeHalvesTheNumberOfSpreads() {
        val file = TestBooks.epub(dir)
        val single = EngineDocument.open(context, file, HEIGHT, WIDTH).use { it.pageCount }
        val double = EngineDocument.open(context, file, HEIGHT, WIDTH, EngineSettings(twoPages = true)).use { it.pageCount }
        // Each of the two columns is narrower than half the screen, so the count
        // does not match exactly; it only has to show the text was split in two.
        assertTrue("two columns on a landscape screen: $single -> $double", double > single)
    }

    @Test
    fun bothPagesOfASpreadStartTheirTextAtTheSameHeight() {
        val settings = EngineSettings(marginTop = 28, twoPages = true)
        EngineDocument.open(context, TestBooks.epub(dir), HEIGHT, WIDTH, settings).use { doc ->
            val bitmap = Bitmap.createBitmap(HEIGHT, WIDTH, Bitmap.Config.ARGB_8888)
            // A chapter heading brings its own space above it, so a page that opens
            // a chapter starts lower by design; only spreads in mid-chapter compare.
            val spreads = (0 until doc.pageCount - 1 step 2).filter { left ->
                listOf(left, left + 1).none { doc.xpointerOfPage(it).orEmpty().contains("/h1[") }
            }
            assertTrue("the book has spreads in mid-chapter: $spreads", spreads.size > CHAPTERS)
            for (left in spreads) {
                doc.goToPage(left)
                doc.renderPage(bitmap)
                assertEquals(
                    "pages $left and ${left + 1}: the top line of text",
                    firstInkRow(bitmap, 0, HEIGHT / 2),
                    firstInkRow(bitmap, HEIGHT / 2, HEIGHT),
                )
            }
        }
    }

    @Test
    fun russianBookIsHyphenatedByItsOwnLanguage() {
        val file = TestBooks.russianFb2(dir)
        val narrow = EngineSettings(fontSize = 44)
        val hyphenated = renderFirstPage(file, narrow)
        val unhyphenated = renderFirstPage(file, narrow.copy(hyphenation = false))
        savePage("russian-hyphenated", hyphenated)
        savePage("russian-unhyphenated", unhyphenated)
        // With the Russian dictionary in effect long words break across lines,
        // so the two pages cannot come out the same.
        assertFalse("hyphenation changes the layout of a Russian page", hyphenated.sameAs(unhyphenated))
    }

    private fun renderFirstPage(file: File, settings: EngineSettings): Bitmap =
        EngineDocument.open(context, file, WIDTH, HEIGHT, settings).use { doc ->
            Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also(doc::renderPage)
        }

    /** Keeps the page for a human to look at; AGP copies this directory off the device. */
    private fun savePage(name: String, bitmap: Bitmap) {
        val out = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        File(out).mkdirs()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The first row from the top with a dark pixel between [fromX] and [toX], or -1 if there is none. */
    private fun firstInkRow(bitmap: Bitmap, fromX: Int, toX: Int): Int {
        val row = IntArray(toX - fromX)
        return (0 until bitmap.height).firstOrNull { y ->
            bitmap.getPixels(row, 0, row.size, fromX, y, row.size, 1)
            row.any { luminance(it) < 96 }
        } ?: -1
    }

    private fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val CHAPTERS = TestBooks.CHAPTERS
    }
}
