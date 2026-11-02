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

    /** A word that opens the second paragraph of the footnote, when the note has one. */
    const val NOTE_SECOND_WORD = "okapis"

    private const val NOTE = "A footnote about zebras, shown under the text that cites it."

    /** A second paragraph that takes the note to a few lines. */
    private val NOTE_MORE = "$NOTE_SECOND_WORD and giraffes take this note on for a few more lines. ".repeat(4)

    private fun paragraph(chapter: Int, n: Int) =
        "Chapter $chapter, paragraph $n. " +
            "The quick brown fox jumps over the lazy dog while the engine lays out this line. ".repeat(3)

    /**
     * Three chapters with a table of contents. The first paragraph links to a
     * footnote kept at the end of the first chapter; [longNote] gives the note
     * a second paragraph.
     */
    fun epub(dir: File, longNote: Boolean = false): File {
        val file = File(dir, if (longNote) "test-long-note.epub" else "test.epub")
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
                    val more = if (longNote) "<p>$NOTE_MORE</p>" else ""
                    """<aside epub:type="footnote" id="fn1"><p>$NOTE</p>$more</aside>"""
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
<section id="n1"><title><p>1</p></title><p>$NOTE</p></section>
</body>
</FictionBook>""",
            )
        }
    }

    /** Occurs twice in a book of [notesEpub] or [notesFb2]: in the first paragraph of the text, then in its first note. */
    const val NOTE_WORD = "zebras"

    /** The last word of the first note when the note is a long one. */
    const val NOTE_LAST_WORD = "quaggas"

    /** The first paragraph of a book with notes: the same word in the text as in the note it cites. */
    private const val NOTE_LEAD = "Striped $NOTE_WORD graze in this paragraph of the text,"

    /** Every sixth paragraph of a chapter cites a note of its own, so the size of notes moves the pages of the whole book. */
    private fun citesNote(n: Int) = n % 6 == 0

    private fun noteText(chapter: Int, n: Int) =
        "Note $chapter.$n tells of the striped horses of the plain, kept under the text that cites it. ".repeat(2)

    /** The first note, a long one ending in [NOTE_LAST_WORD] when [long]. */
    private fun firstNote(long: Boolean): List<String> =
        listOf(NOTE) + if (long) listOf(NOTE_MORE.repeat(LONG_NOTE_REPEATS / 4) + "Then come the $NOTE_LAST_WORD.") else emptyList()

    /** How many times the long first note says [NOTE_SECOND_WORD]: [NOTE_MORE] says it four times. */
    const val LONG_NOTE_REPEATS = 32

    /**
     * Three chapters whose paragraphs cite notes marked as EPUB 3 footnotes, linked from the text and kept at
     * the end of each chapter. [styled] gives the book a linked stylesheet of its own that sizes the notes
     * down, on an ancestor of them, on the notes themselves and on their paragraphs, besides an inline style
     * on the first note; it also sets its root at 62.5 % and its body back at 1.6 rem, as books often do.
     */
    fun notesEpub(dir: File, styled: Boolean = false, long: Boolean = false): File {
        val file = File(dir, "notes${if (styled) "-styled" else ""}${if (long) "-long" else ""}.epub")
        val link = if (styled) """<link rel="stylesheet" type="text/css" href="style.css"/>""" else ""
        val chapters = (1..CHAPTERS).map { c ->
            val lead = if (c == 1) """<p>$NOTE_LEAD <a epub:type="noteref" href="#n1">$LINK_TEXT</a> leads to a note.</p>""" else ""
            val body = (1..PARAGRAPHS).joinToString("\n") { n ->
                val ref = if (citesNote(n)) """ <a epub:type="noteref" href="#n$c-$n">[$c.$n]</a>""" else ""
                "<p>${paragraph(c, n)}$ref</p>"
            }
            val first = if (c == 1) {
                val inline = if (styled) " style=\"font-size: 50%\"" else ""
                """<aside epub:type="footnote" id="n1"$inline>${firstNote(long).joinToString("") { "<p>$it</p>" }}</aside>"""
            } else {
                ""
            }
            val asides = first + (1..PARAGRAPHS).filter(::citesNote).joinToString("\n") { n ->
                """<aside epub:type="footnote" id="n$c-$n"><p>${noteText(c, n)}</p></aside>"""
            }
            val notes = if (styled) "<div class=\"notes\">$asides</div>" else asides
            "c$c.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Chapter $c</title>$link</head>
<body><h1>Chapter $c</h1>
$lead
$body
$notes
</body></html>"""
        }
        val css = """html { font-size: 62.5%; }
body { font-size: 1.6rem; }
.notes { font-size: 60%; }
aside { font-size: 0.7em; }
aside p { font-size: 0.8em; }
"""
        val manifest = chapters.indices.joinToString("\n") { """<item id="c${it + 1}" href="c${it + 1}.xhtml" media-type="application/xhtml+xml"/>""" } +
            if (styled) "\n" + """<item id="css" href="style.css" media-type="text/css"/>""" else ""
        val spine = chapters.indices.joinToString("\n") { """<itemref idref="c${it + 1}"/>""" }
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Perigee Notes Book</dc:title>
    <dc:creator>Test Author</dc:creator>
    <dc:language>en</dc:language>
    <dc:identifier id="id">perigee-notes-epub</dc:identifier>
  </metadata>
  <manifest>
