package org.evsyukov.perigee.reader

import java.io.File

/** A generated book of three chapters, so the tests carry no third-party texts. */
internal object TestBook {
    /** Occurs once, in the second chapter. */
    const val RARE_WORD = "needlework"

    fun write(file: File, title: String): File {
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
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
<description><title-info>
  <author><first-name>Test</first-name><last-name>Author</last-name></author>
  <book-title>$title</book-title><lang>en</lang>
</title-info></description>
<body>
$sections
</body>
</FictionBook>""",
        )
        return file
    }
}
