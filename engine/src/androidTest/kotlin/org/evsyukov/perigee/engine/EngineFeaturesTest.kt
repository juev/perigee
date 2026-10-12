package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Contents, selection, search, highlights, links and the layout settings. */
@RunWith(AndroidJUnit4::class)
class EngineFeaturesTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = File(context.cacheDir, "books").apply { mkdirs() }
    private val base = EngineSettings(fontFace = TestBooks.TEST_FONT)

    @Before
    fun registerTestFont() {
        runBlocking { Engine.registerFonts(context, TestBooks.fonts(context)) }
    }

    private fun open(file: File, settings: EngineSettings = base) =
        EngineDocument.open(context, file, WIDTH, HEIGHT, settings, DENSITY)

    private fun EngineDocument.render(highlights: List<Highlight> = emptyList()): Bitmap =
        Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also { renderPage(it, highlights) }

    /** Goes to the first match of [phrase] and returns it with its place on that page. */
    private fun EngineDocument.locate(phrase: String): Pair<SearchHit, Rect> {
        val hit = search(phrase).first()
        assertTrue(goToPage(hit.page))
        return hit to rects(hit.range.start, hit.range.end).first()
    }

    // --- table of contents

    @Test
    fun epubContentsListTheChaptersInOrder() = open(TestBooks.epub(dir)).use { doc ->
        val toc = doc.toc()
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), toc.map { it.title })
        assertTrue("all chapters are top-level: $toc", toc.all { it.depth == 1 })
        assertEquals("the book opens on its first chapter", 0, toc.first().page)
        assertEquals("chapters follow one another: $toc", toc.map { it.page }.sorted(), toc.map { it.page })
        assertEquals("each chapter starts on its own page", CHAPTERS, toc.map { it.page }.distinct().size)
    }

    @Test
    fun contentsEntryLeadsToItsPage() = open(TestBooks.epub(dir)).use { doc ->
        val third = doc.toc()[2]
        assertTrue(doc.goToXPointer(third.xpointer))
        assertEquals(third.page, doc.currentPage)
        assertEquals(third.page, doc.pageOf(third.xpointer))
    }

    @Test
    fun fb2ContentsComeFromSectionTitles() = open(TestBooks.fb2(dir)).use { doc ->
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), doc.toc().take(CHAPTERS).map { it.title })
    }

    // --- pages and positions

    @Test
    fun pageAndXPointerConvertBothWays() = open(TestBooks.epub(dir)).use { doc ->
        val page = doc.pageCount / 2
        val xpointer = checkNotNull(doc.xpointerOfPage(page))
        assertEquals(page, doc.pageOf(xpointer))
        assertNull(doc.xpointerOfPage(doc.pageCount))
        assertNull(doc.pageOf("/body/DocFragment[99]/body/p[1]/text().0"))
    }

    // --- search

    @Test
    fun searchFindsAPhraseSplitByMarkup() = open(TestBooks.epub(dir)).use { doc ->
        val hits = doc.search(TestBooks.SPLIT_WORD)
        assertEquals("the word occurs once", 1, hits.size)
        val hit = hits.single()
        assertEquals(TestBooks.SPLIT_WORD, hit.range.text)
        assertEquals(TestBooks.SPLIT_WORD, doc.textBetween(hit.range.start, hit.range.end))
        val toc = doc.toc()
        assertTrue("it is in the second chapter, page ${hit.page}", hit.page in toc[1].page until toc[2].page)
        assertTrue("words before the match: '${hit.before}'", hit.before.trim().endsWith("The"))
        assertTrue("words after the match: '${hit.after}'", hit.after.trim().startsWith("of this chapter"))
    }

    @Test
    fun searchIgnoresCaseUnlessToldOtherwise() = open(TestBooks.epub(dir)).use { doc ->
        val shouted = TestBooks.SPLIT_WORD.uppercase()
        assertEquals(1, doc.search(shouted).size)
        assertEquals(0, doc.search(shouted, caseInsensitive = false).size)
    }

    @Test
    fun searchReturnsMatchesInReadingOrderUpToTheLimit() = open(TestBooks.epub(dir)).use { doc ->
        val hits = doc.search("quick brown", maxHits = 7)
        assertEquals(7, hits.size)
        assertEquals("pages do not go backwards", hits.map { it.page }.sorted(), hits.map { it.page })
        assertTrue(doc.search("no such words here").isEmpty())
        assertTrue(doc.search("").isEmpty())
    }

    @Test
    fun searchFindsRussianWordsWhateverTheCase() = open(TestBooks.russianFb2(dir)).use { doc ->
        assertTrue(doc.search("ЖЕЛЕЗНОДОРОЖНОЕ").isNotEmpty())
    }

    // --- selection

    @Test
    fun touchingAWordSelectsThatWord() = open(TestBooks.epub(dir)).use { doc ->
        val (_, rect) = doc.locate("brown")
        val selected = checkNotNull(doc.select(rect.centerX(), rect.centerY()))
        assertEquals("brown", selected.text)
        assertEquals("the range reads back as the same text", "brown", doc.textBetween(selected.start, selected.end))
    }

    @Test
    fun draggingSelectsWholeWordsBetweenTheTwoPoints() = open(TestBooks.epub(dir)).use { doc ->
        val (first, from) = doc.locate("quick")
        // The next word on the same line, three words further.
        val to = doc.search("jumps").first { it.page == first.page }
            .let { doc.rects(it.range.start, it.range.end).first() }
        assertEquals("both words are on one line", from.top, to.top)

        val forward = checkNotNull(doc.select(from.centerX(), from.centerY(), to.centerX(), to.centerY()))
        assertEquals("quick brown fox jumps", forward.text)

        val backward = checkNotNull(doc.select(to.centerX(), to.centerY(), from.centerX(), from.centerY()))
        assertEquals("dragging right to left gives the same range", forward, backward)
    }

    // --- where a range is on the page

    @Test
    fun rangeRectsCoverTheTextOnThePageShown() = open(TestBooks.epub(dir)).use { doc ->
        val (hit, rect) = doc.locate("lazy dog")
        assertTrue("inside the page: $rect", Rect(0, 0, WIDTH, HEIGHT).contains(rect))
        assertTrue("wider than tall, as two words on a line are: $rect", rect.width() > rect.height())

        val page = doc.render()
        val inside = pixels(page, rect)
        assertTrue("there is text under the rectangle", inside.any { luminance(it) < 96 })

        doc.goToPage(hit.page + 1)
        assertTrue("nothing to show on another page", doc.rects(hit.range.start, hit.range.end).isEmpty())
    }

    @Test
    fun aRangeOverSeveralLinesGetsARectPerLine() = open(TestBooks.epub(dir)).use { doc ->
        val first = doc.search("Chapter 1, paragraph 3.").first()
        doc.goToPage(first.page)
        val next = doc.search("Chapter 1, paragraph 4.").first()
        val rects = doc.rects(first.range.start, next.range.start)
        assertTrue("a paragraph of three sentences takes several lines: $rects", rects.size >= 3)
        assertEquals("lines go down the page", rects.map { it.top }.sorted(), rects.map { it.top })
    }

    // --- highlights

    @Test
    fun highlightTintsThePageUnderTheTextAndLeavesTheRestAlone() = open(TestBooks.epub(dir)).use { doc ->
        val (hit, rect) = doc.locate("lazy dog")
        val plain = doc.render()
        val marked = doc.render(listOf(Highlight(hit.range.start, hit.range.end, YELLOW)))

        val before = pixels(plain, rect)
        val after = pixels(marked, rect)
        val paper = before.indices.filter { luminance(before[it]) > 200 }
        assertTrue("the paper turned yellow", paper.all { blue(after[it]) < 64 && red(after[it]) > 200 })
        val ink = before.indices.filter { luminance(before[it]) < 64 }
        assertTrue("there is text in the range", ink.isNotEmpty())
        assertTrue("the text stayed dark", ink.all { luminance(after[it]) < 64 })

        val above = Rect(0, 0, WIDTH, rect.top)
        assertTrue("nothing changed outside the range", pixels(plain, above).contentEquals(pixels(marked, above)))
    }

    @Test
    fun highlightShowsOnADarkPage() = open(TestBooks.epub(dir), base.copy(colors = PageColors.Night)).use { doc ->
        val (hit, rect) = doc.locate("lazy dog")
        val plain = pixels(doc.render(), rect)
        val marked = pixels(doc.render(listOf(Highlight(hit.range.start, hit.range.end, YELLOW))), rect)
        val paper = plain.indices.filter { luminance(plain[it]) < 16 }
        assertTrue("the dark page took the colour", paper.all { red(marked[it]) > blue(marked[it]) + 32 })
    }

    @Test
    fun highlightOnAnotherPageDrawsNothing() = open(TestBooks.epub(dir)).use { doc ->
        val hit = doc.search(TestBooks.SPLIT_WORD).single()
        doc.goToPage(0)
        assertTrue(doc.render().sameAs(doc.render(listOf(Highlight(hit.range.start, hit.range.end, YELLOW)))))
    }

    // --- links

    @Test
    fun linkUnderATouchIsReportedWithItsTarget() = open(TestBooks.epub(dir)).use { doc ->
        val (_, rect) = doc.locate(TestBooks.LINK_TEXT)
        val link = checkNotNull(doc.linkAt(rect.centerX(), rect.centerY()))
        assertTrue("an internal link: ${link.href}", link.href.startsWith("#"))
        val target = checkNotNull(link.target)
        val note = doc.search("zebras").single()
        assertEquals("the target is the footnote", doc.pageOf(note.range.start), doc.pageOf(target))
        assertNotNull(doc.pageOf(link.source))
    }

    @Test
    fun plainTextIsNotALink() = open(TestBooks.epub(dir)).use { doc ->
        val (_, rect) = doc.locate("lazy dog")
        assertNull(doc.linkAt(rect.centerX(), rect.centerY()))
    }

    // --- footnotes

    @Test
    fun epubFootnoteIsShownOnThePageThatCitesIt() = footnoteMovesToTheCitingPage(TestBooks.epub(dir))

    @Test
    fun fb2FootnoteIsShownOnThePageThatCitesIt() = footnoteMovesToTheCitingPage(TestBooks.fb2(dir))

    private fun footnoteMovesToTheCitingPage(file: File) {
        val with = open(file, base.copy(footnotes = true)).use { it.render() }
        val without = open(file, base.copy(footnotes = false)).use { it.render() }
        // The first page cites the note; with footnotes on, the note takes the
        // bottom of that page and pushes body text off it.
        assertFalse("the first page changes when its footnote joins it", with.sameAs(without))
    }

    // --- layout settings

    @Test
    fun heavierWeightDarkensThePage() {
        val file = TestBooks.epub(dir)
        val regular = open(file, base.copy(fontWeight = 400)).use { it.render() }
        val bold = open(file, base.copy(fontWeight = 700)).use { it.render() }
        assertTrue("bolder strokes cover more of the page", ink(bold) > ink(regular) * 11 / 10)
    }

    @Test
    fun coloursOfThePageFollowTheSettings() = open(TestBooks.epub(dir), base.copy(colors = PageColors.Sepia)).use { doc ->
        val page = pixels(doc.render(), Rect(0, 0, WIDTH, HEIGHT))
        assertEquals("the margin has the page colour", PageColors.Sepia.background, page[0] and 0xFFFFFF)
        assertTrue("letters have the text colour", page.any { it and 0xFFFFFF == PageColors.Sepia.text })
        assertFalse("nothing is left pure white", page.any { it and 0xFFFFFF == 0xFFFFFF })
    }

    @Test
    fun alignmentChangesTheRightEdgeOfTheText() {
        val file = TestBooks.epub(dir)
        val justified = open(file, base.copy(textAlign = TextAlign.Justify, hyphenation = false)).use { it.render() }
        val ragged = open(file, base.copy(textAlign = TextAlign.Left, hyphenation = false)).use { it.render() }
        // Justified lines run to the margin; ragged ones stop where the words do.
        assertTrue(
            "rows reaching the right margin: ${rowsAtRightMargin(justified)} justified, ${rowsAtRightMargin(ragged)} ragged",
            rowsAtRightMargin(justified) > rowsAtRightMargin(ragged) * 2,
        )
    }

    @Test
    fun paragraphIndentCanBeForcedOnAndOff() {
        val file = TestBooks.epub(dir)
        val indented = open(file, base.copy(paragraphIndent = true)).use { it.render() }
        val flush = open(file, base.copy(paragraphIndent = false)).use { it.render() }
        assertFalse(indented.sameAs(flush))
    }

    @Test
    fun looserSettingsTakeMorePages() {
        val file = TestBooks.epub(dir)
        fun pages(settings: EngineSettings) = open(file, settings).use { it.pageCount }
        val tight = pages(base.copy(paragraphSpacingPercent = 0))
        assertTrue("paragraph spacing", pages(base.copy(paragraphSpacingPercent = 100)) > tight)
        assertTrue("line spacing", pages(base.copy(paragraphSpacingPercent = 0, lineSpacingPercent = 150)) > tight)
        assertTrue("margins", pages(base.copy(paragraphSpacingPercent = 0, marginHorizontal = 120)) > tight)
    }

    // --- fonts

    @Test
    fun registeredFontIsOfferedAndUsed() {
        assertTrue(TestBooks.TEST_FONT in Engine.facesOnEngineThread(context))
        val file = TestBooks.epub(dir)
        val serif = open(file, base).use { it.render() }
        val sans = open(file, base.copy(fontFace = EngineSettings.DEFAULT_FONT_FACE)).use { it.render() }
        assertFalse("the page is set in the chosen face", serif.sameAs(sans))
    }

    @Test
    fun offeredFontsLeaveOutTheSingleScriptOnes() {
        val faces = Engine.facesOnEngineThread(context)
        assertTrue("the default face is there: $faces", EngineSettings.DEFAULT_FONT_FACE in faces)
        assertTrue("a serif is there: $faces", "Noto Serif" in faces)
        val scripts = faces.filter { it.matches(Regex("Noto (Sans|Serif) .+")) }
        assertTrue("fonts of single scripts are not offered: $scripts", scripts.isEmpty())
    }

    @Test
    fun namedFamiliesAreReadFromTheFontConfig() {
        val config = """<?xml version="1.0" encoding="utf-8"?>
<familyset version="23">
    <family name="sans-serif">
        <font weight="400" style="normal">Roboto-Regular.ttf
            <axis tag="wght" stylevalue="400" />
        </font>
    </family>
    <alias name="arial" to="sans-serif" />
    <family name="serif">
        <font weight="400" style="normal" postScriptName="NotoSerif">NotoSerif-Regular.ttf</font>
        <font weight="700" style="normal">NotoSerif-Bold.ttf</font>
    </family>
    <family lang="und-Armn" variant="elegant">
        <font weight="400" style="normal">NotoSerifArmenian-VF.ttf</font>
    </family>
</familyset>"""
        assertEquals(
            setOf("Roboto-Regular.ttf", "NotoSerif-Regular.ttf", "NotoSerif-Bold.ttf"),
            SystemFonts.namedFamilyFiles(config.byteInputStream()),
        )
    }

    // --- helpers

    private fun pixels(bitmap: Bitmap, rect: Rect): IntArray =
        IntArray(rect.width() * rect.height()).also {
            bitmap.getPixels(it, 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
        }

    private fun ink(bitmap: Bitmap): Int = pixels(bitmap, Rect(0, 0, WIDTH, HEIGHT)).count { luminance(it) < 128 }

    /** Pixel rows whose text ends within a few pixels of the right margin. */
    private fun rowsAtRightMargin(bitmap: Bitmap): Int {
        val all = pixels(bitmap, Rect(0, 0, WIDTH, HEIGHT))
        val margin = WIDTH - base.marginHorizontal
        return (0 until HEIGHT).count { y ->
            val end = (WIDTH - 1 downTo 0).firstOrNull { x -> luminance(all[y * WIDTH + x]) < 96 }
            end != null && end > margin - 8
        }
    }

    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF
    private fun luminance(argb: Int) = (red(argb) * 299 + ((argb shr 8) and 0xFF) * 587 + blue(argb) * 114) / 1000

    private companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val DENSITY = 320
        const val CHAPTERS = TestBooks.CHAPTERS
        const val YELLOW = 0xFFFF00
    }
}
