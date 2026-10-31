package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One setting, one size of letters and one distance between lines in every face. Measured on the page
 * as drawn: a book of nothing but the letter x gives bands of ink as tall as the x-height.
 */
@RunWith(AndroidJUnit4::class)
class FaceSizeTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = File(context.cacheDir, "books").apply { mkdirs() }

    @Test
    fun lettersAreOneSizeInEveryFace() {
        val sizes = SHIPPED.associateWith { measure(book(), it).letters }
        assertTrue("x-height within a pixel: $sizes", sizes.values.max() - sizes.values.min() <= 1)
    }

    @Test
    fun linesAreOneDistanceApartInEveryFace() {
        val distances = SHIPPED.associateWith { measure(book(), it).lines }
        assertTrue("line distance within a pixel: $distances", distances.values.max() - distances.values.min() <= 1)
    }

    @Test
    fun theLineSpacingSettingMeansTheSameInEveryFace() {
        val wider = SHIPPED.associateWith {
            measure(book(), it, lineSpacingPercent = 150).lines.toDouble() / measure(book(), it).lines
        }
        assertTrue("150 % against 120 %: $wider", wider.values.all { it in 1.2..1.3 })
    }

    @Test
    fun aSizeTheEngineUsedToCutOffIsDrawn() {
        val large = measure(book(), "Roboto", fontSize = 100).letters
        val larger = measure(book(), "Roboto", fontSize = 140).letters
        assertTrue("x-height grows with the setting: $large -> $larger", larger > large * 13 / 10)
    }

    /** Set by the book, a line height is in ems of the face drawn, so the larger em has the wider lines. */
    @Test
    fun aBookThatSetsItsLineHeightKeepsIt() {
        val styled = book("body { line-height: 2; }")
        val roboto = measure(styled, "Roboto").lines
        val serif = measure(styled, "PT Serif").lines
        assertTrue("$roboto px in Roboto", roboto > measure(book(), "Roboto").lines)
        assertTrue("PT Serif's em is larger than Roboto's: $roboto, $serif", serif > roboto)
    }

    @Test
    fun anotherFaceKeepsThePlaceRead() = EngineDocument.open(
        context, TestBooks.epub(dir), WIDTH, HEIGHT, EngineSettings(fontFace = "Roboto", fontSize = SIZE), RENDER_DPI,
    ).use { doc ->
        doc.goToPage(doc.pageCount / 2)
        val position = doc.xpointer()

        doc.apply(EngineSettings(fontFace = "Literata", fontSize = SIZE))

        val shown = doc.currentPage
        assertTrue(doc.goToXPointer(position))
        assertEquals("the old position is on the page shown after the change", shown, doc.currentPage)
    }

    private fun book(css: String? = null): File {
        val style = css?.let { """<stylesheet type="text/css">$it</stylesheet>""" }.orEmpty()
        val name = "letters-${css.hashCode()}.fb2"
        return File(dir, name).apply {
            writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">$style
<description><title-info><book-title>x</book-title><lang>en</lang></title-info></description>
<body><section><p>${"xxxxxxxx ".repeat(800)}</p></section></body>
</FictionBook>""",
            )
        }
    }

    /** The height of a line of x and the distance from one line to the next, the commonest of each on the page. */
    private class Ink(val letters: Int, val lines: Int)

    private fun measure(book: File, face: String, fontSize: Int = SIZE, lineSpacingPercent: Int = 120): Ink {
        val settings = EngineSettings(
            fontFace = face,
            fontSize = fontSize,
            lineSpacingPercent = lineSpacingPercent,
            textAlign = TextAlign.Left,
            hyphenation = false,
        )
        val page = EngineDocument.open(context, book, WIDTH, HEIGHT, settings, RENDER_DPI).use { doc ->
            Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also(doc::renderPage)
        }
        val pixels = IntArray(WIDTH * HEIGHT).also { page.getPixels(it, 0, WIDTH, 0, 0, WIDTH, HEIGHT) }
        val inked = (0 until HEIGHT).map { y -> (0 until WIDTH).any { x -> Color.green(pixels[y * WIDTH + x]) < 128 } }
        val starts = (0 until HEIGHT).filter { inked[it] && (it == 0 || !inked[it - 1]) }
        val ends = (0 until HEIGHT).filter { inked[it] && (it == HEIGHT - 1 || !inked[it + 1]) }
        val bands = starts.zip(ends) { start, end -> end - start + 1 }
        return Ink(commonest(bands), commonest(starts.zipWithNext { a, b -> b - a }))
    }

    private fun commonest(values: List<Int>): Int = values.groupingBy { it }.eachCount().maxBy { it.value }.key

    private companion object {
        val SHIPPED = listOf("Comfortaa", "Droid Serif", "Literata", "Merriweather", "Noto Serif", "PT Sans", "PT Serif", "Roboto")

        // 18 dp on the owner's 560 dpi phone, whose render DPI is 336.
        const val SIZE = 63
        const val RENDER_DPI = 336
        const val WIDTH = 1080
        const val HEIGHT = 2000
    }
}
