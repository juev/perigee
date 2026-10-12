package org.evsyukov.perigee.engine

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Books generated on the fly, so the tests carry no third-party texts. */
internal object TestBooks {
    private const val CHAPTERS = 3
    private const val PARAGRAPHS = 60

    private fun paragraph(chapter: Int, n: Int) =
        "Chapter $chapter, paragraph $n. " +
            "The quick brown fox jumps over the lazy dog while the engine lays out this line. ".repeat(3)

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
$items
  </manifest>
  <spine>
$spine
  </spine>
</package>""",
            )
            for (c in 1..CHAPTERS) {
                val body = (1..PARAGRAPHS).joinToString("\n") { "<p>${paragraph(c, it)}</p>" }
                entry(
                    "c$c.xhtml",
                    """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter $c</title></head>
<body><h1>Chapter $c</h1>
$body
</body></html>""",
                )
            }
        }
        return file
    }

    fun fb2(dir: File): File {
        val sections = (1..CHAPTERS).joinToString("\n") { c ->
            val body = (1..PARAGRAPHS).joinToString("\n") { "<p>${paragraph(c, it)}</p>" }
            "<section><title><p>Chapter $c</p></title>\n$body\n</section>"
        }
        return File(dir, "test.fb2").apply {
            writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
<description><title-info>
  <author><first-name>Test</first-name><last-name>Author</last-name></author>
  <book-title>Perigee Test Book</book-title><lang>en</lang>
</title-info></description>
<body>
$sections
</body>
</FictionBook>""",
            )
        }
    }
}