$manifest
  </manifest>
  <spine>
$spine
  </spine>
</package>"""
        return zipEpub(file, listOf("content.opf" to opf) + chapters + if (styled) listOf("style.css" to css) else emptyList())
    }

    /**
     * The book of [notesEpub] as FB2, the notes in the notes body. [styled] nests the notes in a section of
     * their own, which the compact size compounds over, and gives the book a stylesheet that sizes the notes
     * body and its paragraphs down. A [stamp] makes the file differ from every other, as a book new to the
     * engine's cache does.
     */
    fun notesFb2(dir: File, styled: Boolean = false, long: Boolean = false, stamp: String? = null): File {
        val name = if (stamp != null) "notes-cached" else "notes${if (styled) "-styled" else ""}${if (long) "-long" else ""}"
        val sections = (1..CHAPTERS).joinToString("\n") { c ->
            val lead = if (c == 1) """<p>$NOTE_LEAD <a l:href="#n1" type="note">$LINK_TEXT</a> leads to a note.</p>""" else ""
            val body = (1..PARAGRAPHS).joinToString("\n") { n ->
                val ref = if (citesNote(n)) """ <a l:href="#n$c-$n" type="note">[$c.$n]</a>""" else ""
                "<p>${paragraph(c, n)}$ref</p>"
            }
            "<section><title><p>Chapter $c</p></title>\n$lead\n$body\n</section>"
        }
        val notes = """<section id="n1"><title><p>1</p></title>${firstNote(long).joinToString("") { "<p>$it</p>" }}</section>""" +
            (1..CHAPTERS).joinToString("\n") { c ->
                (1..PARAGRAPHS).filter(::citesNote).joinToString("\n") { n ->
                    """<section id="n$c-$n"><title><p>$c.$n</p></title><p>${noteText(c, n)}</p></section>"""
                }
            }
        val stylesheet = if (styled) {
            """<stylesheet type="text/css">body[name="notes"] { font-size: 60%; } body[name="notes"] p { font-size: 0.8em; }</stylesheet>"""
        } else {
            ""
        }
        val notesBody = if (styled) "<section><title><p>Notes</p></title>$notes</section>" else notes
        return File(dir, "$name.fb2").apply {
            writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
$stylesheet
<description><title-info>
  <author><first-name>Test</first-name><last-name>Author</last-name></author>
  <book-title>Perigee Notes Book${stamp?.let { " $it" }.orEmpty()}</book-title><lang>en</lang>
</title-info></description>
<body>
$sections
</body>
<body name="notes">
$notesBody
</body>
</FictionBook>""",
            )
        }
    }

    /**
     * Hands [block] a copy of [book], the same bytes under a name of its own, and deletes it afterwards. The
     * engine finds a cached layout by the file's name, CRC and flags, and looks for one even when it is told not
     * to write any (`cacheable = false`), so a reference opened under the book's own name could be read from the
     * entry under test. Only references are opened under this name, always with the cache off, so the engine
     * never has an entry for it. The name stays under 25 characters, which the engine would shorten.
     */
    fun <T> uncachedCopy(context: Context, book: File, block: (File) -> T): T {
        val copy = File(File(context.cacheDir, "uncached-books").apply { mkdirs() }, "uncached-ref.${book.extension}")
        book.copyTo(copy, overwrite = true)
        return try {
            block(copy)
        } finally {
            copy.delete()
        }
    }

    /** An EPUB of [entries] besides the mimetype and the container, which point to `content.opf`. */
    private fun zipEpub(file: File, entries: List<Pair<String, String>>): File {
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
            val container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
            for ((name, text) in listOf("META-INF/container.xml" to container) + entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
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
