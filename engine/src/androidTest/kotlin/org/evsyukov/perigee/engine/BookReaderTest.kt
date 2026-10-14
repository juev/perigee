package org.evsyukov.perigee.engine

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookReaderTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = File(context.cacheDir, "books").apply { mkdirs() }

    private suspend fun open(
        settings: EngineSettings = EngineSettings(),
        width: Int = WIDTH,
        height: Int = HEIGHT,
        xpointer: String? = null,
    ) = BookReader.open(context, TestBooks.epub(dir), width, height, settings, xpointer, DENSITY)

    private fun reading(block: suspend (BookReader) -> Unit) = runBlocking(Dispatchers.Default) {
        val reader = open()
        try {
            block(reader)
        } finally {
            reader.close()
        }
    }

    @Test
    fun copyLaidOutForTheOtherOrientationIsShownAtOnceAndKeptForTheWayBack() = reading { reader ->
        val start = reader.goToPage(5)
        val place = reader.position
        val tall = Layout(EngineSettings(), WIDTH, HEIGHT)
        val wide = Layout(EngineSettings(twoPages = true), HEIGHT, WIDTH)
        assertFalse(reader.hasSpare(wide))

        reader.prepareSpare(wide)
        assertTrue(reader.hasSpare(wide))
        val turned = reader.apply(wide.settings, wide.width, wide.height)

        assertEquals(HEIGHT, turned.bitmap.width)
        assertEquals(WIDTH, turned.bitmap.height)
        assertEquals("turning the screen does not move the place", place, reader.position)
        val pageOfPlace = checkNotNull(reader.read { it.pageOf(place) })
        assertEquals("the left page of the two the place falls on", pageOfPlace - pageOfPlace % 2, turned.index)
        assertFalse("the copy is the one shown now", reader.hasSpare(wide))
        assertTrue("and the one shown before waits for the way back", reader.hasSpare(tall))

        val back = reader.apply(tall.settings, tall.width, tall.height)
        assertEquals(start.index, back.index)
        assertEquals(WIDTH, back.bitmap.width)
        assertTrue(reader.hasSpare(wide))
    }

    @Test
    fun copyLaidOutWithSettingsSinceChangedIsNotShown() = reading { reader ->
        val wide = Layout(EngineSettings(twoPages = true), HEIGHT, WIDTH)
        reader.prepareSpare(wide)
        val bigger = wide.settings.copy(fontSize = wide.settings.fontSize + 8)

        reader.apply(bigger, wide.width, wide.height)

        assertTrue("the copy is still laid out as it was", reader.hasSpare(wide))
        // Laid out again for what is asked now, the copy is used once more.
        reader.prepareSpare(Layout(EngineSettings(), WIDTH, HEIGHT))
        assertTrue(reader.hasSpare(Layout(EngineSettings(), WIDTH, HEIGHT)))
    }

    @Test
    fun badlyMadeBookWithParagraphsNeverClosedOpens() = runBlocking(Dispatchers.Default) {
        // Each paragraph ends up inside the one before it: hundreds of levels,
        // which the engine walks by recursion. This took the app down.
        val body = (1..360).joinToString("\n") { "<p>Paragraph $it was opened and never closed, like the rest of them." }
        val file = File(dir, "nested.fb2").apply {
            writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
<description><title-info><book-title>Nested</book-title><lang>en</lang></title-info></description>
<body><section><title><p>One</p></title>$body</section></body>
</FictionBook>""",
            )
        }
        val reader = BookReader.open(context, file, WIDTH, HEIGHT, densityDpi = DENSITY)
        try {
            assertTrue(reader.current().pageCount > 1)
            assertEquals(1, reader.turn(1).index)
        } finally {
            reader.close()
        }
    }

    @Test
    fun turningMovesOneScreenAndStopsAtTheEndsOfTheBook() = reading { reader ->
        val first = reader.current()
        assertEquals(0, first.index)
        assertEquals("no page before the first", 0, reader.turn(-1).index)
        assertEquals(1, reader.turn(1).index)
        assertEquals(4, reader.turn(3).index)

        val last = reader.goToPage(first.pageCount - 1)
        assertEquals(first.pageCount - 1, last.index)
        assertEquals("no page after the last", last.index, reader.turn(1).index)
        assertTrue("progress grows through the book", last.progress > first.progress)
    }

    @Test
    fun pageHasTheSizeAskedFor() = reading { reader ->
        val page = reader.current()
        assertEquals(WIDTH, page.bitmap.width)
        assertEquals(HEIGHT, page.bitmap.height)
    }

    @Test
    fun pagesNextToTheOneShownAreDrawnAhead() = reading { reader ->
        reader.goToPage(5)
        // Drawing ahead is queued behind the call that asked for the page.
        assertEquals(setOf(4, 5, 6), reader.cachedPages())

        val next = reader.turn(1)
        assertEquals(setOf(5, 6, 7), reader.cachedPages())
        assertSame("coming back shows the page already drawn", next, reader.turn(1).let { reader.turn(-1) })
    }

    @Test
    fun drawingAheadLeavesTheReaderWhereTheyWere() = reading { reader ->
        val shown = reader.goToPage(5)
        reader.cachedPages()
        assertEquals(5, reader.read { it.currentPage })
        assertEquals(shown.xpointer, reader.read { it.xpointer() })
    }

    @Test
    fun newSettingsRedrawThePageAndKeepThePlace() = reading { reader ->
        val before = reader.goToPage(reader.current().pageCount / 2)
        val after = reader.apply(EngineSettings(fontSize = 44))

        assertNotSame(before, after)
        assertTrue("larger type needs more pages", after.pageCount > before.pageCount)
        assertEquals(
            "the text the reader was at is on the page shown",
            after.index,
            reader.read { it.pageOf(before.xpointer) },
        )
    }

    @Test
    fun resizingLaysTheBookOutForTheNewPage() = reading { reader ->
        val portrait = reader.current()
        val landscape = reader.resize(HEIGHT, WIDTH)
        assertEquals(HEIGHT, landscape.bitmap.width)
        assertEquals(WIDTH, landscape.bitmap.height)
        assertSame("the same size again changes nothing", landscape, reader.resize(HEIGHT, WIDTH))
        assertTrue(portrait.pageCount != landscape.pageCount)
    }

    @Test
    fun aBookLaidOutAgainBreaksIntoTheSamePagesAsOneOpenedThatWay() = runBlocking(Dispatchers.Default) {
        val settings = EngineSettings(fontSize = 36, paragraphSpacingPercent = 20, paragraphIndent = true)
        suspend fun tops(reader: BookReader) = reader.read { doc -> (0..5).map { doc.xpointerOfPage(it) } }

        val fresh = open(settings, width = WIDTH, height = HEIGHT - 80)
        val expected = tops(fresh)
        fresh.close()

        val resized = open(settings)
        resized.resize(WIDTH, HEIGHT - 80)
        assertEquals("after a resize", expected, tops(resized))
        resized.close()

        val restyled = open(EngineSettings(), width = WIDTH, height = HEIGHT - 80)
        restyled.apply(settings)
        assertEquals("after new settings", expected, tops(restyled))
        restyled.close()

        val moved = open(settings, width = WIDTH, height = HEIGHT - 80)
        moved.goToPage(3)
        moved.turn(1)
        assertEquals("after reading on", expected, tops(moved))
        moved.close()
    }

    @Test
    fun positionOfEveryPageLeadsBackToThatPage() = runBlocking(Dispatchers.Default) {
        val settings = EngineSettings(fontSize = 36, paragraphSpacingPercent = 20, paragraphIndent = true)
        val reader = open(settings)
        val positions = (0..8).map { index ->
            // Pages reached by turning come from the ones drawn ahead.
            val page = if (index == 0) reader.current() else reader.turn(1)
            assertEquals(index, page.index)
            assertEquals("top of page $index", reader.read { it.xpointerOfPage(index) }, page.xpointer)
            page.xpointer
        }
        reader.close()

        for ((index, position) in positions.withIndex()) {
            val reopened = open(settings, xpointer = position)
            val page = reopened.current()
            assertEquals("reopened at the position of page $index", index, page.index)
            assertEquals("and reports the same position", position, page.xpointer)
            reopened.close()
        }
    }

    @Test
    fun turningTheScreenAndBackReturnsToTheSamePage() = reading { reader ->
        for (start in 3..9) {
            val page = reader.goToPage(start)
            repeat(3) {
                reader.resize(HEIGHT, WIDTH)
                reader.resize(WIDTH, HEIGHT)
            }
            assertEquals("from page $start", start, reader.current().index)
            assertEquals(page.xpointer, reader.position)
        }
    }

    @Test
    fun positionFollowsTheReaderNotTheLayout() = reading { reader ->
        val page = reader.goToPage(6)
        assertEquals(page.xpointer, reader.position)
        reader.apply(EngineSettings(fontSize = 44))
        assertEquals("a layout change keeps the position", page.xpointer, reader.position)
        val next = reader.turn(1)
        assertEquals("turning moves it", next.xpointer, reader.position)
    }

    @Test
    fun storedPositionDoesNotMoveWhenTheBookIsOpenedInAnotherLayout() = runBlocking(Dispatchers.Default) {
        val portrait = open()
        val stored = portrait.goToPage(6).xpointer
        portrait.close()

        // Opened on a wide screen, where pages break elsewhere, and only looked at.
        val landscape = open(width = HEIGHT, height = WIDTH, xpointer = stored)
        landscape.current()
        assertEquals(stored, landscape.position)
        val jumped = open(width = HEIGHT, height = WIDTH)
        jumped.goTo(stored)
        assertEquals(stored, jumped.position)
        landscape.close()
        jumped.close()

        val again = open(xpointer = stored)
        assertEquals("back in the first layout the reader is on the same page", 6, again.current().index)
        again.close()
    }

    @Test
    fun highlightsAppearOnThePageShown() = reading { reader ->
        val plain = reader.current()
        val hit = reader.read { it.search("lazy dog").first() }
        assertEquals("the phrase is on the first page", 0, hit.page)

        val marked = reader.setHighlights(listOf(Highlight(hit.range.start, hit.range.end, 0xFFFF00)))
        assertFalse(plain.bitmap.sameAs(marked.bitmap))
        assertTrue("taking them away restores the page", plain.bitmap.sameAs(reader.setHighlights(emptyList()).bitmap))
    }

    @Test
    fun unknownPositionLeavesTheReaderInPlace() = reading { reader ->
        val shown = reader.goToPage(3)
        assertNull(reader.goTo("/body/DocFragment[99]/body/p[1]/text().0"))
        assertEquals(shown.index, reader.current().index)
    }

    @Test
    fun bookReopensAtASavedPosition() = runBlocking(Dispatchers.Default) {
        val first = open()
        val saved = first.goToPage(7)
        first.close()

        val second = open(xpointer = saved.xpointer)
        assertEquals(7, second.current().index)
        second.close()
    }

    @Test
    fun withTwoPagesAScreenIsTwoPages() = runBlocking(Dispatchers.Default) {
        val reader = open(EngineSettings(twoPages = true), width = HEIGHT, height = WIDTH)
        assertEquals(0, reader.current().index)
        assertEquals(2, reader.turn(1).index)
        assertEquals("an odd page is shown as the right one of its pair", 4, reader.goToPage(5).index)
        assertEquals(setOf(2, 4, 6), reader.cachedPages())
        reader.close()
    }

    @Test
    fun unreadableFileIsRejected() = runBlocking(Dispatchers.Default) {
        val missing = File(dir, "no-such-book.epub")
        val failure = runCatching { BookReader.open(context, missing, WIDTH, HEIGHT) }.exceptionOrNull()
        assertTrue("got $failure", failure is IllegalArgumentException)
    }

    @Test
    fun callAfterClosingIsCancelledNotFailed() = runBlocking(Dispatchers.Default) {
        val reader = open()
        reader.current()
        reader.close()
        reader.close()
        val failure = runCatching { reader.turn(1) }.exceptionOrNull()
        assertTrue("got $failure", failure is CancellationException)
    }

    @Test
    fun callsFromManyThreadsAreServedOneAtATime() = reading { reader ->
        val pages = coroutineScope {
            (1..40).map { n ->
                async { if (n % 2 == 0) reader.turn(1) else reader.read { it.search("quick", maxHits = 3).size } }
            }.awaitAll()
        }
        assertEquals("every search ran to the end", 20, pages.count { it == 3 })
        assertEquals("every turn was counted", 20, reader.current().index)
    }

    /**
     * A rotation closes the reader of the old screen while the new screen is
     * already opening the same book. Overlapping the two used to corrupt the
     * engine's allocator and kill the process.
     */
    @Test
    fun closingOneReaderWhileOpeningAnotherIsSafe() = runBlocking(Dispatchers.Default) {
        var current = open()
        repeat(30) {
            val closing = current
            val closed = launch { closing.close() }
            val opened = async { open(width = HEIGHT, height = WIDTH) }
            current = opened.await()
            closed.join()
            assertTrue(current.current().pageCount > 1)
        }
        current.close()
    }

    private companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val DENSITY = 320
    }
}
