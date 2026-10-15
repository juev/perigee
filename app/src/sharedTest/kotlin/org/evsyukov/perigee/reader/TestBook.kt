package org.evsyukov.perigee.reader

import android.util.Base64
import java.io.File

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
}
