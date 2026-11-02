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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
 *
 * What was drawn is read from the page itself: a book that names a face, with the override on, must
 * draw exactly what a book naming no face draws — same face, same text, same page.
 *
 * A font the book carries is always there for its stylesheet (issue #149): with the override off it is
 * the face drawn, read from the engine by the family the book declares it under, and on the page against
 * the same book without the file. The font is PT Serif from the test assets (OFL, `assets/fonts/OFL.txt`).
 */
@RunWith(AndroidJUnit4::class)
class FaceOverBookCssTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = File(context.cacheDir, "books").apply { mkdirs() }

    /** A book that names a face in a list ending in a generic, as Calibre and the stores write one. */
    private val listed: File by lazy {
        epub("list-ending-in-a-generic.epub", """p { font-family: "PT Serif", serif; }""")
    }

    /** A book that names one face and no generic. */
    private val named: File by lazy { epub("one-named-face.epub", """p { font-family: "PT Serif"; }""") }

    /** The same book with nothing about faces: what the reader's choice draws when nothing contests it. */
    private val plain: File by lazy { epub("no-font-family.epub", null) }

    /** A book that carries its face and names it from a linked stylesheet, with a block of code. */
    private val carried: File by lazy { epub("carries-its-face.epub", CARRIED_CSS, carry = true, code = true) }

    /** The same book without the font file: its stylesheet names a face nothing provides. */
    private val lacking: File by lazy { epub("names-a-face-it-lacks.epub", CARRIED_CSS, code = true) }

    /** A book naming no face, with the same code. */
    private val plainWithCode: File by lazy { epub("no-font-family-with-code.epub", null, code = true) }

    @Test
    fun theFaceChosenBeatsAListThatEndsInAGeneric() = assertTheChosenFaceIsDrawn(listed)

    @Test
    fun theFaceChosenBeatsABookThatNamesOneFont() = assertTheChosenFaceIsDrawn(named)

    /** The switch in the reading settings, off: the book keeps the face it names. */
    @Test
    fun aBookKeepsItsOwnFaceWhenTheReaderSaysSo() {
        val chosen = page(named, LITERATA, override = true)
        val own = page(named, LITERATA, override = false)
        assertNotEquals("the book's own face with the switch off: $chosen", chosen, own)
    }

    /** The path the app takes: the reader changes the face with the book already open. */
    @Test
    fun theFaceChangedOnAnOpenBookIsDrawn() {
        EngineDocument.open(
            context, named, WIDTH, HEIGHT, EngineSettings(fontFace = LITERATA, overrideBookFont = false), DENSITY,
        ).use { doc ->
            val bookFace = hash(doc)
            doc.apply(EngineSettings(fontFace = LITERATA))
            val ourFace = hash(doc)
            doc.apply(EngineSettings(fontFace = PT_SANS))
            assertNotEquals("one face replaced by another on the open book: $ourFace", ourFace, hash(doc))
            assertNotEquals("and the book's own face was not that: $bookFace", bookFace, ourFace)
        }
    }

    /** With the override off, the face drawn is the font the book carries, not a face the engine falls back to. */
    @Test
    fun aFontTheBookCarriesIsDrawnWithTheOverrideOff() {
        val (face, ownPage) = open(carried, ownFace).use { it.drawnFace to hash(it) }
        assertEquals("the font the book carries, by the family its @font-face gives", CARRIED_FAMILY, face)
        // Without the file the same stylesheet falls back to another face, as before.
        val (fallback, fallbackPage) = open(lacking, ownFace).use { it.drawnFace to hash(it) }
        assertNotEquals("a book without the file draws another face", CARRIED_FAMILY, fallback)
        assertNotEquals("and another page: $ownPage", ownPage, fallbackPage)
    }

    /** With the override on, the reader's face is drawn over the font the book carries, and code keeps its own. */
    @Test
    fun theFaceChosenBeatsAFontTheBookCarries() {
        val (face, chosen) = open(carried, chosenFace).use { it.drawnFace to hash(it) }
        assertEquals(LITERATA, face)
        assertEquals(
            "the page a book that carries nothing draws, its code included",
            open(plainWithCode, chosenFace).use(::hash), chosen,
        )
    }

    /**
     * The override switched on and off on the open book. Each change keeps the place at the top of the page
     * shown before it, and the page is the one a book opened afresh with the new settings shows at that place.
     */
    @Test
    fun theOverrideSwitchedOnAnOpenBookThatCarriesItsFaceKeepsThePlace() = open(carried, ownFace).use { doc ->
        assertTrue(doc.goToPage(doc.pageCount / 2))
        for ((settings, face) in listOf(chosenFace to LITERATA, ownFace to CARRIED_FAMILY)) {
            val place = doc.xpointer()
            doc.apply(settings)
            assertEquals(face, doc.drawnFace)
            assertEquals("the page shown holds $place", doc.pageOf(place), doc.currentPage)
            val fresh = open(carried, settings).use { other ->
                assertTrue(other.goToXPointer(place))
                other.currentPage to hash(other)
            }
            assertEquals("the page of a fresh opening at $place, drawn in $face", fresh, doc.currentPage to hash(doc))
        }
    }

    /** With the override on, the book's page is the page a book naming no face draws. */
    private fun assertTheChosenFaceIsDrawn(book: File) {
        val chosen = page(book, LITERATA, override = true)
        assertEquals(
            "a book naming a face draws what a book naming none does",
            page(plain, LITERATA, override = true), chosen,
        )
        assertNotEquals("and not the face the book names", page(book, LITERATA, override = false), chosen)
    }

    private fun page(book: File, face: String, override: Boolean): Int =
        EngineDocument.open(
            context, book, WIDTH, HEIGHT, EngineSettings(fontFace = face, overrideBookFont = override), DENSITY,
        ).use(::hash)

    private val ownFace = EngineSettings(fontFace = LITERATA, overrideBookFont = false)
    private val chosenFace = EngineSettings(fontFace = LITERATA)

    /** Opened without the engine's cache, so that each opening lays the book out afresh. */
    private fun open(book: File, settings: EngineSettings): EngineDocument =
        EngineDocument.open(context, book, WIDTH, HEIGHT, settings, DENSITY, cacheable = false)

    private fun hash(doc: EngineDocument): Int {
        val page = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        doc.renderPage(page)
        return ByteBuffer.allocate(page.byteCount).also { page.copyPixelsToBuffer(it) }.array().contentHashCode()
    }

    /**
     * An EPUB whose chapter takes its rules from a stylesheet of its own, listed in the package as a
     * book does; a null [css] leaves the book with nothing about faces. [carry] puts [CARRIED_FONT] in the
     * package, [code] a block of code after the second paragraph.
     */
    private fun epub(name: String, css: String?, carry: Boolean = false, code: Boolean = false): File {
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
            val stylesheet = if (css == null) "" else
                """  <item id="css" href="style.css" media-type="text/css"/>"""
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
    <dc:title>Face Over Book CSS</dc:title>
    <dc:creator>Test Author</dc:creator>
    <dc:language>en</dc:language>
    <dc:identifier id="id">perigee-face-over-book-css</dc:identifier>
  </metadata>
  <manifest>
  <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
$stylesheet
$font
  </manifest>
  <spine><itemref idref="c1"/></spine>
</package>""",
            )
            if (css != null) entry("style.css", css)
            if (carry) {
                zip.putNextEntry(ZipEntry("fonts/$CARRIED_FONT"))
                InstrumentationRegistry.getInstrumentation().context.assets.open("fonts/$CARRIED_FONT").use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val paragraphs = (1..PARAGRAPHS).map {
                "<p>Paragraph $it. The quick brown fox jumps over the lazy dog while the engine lays " +
                    "out this line, and the letters of the face decide how far it reaches.</p>"
            }.toMutableList()
            if (code) paragraphs.add(2, "<pre><code>val face = \"monospace\"\nprintln(face)</code></pre>")
            val link = if (css == null) "" else """<link rel="stylesheet" type="text/css" href="style.css"/>"""
            entry(
                "c1.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title>$link</head>
<body>${paragraphs.joinToString("\n")}</body></html>""",
            )
        }
        return file
    }

    private companion object {
        const val LITERATA = "Literata"
        const val PT_SANS = "PT Sans"
        const val PARAGRAPHS = 40
        const val WIDTH = 600
        const val HEIGHT = 800
        const val DENSITY = 320
        const val CARRIED_FONT = "PT_Serif-Web-Regular.ttf"

        /** The family the book declares its font under; the file itself names PT Serif, which the app ships too. */
        const val CARRIED_FAMILY = "Book Serif"
        const val CARRIED_CSS = """@font-face { font-family: "$CARRIED_FAMILY"; src: url(fonts/$CARRIED_FONT); }
p { font-family: "$CARRIED_FAMILY", serif; }"""
    }
}
