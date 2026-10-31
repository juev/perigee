package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gaps of justified lines, measured on the page as drawn: the widest run of empty columns inside a
 * line. Line by line, the paragraph of [AUSTEN] has a gap of 0.90 em and [RUSSIAN] one before a Latin word
 * the plain Russian dictionary cannot hyphenate; docs/spikes/word-gaps-preview.md has the measurements.
 */
@RunWith(AndroidJUnit4::class)
class JustificationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = File(context.cacheDir, "books").apply { mkdirs() }

    @Test
    fun aLooseLineIsRepaired() {
        val widest = widestGap(book("en", AUSTEN))
        assertTrue("widest gap $widest px, ceiling $LIMIT px", widest <= LIMIT)
    }

    @Test
    fun aLatinWordInARussianBookIsHyphenated() {
        val widest = widestGap(book("ru", RUSSIAN), firstLine = 1) // the first line has no way out
        assertTrue("widest gap $widest px, ceiling $LIMIT px", widest <= LIMIT)
    }

    private fun book(language: String, text: String): File = File(dir, "gaps-$language.fb2").apply {
        writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
<description><title-info><book-title>x</book-title><lang>$language</lang></title-info></description>
<body><section><p>$text</p></section></body>
</FictionBook>""",
        )
    }

    /** The widest gap inside the lines of the first page from [firstLine] on, the last line of the text left out. */
    private fun widestGap(book: File, firstLine: Int = 0): Int {
        val settings = EngineSettings(
            fontFace = "Roboto",
            fontSize = SIZE,
            marginHorizontal = 0,
            marginTop = 0,
            marginBottom = 0,
            textAlign = TextAlign.Justify,
            hyphenation = true,
        )
        val page = EngineDocument.open(context, book, WIDTH, HEIGHT, settings, RENDER_DPI).use { doc ->
            Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also(doc::renderPage)
        }
        val pixels = IntArray(WIDTH * HEIGHT).also { page.getPixels(it, 0, WIDTH, 0, 0, WIDTH, HEIGHT) }
        fun ink(x: Int, y: Int) = Color.green(pixels[y * WIDTH + x]) < 128
        val inked = (0 until HEIGHT).map { y -> (0 until WIDTH).any { x -> ink(x, y) } }
        val starts = (0 until HEIGHT).filter { inked[it] && (it == 0 || !inked[it - 1]) }
        val ends = (0 until HEIGHT).filter { inked[it] && (it == HEIGHT - 1 || !inked[it + 1]) }
        return starts.zip(ends).drop(firstLine).dropLast(1).maxOf { (top, bottom) ->
            val columns = (0 until WIDTH).filter { x -> (top..bottom).any { y -> ink(x, y) } }
            columns.zipWithNext { a, b -> b - a - 1 }.max()
        }
    }

    private companion object {
        // The width in ems of the phone the paragraph was measured on, 22 em at 63 px.
        const val SIZE = 63
        const val RENDER_DPI = 560
        const val WIDTH = 1384
        const val HEIGHT = 2800

        // 0.75 em of 63 px: a gap past it is what the repair takes away.
        const val LIMIT = 47

        const val AUSTEN = "At length, however, Mrs. Bennet had no more to say; and Lady Lucas, who had been long yawning at the repetition of delights which she saw no likelihood of sharing, was left to the comforts of cold ham and chicken. Elizabeth now began to revive. But not long was the interval of tranquillity; for when supper was over, singing was talked of, and she had the mortification of seeing Mary, after very little entreaty, preparing to oblige the company. By many significant looks and silent entreaties did she endeavour to prevent such a proof of complaisance,--but in vain; Mary would not understand them; such an opportunity of exhibiting was delightful to her, and she began her song. Elizabeth’s eyes were fixed on her, with most painful sensations; and she watched her progress through the several stanzas with an impatience which was very ill rewarded at their close; for Mary, on receiving amongst the thanks of the table the hint of a hope that she might be prevailed on to favour them again, after the pause of half a minute began another. Mary’s powers were by no means fitted for such a display; her voice was weak, and her manner affected. Elizabeth was in agonies. She looked at Jane to see how she bore it; but Jane was very composedly talking to Bingley. She looked at his two sisters, and saw them making signs of derision at each other, and at Darcy, who continued, however, impenetrably grave. She looked at her father to entreat his interference, lest Mary should be singing all night. He took the hint, and, when Mary had finished her second song, said aloud,--"
        const val RUSSIAN = "Необходимый минимум, который нужно прописать (а точнее изменить в файле mcabberrc), это:"
    }
}
