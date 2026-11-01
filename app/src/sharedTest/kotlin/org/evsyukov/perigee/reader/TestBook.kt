package org.evsyukov.perigee.reader

import android.util.Base64
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A generated book of three chapters, so the tests carry no third-party texts. */
internal object TestBook {
    /** Occurs once, in the second chapter. */
    const val RARE_WORD = "needlework"

    /**
     * @param author first and last name
     * @param cover a PNG file
     */
    fun write(
        file: File,
        title: String,
        author: String = "Test Author",
        series: String? = null,
        seriesNumber: Int = 0,
        cover: ByteArray? = null,
        about: String? = null,
    ): File {
        val sequence = series?.let { """<sequence name="$it" number="$seriesNumber"/>""" }.orEmpty()
        val coverPage = if (cover != null) """<coverpage><image l:href="#cover.png"/></coverpage>""" else ""
        val binary = cover?.let {
            """<binary id="cover.png" content-type="image/png">${Base64.encodeToString(it, Base64.NO_WRAP)}</binary>"""
        }.orEmpty()
        val sections = (1..3).joinToString("\n") { chapter ->
            val lead = if (chapter == 2) "<p>The $RARE_WORD of this chapter is found nowhere else.</p>" else ""
            val body = (1..60).joinToString("\n") { n ->
                "<p>Chapter $chapter, paragraph $n. " +
                    "The quick brown fox jumps over the lazy dog while the engine lays out this line. ".repeat(3) +
                    "</p>"
            }
            "<section><title><p>Chapter $chapter</p></title>\n$lead\n$body\n</section>"
        }
        file.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description><title-info>
  <author><first-name>${author.substringBefore(' ')}</first-name><last-name>${author.substringAfter(' ')}</last-name></author>
  <book-title>$title</book-title>${about?.let { "<annotation><p>$it</p></annotation>" }.orEmpty()}$coverPage<lang>en</lang>$sequence
</title-info></description>
<body>
$sections
</body>
$binary
</FictionBook>""",
        )
        return file
    }

    /** The same book as an EPUB: one chapter, named by the series as calibre names it. */
    fun epub(file: File, title: String, author: String = "Test Author", series: String? = null, seriesNumber: Int = 0): File {
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
            val sequence = series?.let {
                """<meta name="calibre:series" content="$it"/><meta name="calibre:series_index" content="$seriesNumber"/>"""
            }.orEmpty()
            entry(
                "content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>$title</dc:title>
    <dc:creator>$author</dc:creator>
    <dc:language>en</dc:language>
    <dc:identifier id="id">$title</dc:identifier>
    $sequence
  </metadata>
  <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine><itemref idref="c1"/></spine>
</package>""",
            )
            val body = (1..60).joinToString("\n") { n ->
                "<p>Paragraph $n. " + "The quick brown fox jumps over the lazy dog while the engine lays out this line. ".repeat(3) + "</p>"
            }
            entry(
                "c1.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head>
<body><h1>$title</h1>
$body
</body></html>""",
            )
        }
        return file
    }
}
