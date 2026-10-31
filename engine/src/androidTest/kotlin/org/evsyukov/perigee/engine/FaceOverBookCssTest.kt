package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The face the reader picks against a book that names fonts of its own.
 *
 * A book without a `font-family` is drawn in the reader's face, and `ShippedFontsTest` covers that.
 * A book that sets one used to keep it: the reader's choice arrived only as the root style of the
 * document, and an element that names a font asks the engine for that name first. So the setting did
 * nothing for such a book, and the page stayed the same whatever was picked (issue #120). Every book
 * the tests generated had no `font-family`, which is why nothing caught it.
 */
@RunWith(AndroidJUnit4::class)
class FaceOverBookCssTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = File(context.cacheDir, "books").apply { mkdirs() }

    @Test
    fun theFaceChosenBeatsAListThatEndsInAGeneric() {
        val book = epub("list-ending-in-a-generic.epub", """p { font-family: "PT Serif", serif; }""")
        val pages = pagesIn(book)
        assertNotEquals("a book listing a face and a generic: $pages", pages.first, pages.second)
    }

    @Test
    fun theFaceChosenBeatsABookThatNamesOneFont() {
        val book = epub("one-named-face.epub", """p { font-family: "PT Serif"; }""")
        val pages = pagesIn(book)
        assertNotEquals("a book naming one face: $pages", pages.first, pages.second)
    }

    /** The path the app takes: the reader changes the face with the book already open. */
    @Test
    fun theFaceChangedOnAnOpenBookIsDrawn() {
        val book = epub("open-book.epub", """p { font-family: "PT Serif"; }""")
        EngineDocument.open(context, book, WIDTH, HEIGHT, EngineSettings(fontFace = ROBOTO), DENSITY).use { doc ->
            val roboto = hash(doc)
            doc.apply(EngineSettings(fontFace = LITERATA))
            assertNotEquals("the face changed on the open book: $roboto", roboto, hash(doc))
        }
    }

    /** A page of the book in each of two faces, drawn from its start. */
    private fun pagesIn(book: File): Pair<Int, Int> {
        fun page(face: String): Int =
            EngineDocument.open(context, book, WIDTH, HEIGHT, EngineSettings(fontFace = face), DENSITY).use(::hash)
        return page(ROBOTO) to page(LITERATA)
    }

    private fun hash(doc: EngineDocument): Int {
        val page = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        doc.renderPage(page)
        return ByteBuffer.allocate(page.byteCount).also { page.copyPixelsToBuffer(it) }.array().contentHashCode()
    }

    /** An EPUB whose chapter sets [css], so that the book, not the app, decides the face. */
    private fun epub(name: String, css: String): File {
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
    <dc:title>Face Over Book CSS</dc:title>
    <dc:creator>Test Author</dc:creator>
    <dc:language>en</dc:language>
    <dc:identifier id="id">perigee-face-over-book-css</dc:identifier>
  </metadata>
  <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine><itemref idref="c1"/></spine>
</package>""",
            )
            val paragraphs = (1..PARAGRAPHS).joinToString("\n") {
                "<p>Paragraph $it. The quick brown fox jumps over the lazy dog while the engine lays " +
                    "out this line, and the letters of the face decide how far it reaches.</p>"
            }
            entry(
                "c1.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title>
<style type="text/css">$css</style></head>
<body>$paragraphs</body></html>""",
            )
        }
        return file
    }

    private companion object {
        const val ROBOTO = "Roboto"
        const val LITERATA = "Literata"
        const val PARAGRAPHS = 40
        const val WIDTH = 600
        const val HEIGHT = 800
        const val DENSITY = 320
    }
}
