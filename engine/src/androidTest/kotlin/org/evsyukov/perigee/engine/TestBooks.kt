package org.evsyukov.perigee.engine

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Books generated on the fly, so the tests carry no third-party texts. */
internal object TestBooks {
    const val CHAPTERS = 3
    private const val PARAGRAPHS = 60

    /** A word written across an inline element, in the second chapter only. */
    const val SPLIT_WORD = "needlework"

    /** Text of the only link, in the first paragraph of the book. */
    const val LINK_TEXT = "seenote"

    private fun paragraph(chapter: Int, n: Int) =
        "Chapter $chapter, paragraph $n. " +
            "The quick brown fox jumps over the lazy dog while the engine lays out this line. ".repeat(3)

    /**
     * Three chapters with a table of contents. The first paragraph links to a
     * footnote kept at the end of the first chapter.
     */
    fun epub(dir: File): File {
        val file = File(dir, "test.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            // The EPUB container requires an uncompressed mimetype entry first.
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
            val items = (1..CHAPTERS).joinToString("\n") {
                """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>"""
            }
            val spine = (1..CHAPTERS).joinToString("\n") { """<itemref idref="c$it"/>""" }
            entry(
                "content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Perigee Test Book</dc:title>
    <dc:creator>Test Author</dc:creator>
    <dc:language>en</dc:language>
    <dc:identifier id="id">perigee-test-epub</dc:identifier>
  </metadata>
  <manifest>
<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
$items
  </manifest>
  <spine toc="ncx">
$spine
  </spine>
</package>""",
            )
            val navPoints = (1..CHAPTERS).joinToString("\n") {
                """<navPoint id="n$it" playOrder="$it"><navLabel><text>Chapter $it</text></navLabel>""" +
                    """<content src="c$it.xhtml"/></navPoint>"""
            }
            entry(
                "toc.ncx",
                """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="perigee-test-epub"/></head>
<docTitle><text>Perigee Test Book</text></docTitle>
<navMap>
$navPoints
</navMap>
</ncx>""",
            )
            for (c in 1..CHAPTERS) {
                val lead = when (c) {
                    1 -> """<p>Notes are kept apart, <a epub:type="noteref" href="#fn1">$LINK_TEXT</a> leads to one.</p>"""
                    2 -> "<p>The <i>needle</i>work of this chapter is found nowhere else.</p>"
                    else -> ""
                }
                val tail = if (c == 1) {
                    """<aside epub:type="footnote" id="fn1"><p>A footnote about zebras, shown under the text that cites it.</p></aside>"""
                } else {
                    ""
                }
                val body = (1..PARAGRAPHS).joinToString("\n") { "<p>${paragraph(c, it)}</p>" }
                entry(
                    "c$c.xhtml",
                    """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Chapter $c</title></head>
<body><h1>Chapter $c</h1>
$lead
$body
$tail
</body></html>""",
                )
            }
        }
        return file
    }

    /** Long words in a narrow column: the layout depends on Russian hyphenation. */
    fun russianFb2(dir: File): File {
        val sentence = "Железнодорожное сообщение восстанавливалось чрезвычайно медленно, " +
            "путешественники переговаривались вполголоса, разглядывая достопримечательности " +
            "провинциального городка и размышляя о непредвиденных обстоятельствах, " +
            "задержавших отправление пассажирского поезда. "
        val body = (1..40).joinToString("\n") { "<p>${sentence.repeat(2)}</p>" }
        return File(dir, "russian.fb2").apply {
            writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
<description><title-info>
  <author><first-name>Тест</first-name><last-name>Автор</last-name></author>
  <book-title>Контрольная страница</book-title><lang>ru</lang>
</title-info></description>
<body>
<section><title><p>Глава первая</p></title>
$body
</section>
</body>
</FictionBook>""",
            )
        }
    }

    /** Three sections; the first paragraph cites a note from the notes body. */
    fun fb2(dir: File): File {
        val sections = (1..CHAPTERS).joinToString("\n") { c ->
            val lead = if (c == 1) {
                """<p>Notes are kept apart, <a l:href="#n1" type="note">$LINK_TEXT</a> leads to one.</p>"""
            } else {
                ""
            }
            val body = (1..PARAGRAPHS).joinToString("\n") { "<p>${paragraph(c, it)}</p>" }
            "<section><title><p>Chapter $c</p></title>\n$lead\n$body\n</section>"
        }
        return File(dir, "test.fb2").apply {
            writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description><title-info>
  <author><first-name>Test</first-name><last-name>Author</last-name></author>
  <book-title>Perigee Test Book</book-title><lang>en</lang>
</title-info></description>
<body>
$sections
</body>
<body name="notes">
<section id="n1"><title><p>1</p></title><p>A footnote about zebras, shown under the text that cites it.</p></section>
</body>
</FictionBook>""",
            )
        }
    }

    /** PT Serif from the test assets: the same glyphs on every device, unlike the system fonts. */
    fun fonts(context: Context): List<File> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val dir = File(context.cacheDir, "test-fonts").apply { mkdirs() }
        return assets.list("fonts").orEmpty().filter { it.endsWith(".ttf") }.map { name ->
            File(dir, name).also { target ->
                assets.open("fonts/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    const val TEST_FONT = "PT Serif"
}
