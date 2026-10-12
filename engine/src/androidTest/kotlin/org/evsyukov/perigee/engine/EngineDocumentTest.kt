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

    private fun open(file: File) = EngineDocument.open(context, file, WIDTH, HEIGHT, fontSize = 28)

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

    private fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val CHAPTERS = 3
    }
}
