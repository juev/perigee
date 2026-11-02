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

    // The size at which the first line of the test book holds "quick brown fox jumps" whole.
    private val roomyLine = base.copy(fontSize = 26)

    // Notes at the size of the main text, where the default sets them compact.
    private val fullNotes = base.copy(fullSizeFootnotes = true)

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

    // --- what the book says about itself

    @Test
    fun titleAuthorAndLanguageAreRead() {
        open(TestBooks.epub(dir)).use { doc ->
            assertEquals(BookInfo("Perigee Test Book", "Test Author", "en", ""), doc.info())
        }
        open(TestBooks.russianFb2(dir)).use { doc ->
            assertEquals("Контрольная страница", doc.info().title)
            assertEquals("ru", doc.info().language)
            assertTrue("got '${doc.info().authors}'", "Автор" in doc.info().authors)
        }
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
    fun touchingTheGapBetweenWordsSelectsAWordNotTheGap() = open(TestBooks.epub(dir)).use { doc ->
        val (first, quick) = doc.locate("quick")
        val brown = doc.search("brown").first { it.page == first.page }
            .let { doc.rects(it.range.start, it.range.end).first() }
        assertEquals("both words are on one line", quick.top, brown.top)

        val gap = (quick.right + brown.left) / 2
        val selected = checkNotNull(doc.select(gap, quick.centerY()))
        assertTrue("got '${selected.text}'", selected.text == "quick" || selected.text == "brown")
    }

    @Test
    fun everyTouchOnALineOfTextSelectsOneWord() = open(TestBooks.epub(dir)).use { doc ->
        val (_, rect) = doc.locate("lazy dog")
        for (x in 24 until WIDTH - 24 step 7) {
            val text = doc.select(x, rect.centerY())?.text ?: continue
            assertTrue("at x=$x got '$text'", text.isNotBlank() && ' ' !in text)
        }
    }

    @Test
    fun draggingSelectsWholeWordsBetweenTheTwoPoints() = open(TestBooks.epub(dir), roomyLine).use { doc ->
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

    @Test
    fun aSelectionFromGapToGapHasNoSpacesAtItsEnds() = open(TestBooks.epub(dir), roomyLine).use { doc ->
        val (first, quick) = doc.locate("quick")
        val onLine = { word: String -> doc.search(word).first { it.page == first.page }.let { doc.rects(it.range.start, it.range.end).first() } }
        val brown = onLine("brown")
        val jumps = onLine("jumps")
        assertEquals("the words are on one line", quick.top, jumps.top)

        // The server drops spaces at the ends of a quote; a selection must end where its copy does.
        val gap = (quick.right + brown.left) / 2
        for ((from, to) in listOf(gap to jumps.centerX(), jumps.centerX() to gap)) {
            val selected = checkNotNull(doc.select(from, quick.centerY(), to, quick.centerY()))
            assertEquals("from x=$from to x=$to got '${selected.text}'", selected.text.trim(), selected.text)
            assertEquals("the range reads back as the same text", selected.text, doc.textBetween(selected.start, selected.end))
        }
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

    // The engine reports where note text lies in the flow of the book, not in
    // the footnote area at the bottom of a page, so the note is measured there;
    // the same style sets it in both places.

    @Test
    fun epubFootnoteIsSetSmallerThanTheText() = footnoteIsSmallerThanTheText(TestBooks.epub(dir))

    @Test
    fun fb2FootnoteIsSetSmallerThanTheText() = footnoteIsSmallerThanTheText(TestBooks.fb2(dir))

    private fun footnoteIsSmallerThanTheText(file: File) = open(file).use { doc ->
        val (_, text) = doc.locate(TestBooks.LINK_TEXT)
        val (_, note) = doc.locate("zebras")
        assertTrue(
            "note lines are ${note.height()} px high, text lines ${text.height()} px",
            note.height() < text.height() * 9 / 10,
        )
    }

    @Test
    fun fb2NoteReferenceKeepsItsOwnSize() {
        // fb2.css sets the reference at 70 %; neither note size may reach it.
        for (notes in listOf(base, fullNotes)) {
            val full = open(TestBooks.epub(dir), notes).use { it.locate(TestBooks.LINK_TEXT).second.width() }
            val reference = open(TestBooks.fb2(dir), notes).use { it.locate(TestBooks.LINK_TEXT).second.width() }
            assertTrue("the reference is $reference px wide, $full px at full size; $notes", reference < full * 3 / 4)
            assertEquals(
                "and as wide whatever size the notes are",
                open(TestBooks.fb2(dir)).use { it.locate(TestBooks.LINK_TEXT).second.width() },
                reference,
            )
        }
    }

    // FB2 notes kept their paragraphs together before the setting was kept out
    // of them, so only EPUB is checked.
    @Test
    fun footnoteParagraphsTakeNoParagraphSpacing() {
        val file = TestBooks.epub(dir, longNote = true)
        for (full in listOf(false, true)) {
            fun gap(spacing: Int) = open(file, roomyLine.copy(paragraphSpacingPercent = spacing, fullSizeFootnotes = full)).use { doc ->
                val (firstHit, first) = doc.locate("zebras")
                val (secondHit, second) = doc.locate(TestBooks.NOTE_SECOND_WORD)
                assertEquals("the note is on one page, or the distance means nothing", firstHit.page, secondHit.page)
                second.top - first.top
            }
            assertEquals("from the first paragraph of the note to the second, full size $full", gap(0), gap(100))
        }
    }

    // --- footnotes at the size of the main text

    /** Where the [occurrence]th match of [word] is drawn, on the page it falls on. */
    private fun EngineDocument.word(word: String, occurrence: Int = 0): Rect {
        val hit = search(word)[occurrence]
        assertTrue(goToPage(hit.page))
        return rects(hit.range.start, hit.range.end).first()
    }

    /**
     * The book laid out from its bytes and these settings alone: a copy under a name the engine's cache has no
     * entry for ([TestBooks.uncachedCopy]), opened with the cache off, which keeps the engine from writing one.
     */
    private fun <T> fresh(file: File, settings: EngineSettings, block: (EngineDocument) -> T): T =
        TestBooks.uncachedCopy(context, file) { copy ->
            EngineDocument.open(context, copy, WIDTH, HEIGHT, settings, DENSITY, cacheable = false).use(block)
        }

    /** [TestBooks.NOTE_WORD] in the text of a book whose text sets no size of its own: the word at the reader's size. */
    private fun inText(plain: File) = fresh(plain, base) { it.word(TestBooks.NOTE_WORD, 0) }

    /** The same word in the first note. */
    private fun inNote(file: File, settings: EngineSettings) = fresh(file, settings) { it.word(TestBooks.NOTE_WORD, 1) }

    private fun assertReadersSize(what: String, text: Rect, note: Rect) {
        assertEquals("$what: the word is as wide as in the text ($note against $text)", text.width().toFloat(), note.width().toFloat(), 1f)
        assertEquals("$what: its line is as high ($note against $text)", text.height().toFloat(), note.height().toFloat(), 1f)
    }

    @Test
    fun epubNotesCanBeSetAtTheSizeOfTheText() =
        notesAtTheSizeOfTheText(TestBooks.notesEpub(dir), TestBooks.notesEpub(dir, styled = true))

    @Test
    fun fb2NotesCanBeSetAtTheSizeOfTheText() =
        notesAtTheSizeOfTheText(TestBooks.notesFb2(dir), TestBooks.notesFb2(dir, styled = true))

    private fun notesAtTheSizeOfTheText(plain: File, styled: File) {
        val text = inText(plain)
        // The book's own smaller sizes reach compact notes: the styled book is the case it is meant to be.
        assertTrue("the styled book sizes its notes down", inNote(styled, base).width() < inNote(plain, base).width())
        for (file in listOf(plain, styled)) {
            val compact = inNote(file, base)
            assertTrue(
                "${file.name}: compact notes stay smaller than the text: $compact against $text",
                compact.width() < text.width() * 9 / 10 && compact.height() < text.height() * 9 / 10,
            )
            // Neither a smaller ancestor, nor the book's rules for the note and its paragraphs, nor an inline
            // style, nor a root the book scales, makes a note asked for at the size of the text smaller.
            assertReadersSize(file.name, text, inNote(file, fullNotes))
        }
    }

    @Test
    fun notesKeptInTheFlowTakeTheSizeAsWell() {
        for ((plain, styled) in listOf(
            TestBooks.notesEpub(dir) to TestBooks.notesEpub(dir, styled = true),
            TestBooks.notesFb2(dir) to TestBooks.notesFb2(dir, styled = true),
        )) {
            val inFlow = fullNotes.copy(footnotes = false)
            assertReadersSize("${styled.name} without footnotes", inText(plain), inNote(styled, inFlow))
            assertTrue(inNote(styled, base.copy(footnotes = false)).width() < inText(plain).width() * 9 / 10)
        }
    }

    @Test
    fun aLongNoteAtTheSizeOfTheTextKeepsAllItsText() {
        for ((plain, long) in listOf(
            TestBooks.notesEpub(dir) to TestBooks.notesEpub(dir, styled = true, long = true),
            TestBooks.notesFb2(dir) to TestBooks.notesFb2(dir, styled = true, long = true),
        )) {
            val text = inText(plain)
            val compactEnd = fresh(long, base) { it.word(TestBooks.NOTE_LAST_WORD) }
            fresh(long, fullNotes) { doc ->
                val start = doc.search(TestBooks.NOTE_WORD)[1]
                val end = doc.search(TestBooks.NOTE_LAST_WORD).single()
                assertTrue("${long.name}: the note ends where it began or later", end.page >= start.page)
                // Every line of the note is laid out on a page and can be reached there: none is dropped to fit.
                val lines = doc.search(TestBooks.NOTE_SECOND_WORD)
                assertEquals("${long.name}: the long paragraph says it ${TestBooks.LONG_NOTE_REPEATS} times", TestBooks.LONG_NOTE_REPEATS, lines.size)
                for (hit in lines) {
                    assertTrue(doc.goToPage(hit.page))
                    assertTrue("${long.name}: ${hit.range.start} is on page ${hit.page}", doc.rects(hit.range.start, hit.range.end).isNotEmpty())
                }
                assertReadersSize("${long.name}, first line", text, doc.word(TestBooks.NOTE_WORD, 1))
                // The last word is not in the text, so its width has nothing to match: its line is as high as a
                // line of the text, and the word itself larger than in the compact note.
                val fullEnd = doc.word(TestBooks.NOTE_LAST_WORD)
                assertEquals("${long.name}, last line as high as the text ($fullEnd against $text)", text.height().toFloat(), fullEnd.height().toFloat(), 1f)
                assertTrue("${long.name}, last word larger than compact: $fullEnd against $compactEnd", compactEnd.width() < fullEnd.width() * 9 / 10)
            }
        }
    }

    @Test
    fun noteSizeChangedOnTheOpenBookIsWhatAFreshOpeningGives() {
        for (file in listOf(TestBooks.notesEpub(dir, styled = true), TestBooks.notesFb2(dir, styled = true))) {
            // The first page cites the first note and shows it at its bottom.
            fun freshly(settings: EngineSettings) = fresh(file, settings) { it.render() to it.word(TestBooks.NOTE_WORD, 1) }
            val (compactPage, compactNote) = freshly(base)
            val (fullPage, fullNote) = freshly(fullNotes)
            assertFalse("${file.name}: the page that cites the note changes with its size", compactPage.sameAs(fullPage))
            fresh(file, base) { doc ->
                for ((settings, page, note) in listOf(Triple(fullNotes, fullPage, fullNote), Triple(base, compactPage, compactNote))) {
                    doc.apply(settings)
                    assertTrue(doc.goToPage(0))
                    assertTrue("${file.name}: the open book draws what a fresh one does, $settings", doc.render().sameAs(page))
                    assertEquals("${file.name}: and sets the note as large", note, doc.word(TestBooks.NOTE_WORD, 1))
                }
            }
        }
    }

    @Test
    fun aCachedLayoutIsKeptApartWithCompactAndFullSizeNotes() {
        // A book new to the cache, large enough for the engine to keep it there.
        val file = TestBooks.notesFb2(dir, stamp = System.nanoTime().toString())
        assertTrue("${file.length()} bytes", file.length() >= 30_000)
        val cache = File(context.cacheDir, "crengine")
        fun entries() = cache.list().orEmpty().filter { it.startsWith("${file.name}.") && it.endsWith(".cr3") }.toSet()
        val before = entries()
        val place = fresh(file, base) { checkNotNull(it.xpointerOfPage(it.pageCount / 2)) }
        fun shown(doc: EngineDocument): Triple<Int, Int, Bitmap> {
            assertTrue(doc.goToXPointer(place))
            return Triple(doc.currentPage, doc.pageCount, doc.render())
        }
        // The book itself, with the cache on: it is read from and written to the entry the engine keeps for it.
        fun withCache(settings: EngineSettings) =
            EngineDocument.open(context, file, WIDTH, HEIGHT, settings, DENSITY).use { doc -> shown(doc).also { doc.saveCache() } }
        withCache(base)
        assertTrue("the engine keeps an entry for the book: ${entries()}", (entries() - before).isNotEmpty())
        // Whether a reopening is read from that entry or laid out again is not seen from here: what is checked
        // is that reopening with the entry there, after each change of size, shows what the book laid out afresh shows.
        for (settings in listOf(fullNotes, base, fullNotes)) {
            val (cachedIndex, cachedCount, cached) = withCache(settings)
            val (freshIndex, freshCount, freshPage) = fresh(file, settings, ::shown)
            assertEquals("the place falls on the same page, $settings", freshIndex, cachedIndex)
            assertEquals(freshCount, cachedCount)
            assertTrue("the page reopened is the one laid out afresh, $settings", cached.sameAs(freshPage))
        }
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
    fun contrastDarkensTheLettersAndMovesNoLine() {
        val file = TestBooks.epub(dir)
        fun page(contrast: Int) = open(file, base.copy(contrast = contrast)).use { doc ->
            val hit = doc.search("quick brown fox").first().range
            Triple(doc.render(), doc.pageCount, doc.rects(hit.start, hit.end))
        }
        val (plain, plainPages, plainWord) = page(0)
        val (dark, darkPages, darkWord) = page(EngineSettings.CONTRASTS.last)
        assertTrue("darker strokes cover more of the page", ink(dark) > ink(plain) * 11 / 10)
        assertEquals("the same pages", plainPages, darkPages)
        assertEquals("a word stays where it was", plainWord, darkWord)
    }

    @Test
    fun boldWordStartsDarkenTheStartAndLightenTheTail() {
        val file = TestBooks.epub(dir)
        // "paragraph" has nine letters: the first three are 600, the other six 300.
        fun halves(bold: Boolean) = open(file, base.copy(boldWordStarts = bold)).use { doc ->
            val (_, rect) = doc.locate("paragraph")
            val page = doc.render()
            val middle = rect.left + rect.width() / 2
            inkIn(page, Rect(rect.left, rect.top, middle, rect.bottom)) to inkIn(page, Rect(middle, rect.top, rect.right, rect.bottom))
        }
        val (plainStart, plainEnd) = halves(false)
        val (boldStart, boldEnd) = halves(true)
        assertTrue("the start of the word is darker: $boldStart against $plainStart", boldStart > plainStart * 105 / 100)
        assertTrue("its tail is lighter: $boldEnd against $plainEnd", boldEnd < plainEnd * 9 / 10)
    }

    @Test
    fun wordsShorterThanFiveLettersAreLeftAsTheyAre() {
        val file = File(dir, "short-words.html").apply {
            writeText(
                """<!DOCTYPE html><html><head><meta charset="utf-8"/></head><body><p>""" +
                    "cat dog fox sun run big red cup map kit ore den. ".repeat(12) +
                    "</p></body></html>",
            )
        }
        val plain = open(file).use { it.render() }
        val bold = open(file, base.copy(boldWordStarts = true)).use { it.render() }
        assertTrue("short words are the same to the pixel", bold.sameAs(plain))
    }

    @Test
    fun boldWordStartsKeepWordsWhereTheyAreFound() {
        for (file in listOf(TestBooks.epub(dir), TestBooks.russianFb2(dir))) {
            open(file, base.copy(boldWordStarts = true)).use { doc ->
                // Words in the middle of a text node: the rectangle of each covers it from end to end.
                for (word in if (file.name.endsWith("fb2")) listOf("сообщение", "медленно") else listOf("quick", "brown", "lazy")) {
                    val (hit, rect) = doc.locate(word)
                    assertTrue("$word has a rectangle in ${file.name}: $rect", rect.width() > 0)
                    val y = rect.centerY()
                    for (x in listOf(rect.left + 2, rect.centerX(), rect.right - 2)) {
                        val picked = doc.select(x, y) ?: error("nothing at $x,$y on $word")
                        assertEquals("the word under $x on $word", hit.range.start to hit.range.end, picked.start to picked.end)
                    }
                }
            }
        }
    }

    @Test
    fun aPhraseAcrossBoldAndRegularPartsIsOneRectanglePerLine() = open(TestBooks.epub(dir), base.copy(boldWordStarts = true)).use { doc ->
        val (phrase, rect) = doc.locate("quick brown fox")
        assertEquals("one line, one rectangle", 1, doc.rects(phrase.range.start, phrase.range.end).size)
        assertEquals("from the start of the first word", doc.locate("quick").second.left, rect.left)
        assertEquals("to the end of the last", doc.locate("fox").second.right, rect.right)
    }

    @Test
    fun headingsAndScriptsWithoutSeparateLettersAreLeftAsTheyAre() {
        // A heading, Chinese with no spaces between words, Arabic whose letters join: nothing here is made bold.
        val file = File(dir, "left-alone.html").apply {
            writeText(
                """<!DOCTYPE html><html><head><meta charset="utf-8"/></head><body>""" +
                    "<h1>Heading words stay</h1>" +
                    "<p>这是一个没有空格的中文句子，读者照常阅读。</p>" +
                    "<p dir=\"rtl\">هذه جملة عربية تتصل حروفها ببعض</p>" +
                    "<pre>code words stay</pre></body></html>",
            )
        }
        val plain = open(file).use { it.render() }
        val bold = open(file, base.copy(boldWordStarts = true)).use { it.render() }
        assertTrue("the page is the same to the pixel", bold.sameAs(plain))
        // A paragraph of plain text in the same kind of file does change.
        val control = File(dir, "control.html").apply {
            writeText("""<!DOCTYPE html><html><head><meta charset="utf-8"/></head><body><p>Plain words get bold starts</p></body></html>""")
        }
        assertFalse(open(control).use { it.render() }.sameAs(open(control, base.copy(boldWordStarts = true)).use { it.render() }))
    }

    @Test
    fun aParagraphWithAStyledFirstLineKeepsItsLines() {
        // A first line styled by the book, as chapters often open: the engine lays that line out from a copy
        // of the text and then goes on in the text itself. A colour leaves the metrics as they are, so every
        // word must stand where it stands in the same paragraph with no first-line style.
        val text = (1..12).joinToString(" ") { "Lorem ipsum dolor sit amet, consectetur adipiscing elit number $it." }
        fun book(name: String, css: String) = File(dir, name).apply {
            writeText("""<!DOCTYPE html><html><head><meta charset="utf-8"/><style>$css</style></head><body><p>$text</p></body></html>""")
        }
        val styled = book("first-line.html", "p::first-line { color: #880000 }")
        val plain = book("no-first-line.html", "")
        // With a drop letter as well, the text node starts after it.
        val dropped = book("first-letter-line.html", "p::first-letter { color: #000088 } p::first-line { color: #880000 }")
        val droppedPlain = book("first-letter.html", "p::first-letter { color: #000088 }")
        val words = listOf("Lorem", "dolor", "number 1.", "number 2.", "number 3.", "number 7.", "number 12.")
        fun places(file: File) = open(file, base.copy(boldWordStarts = true)).use { doc ->
            words.map { doc.locate(it).second }
        }
        val reddish = open(styled, base.copy(boldWordStarts = true)).use { doc ->
            pixels(doc.render(), Rect(0, 0, WIDTH, HEIGHT)).count { red(it) > 100 && red(it) > 2 * blue(it) }
        }
        assertTrue("the first line is styled: $reddish reddish pixels", reddish > 100)
        assertEquals(places(plain), places(styled))
        assertEquals(places(droppedPlain), places(dropped))
    }

    @Test
    fun boldWordStartsGoAwayWhenSwitchedOff() = open(TestBooks.epub(dir)).use { doc ->
        val plain = doc.render()
        doc.apply(base.copy(boldWordStarts = true))
        assertFalse(doc.render().sameAs(plain))
        doc.apply(base)
        assertTrue("the same page as before, to the pixel", doc.render().sameAs(plain))
    }

    @Test
    fun aCachedLayoutIsKeptApartWithBoldWordStartsOnAndOff() {
        // Large enough for the engine to cache its layout.
        val file = TestBooks.russianFb2(dir)
        fun page(bold: Boolean) = open(file, base.copy(boldWordStarts = bold)).use { doc ->
            doc.render().also { doc.saveCache() }
        }
        val plain = page(false)
        val bold = page(true)
        assertFalse(bold.sameAs(plain))
        assertTrue("the plain layout comes back from the cache plain", page(false).sameAs(plain))
        assertTrue("and the bold one bold", page(true).sameAs(bold))
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
    fun ownersCssComesAfterTheSettings() {
        val file = TestBooks.epub(dir)
        val indented = base.copy(paragraphIndent = true)
        // Where a word of a paragraph's first line starts: the indent moves it.
        fun firstLineX(settings: EngineSettings) = open(file, settings).use { it.locate("paragraph 2. The").second.left }
        val app = firstLineX(indented)
        val owner = firstLineX(indented.copy(userCss = "p { text-indent: 0 !important; }"))
        assertTrue("the owner's !important rule beats the app's: $owner against $app", owner < app)
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

    @Test
    fun charactersPerLineMatchTheLinesOfThePage() {
        for (file in listOf(TestBooks.epub(dir), TestBooks.russianFb2(dir))) {
            open(file).use { doc ->
                // The second page is all body text: the first has the chapter title on it.
                assertTrue(doc.goToPage(1))
                val estimate = doc.charsPerLine() ?: error("${file.name} has text enough to tell")
                val start = doc.xpointerOfPage(1)!!
                val end = doc.xpointerOfPage(2)!!
                val text = doc.textBetween(start, end)!!.replace(Regex("\\s+"), " ").trim()
                val lines = doc.rects(start, end).size
                assertTrue("lines on the page: $lines", lines >= 10)
                // Justified and hyphenated lines are as full as lines get; the last line of a paragraph is not.
                val average = text.length / lines
                assertTrue("estimate $estimate against $average a line in ${file.name}", estimate in average * 90 / 100..average * 120 / 100)
            }
        }
    }

    @Test
    fun charactersPerLineFollowTheFontAndMargins() {
        val file = TestBooks.epub(dir)
        fun estimate(settings: EngineSettings) = open(file, settings).use { it.charsPerLine()!! }
        val plain = estimate(base)
        assertTrue("larger letters", estimate(base.copy(fontSize = base.fontSize * 3 / 2)) < plain * 3 / 4)
        assertTrue("wider margins", estimate(base.copy(marginHorizontal = 120)) < plain)
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

    private fun inkIn(bitmap: Bitmap, rect: Rect): Int = pixels(bitmap, rect).count { luminance(it) < 128 }

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
