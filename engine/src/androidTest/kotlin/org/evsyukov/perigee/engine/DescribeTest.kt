package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** What a book file tells a list of books, read without opening it for reading. */
@RunWith(AndroidJUnit4::class)
class DescribeTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = File(context.cacheDir, "described").apply { mkdirs() }

    private fun describe(file: File, name: String = file.name): BookDescription? = runBlocking {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { Engine.describe(context, it, name) }
    }

    private fun cover(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(COVER_WIDTH, COVER_HEIGHT, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun assertCover(expected: Int, description: BookDescription) {
        val bytes = description.cover
        assertNotNull("the book has a cover", bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes!!.size)
        assertEquals(COVER_WIDTH, bitmap.width)
        assertEquals(COVER_HEIGHT, bitmap.height)
        assertEquals(expected, bitmap.getPixel(1, 1))
    }

    @Test
    fun fb2TellsTitleAuthorsSeriesAndCover() {
        val book = describe(fb2(File(dir, "saga.fb2"), cover(Color.RED)))!!
        assertEquals("Вторая книга", book.title)
        assertEquals(listOf("Иван Петрович Сидоров", "Anna Smith"), book.authors)
        assertEquals("ru", book.language)
        assertEquals("Большая сага", book.series)
        assertEquals("2", book.seriesNumber)
        assertCover(Color.RED, book)
    }

    @Test
    fun epubTellsTitleAuthorsSeriesAndCover() {
        val book = describe(epub(File(dir, "saga.epub"), cover(Color.BLUE)))!!
        assertEquals("Second Book", book.title)
        assertEquals("a comma inside a name does not split it", listOf("Smith, Anna", "John Doe"), book.authors)
        assertEquals("en", book.language)
        assertEquals("Long Saga", book.series)
        assertEquals("2", book.seriesNumber)
        assertCover(Color.BLUE, book)
    }

    @Test
    fun bookWithoutCoverOrSeriesHasNone() {
        val book = describe(TestBooks.fb2(dir))!!
        assertEquals("Perigee Test Book", book.title)
        assertEquals(listOf("Test Author"), book.authors)
        assertEquals("", book.series)
        assertEquals("", book.seriesNumber)
        assertNull(book.cover)
    }

    @Test
    fun describingDoesNotFillTheEngineCache() {
        // A whole folder of books is described in one go; each must not leave a parsed copy behind.
        val cache = File(context.cacheDir, "crengine")
        describe(TestBooks.epub(dir))
        val before = cache.list().orEmpty().toSet()
        describe(fb2(File(dir, "uncached.fb2"), cover(Color.GREEN)))
        describe(TestBooks.fb2(dir))
        assertEquals(before, cache.list().orEmpty().toSet())
    }

    @Test
    fun describedBookStillOpensForReading() {
        val file = TestBooks.epub(dir)
        describe(file)
        EngineDocument.open(context, file, 600, 800).use { doc ->
            assertEquals("Perigee Test Book", doc.info().title)
            assertEquals(true, doc.pageCount > 1)
        }
    }

    @Test
    fun fileThatIsNotABookIsRefused() {
        assertNull("an empty file", describe(File(dir, "empty.epub").apply { writeBytes(ByteArray(0)) }))
        val noise = File(dir, "noise.epub").apply { writeBytes(ByteArray(4096) { (it * 31 % 251).toByte() }) }
        // Whatever the engine makes of noise, it must come back without a crash.
        describe(noise)
    }

    private fun fb2(file: File, cover: ByteArray): File = file.apply {
        writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description><title-info>
  <author><first-name>Иван</first-name><middle-name>Петрович</middle-name><last-name>Сидоров</last-name></author>
  <author><first-name>Anna</first-name><last-name>Smith</last-name></author>
  <book-title>Вторая книга</book-title>
  <coverpage><image l:href="#cover.png"/></coverpage>
  <lang>ru</lang>
  <sequence name="Большая сага" number="2"/>
</title-info></description>
<body>
<section><title><p>Глава первая</p></title>
${(1..40).joinToString("\n") { "<p>Абзац номер $it этой книги, достаточно длинный для одной строки.</p>" }}
</section>
</body>
<binary id="cover.png" content-type="image/png">${Base64.encodeToString(cover, Base64.NO_WRAP)}</binary>
</FictionBook>""",
        )
    }

    private fun epub(file: File, cover: ByteArray): File {
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

            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""".toByteArray(),
            )
            entry(
                "content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Second Book</dc:title>
    <dc:creator>Smith, Anna</dc:creator>
    <dc:creator>John Doe</dc:creator>
    <dc:language>en</dc:language>
    <dc:identifier id="id">perigee-described-epub</dc:identifier>
    <meta name="cover" content="cover-image"/>
    <meta name="calibre:series" content="Long Saga"/>
    <meta name="calibre:series_index" content="2"/>
  </metadata>
  <manifest>
    <item id="cover-image" href="images/cover.png" media-type="image/png"/>
    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine><itemref idref="c1"/></spine>
</package>""".toByteArray(),
            )
            entry("images/cover.png", cover)
            entry(
                "c1.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head>
<body><h1>One</h1><p>The only chapter of the second book.</p></body></html>""".toByteArray(),
            )
        }
        return file
    }

    private companion object {
        const val COVER_WIDTH = 30
        const val COVER_HEIGHT = 40
    }
}
