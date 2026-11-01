package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The size is worked out for the face the book's text is drawn in (issue #126).
 *
 * The reader's number is the size of Roboto's letters, and every face is set at the em that gives it
 * Roboto's x-height (`FaceSizeTest`). That em used to be worked out for the face the reader picked,
 * also when the override is off and the book's text is drawn in a face of its own: the pick then
 * moved the letters and the lines of a page whose face did not change. Measured as `FaceSizeTest`
 * measures, on a book of nothing but the letter x, and compared by the page drawn, as
 * `FaceOverBookCssTest` compares it.
 *
 * The face a book carries is PT Serif from the test assets (OFL, `assets/fonts/OFL.txt`) under a
 * family name only that book has, so the font manager reaches it only through the document.
 */
@RunWith(AndroidJUnit4::class)
class FontSizeOfDrawnFaceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = File(context.cacheDir, "books").apply { mkdirs() }

    /** A book that names a face in a stylesheet of its own. */
    private val named: File by lazy {
        epub("letters-named-face.epub", """p { font-family: "PT Serif"; }""", carry = false)
    }

    /** A book that carries its face, declared with `@font-face` and listed in the package. */
    private val carried: File by lazy {
        epub(
            "letters-carried-face.epub",
            """@font-face { font-family: "Book Serif"; src: url(fonts/$CARRIED_FONT); }
body { font-family: "Book Serif", serif; }""",
            carry = true,
        )
    }

    @Test
    fun theFacePickedDecidesNothingAboutABookDrawnInItsOwn() {
        val ink = PICKS.associateWith { measure(named, it, override = false) }
        val letters = ink.mapValues { it.value.letters }
        val lines = ink.mapValues { it.value.lines }
        assertTrue("x-height within a pixel: $letters", letters.values.max() - letters.values.min() <= 1)
        assertTrue("line distance within a pixel: $lines", lines.values.max() - lines.values.min() <= 1)
    }

    @Test
    fun theFaceABookNamesIsDrawnAtTheReadersSize() {
        val own = measure(named, "Literata", override = false)
        val roboto = measure(named, "Roboto", override = true)
        assertTrue("x-height ${own.letters} px against Roboto's ${roboto.letters} px", abs(own.letters - roboto.letters) <= 1)
        assertTrue("line distance ${own.lines} px against Roboto's ${roboto.lines} px", abs(own.lines - roboto.lines) <= 1)
    }

    @Test
    fun aBookThatCarriesItsFaceDrawsOnePageWhateverIsPicked() {
        val literata = page(carried, "Literata")
        assertEquals("the page with Roboto picked is the page with Literata picked", literata, page(carried, "Roboto"))
        assertNotEquals(
            "and it is the book's face that is drawn: $literata", literata, page(carried, "Literata", embeddedFonts = false),
        )
    }

    @Test
    fun aFaceChangedOnTheOpenBookChangesNoPixel() = EngineDocument.open(
        context, named, WIDTH, HEIGHT,
        EngineSettings(fontFace = "Literata", fontSize = SIZE, overrideBookFont = false), RENDER_DPI,
    ).use { doc ->
        val before = hash(doc)
        doc.apply(EngineSettings(fontFace = "PT Sans", fontSize = SIZE, overrideBookFont = false))
        assertEquals("the page after PT Sans is picked on the open book", before, hash(doc))
    }

    /** The height of a line of x and the distance from one line to the next, the commonest of each on the page. */
    private class Ink(val letters: Int, val lines: Int)

    private fun measure(book: File, face: String, override: Boolean): Ink {
        val settings = EngineSettings(
            fontFace = face,
            fontSize = SIZE,
            textAlign = TextAlign.Left,
            hyphenation = false,
            overrideBookFont = override,
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

    /** The first page, with the override off, as the book draws it. */
    private fun page(book: File, face: String, embeddedFonts: Boolean = true): Int =
        EngineDocument.open(
            context, book, WIDTH, HEIGHT,
            EngineSettings(fontFace = face, fontSize = SIZE, overrideBookFont = false, embeddedFonts = embeddedFonts), RENDER_DPI,
        ).use(::hash)

    private fun hash(doc: EngineDocument): Int {
        val page = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        doc.renderPage(page)
        return ByteBuffer.allocate(page.byteCount).also { page.copyPixelsToBuffer(it) }.array().contentHashCode()
    }

    /** An EPUB of the letter x whose chapter takes [css] from a stylesheet of its own; [carry] puts the font in it. */
    private fun epub(name: String, css: String, carry: Boolean): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype)
            zip.closeEntry()

            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            val font = if (!carry) "" else
                """  <item id="font" href="fonts/$CARRIED_FONT" media-type="application/x-font-truetype"/>"""
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""",
            )
            entry(
                "content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Font Size Of Drawn Face</dc:title>
    <dc:creator>Test Author</dc:creator>
    <dc:language>en</dc:language>
    <dc:identifier id="id">perigee-$name</dc:identifier>
  </metadata>
  <manifest>
  <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
  <item id="css" href="style.css" media-type="text/css"/>
$font
  </manifest>
  <spine><itemref idref="c1"/></spine>
</package>""",
            )
            entry("style.css", css)
            if (carry) {
                zip.putNextEntry(ZipEntry("fonts/$CARRIED_FONT"))
                InstrumentationRegistry.getInstrumentation().context.assets.open("fonts/$CARRIED_FONT").use { it.copyTo(zip) }
                zip.closeEntry()
            }
            entry(
                "c1.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body><p>${"xxxxxxxx ".repeat(800)}</p></body></html>""",
            )
        }
        return file
    }

    private companion object {
        val PICKS = listOf("Literata", "PT Sans", "Roboto")
        const val CARRIED_FONT = "PT_Serif-Web-Regular.ttf"

        // As in FaceSizeTest: 18 dp on the owner's 560 dpi phone, whose render DPI is 336.
        const val SIZE = 63
        const val RENDER_DPI = 336
        const val WIDTH = 1080
        const val HEIGHT = 2000
    }
}
