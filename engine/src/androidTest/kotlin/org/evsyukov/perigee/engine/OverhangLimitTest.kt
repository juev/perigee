package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two pages in landscape, as the app lays them out (`ReaderViewModel.laidOut`): a canvas wider than the screen by
 * [SIDE] on each side, side margins of twice [SIDE], and the screen showing the canvas without its outer [SIDE] on
 * each side. Hanging punctuation and the hyphen of a broken word may reach into the margin; at large sizes they reached
 * past its visible half and were cut off. [EngineSettings.visibleMarginHorizontal] keeps them in it (issue #154).
 */
@RunWith(AndroidJUnit4::class)
class OverhangLimitTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = File(context.cacheDir, "overhang").apply { mkdirs() }

    @Before
    fun registerTestFont() {
        runBlocking { Engine.registerFonts(context, TestBooks.fonts(context)) }
    }

    /** The settings of two pages; [visible] is what the screen shows of the side margin, null as before issue #154. */
    private fun twoPages(fontSize: Int, visible: Int?) = EngineSettings(
        fontFace = TestBooks.TEST_FONT,
        fontSize = fontSize,
        lineSpacingPercent = 120,
        marginHorizontal = 2 * SIDE,
        marginTop = 28,
        marginBottom = 40,
        textAlign = TextAlign.Justify,
        hyphenation = true,
        paragraphSpacingPercent = 20,
        paragraphIndent = true,
        twoPages = true,
        visibleMarginHorizontal = visible,
    )

    /** A copy of [book] under a name of its own, so that the engine's cache, keyed by name and contents, has no layout of it. */
    private fun copyOf(book: File, name: String): File =
        File(dir, "$name-${System.nanoTime()}.${book.extension}").also { book.copyTo(it, overwrite = true) }

    private fun open(book: File, settings: EngineSettings, cacheable: Boolean = false) =
        EngineDocument.open(context, book, CANVAS, HEIGHT, settings, DENSITY, cacheable)

    /** The screen as BookReader draws it: the place is taken first, then the page is drawn. */
    private fun draw(doc: EngineDocument): Bitmap {
        doc.currentPage
        return Bitmap.createBitmap(CANVAS, HEIGHT, Bitmap.Config.ARGB_8888).also(doc::renderPage)
    }

    /** Dark pixels in the strips the screen crops off: what the reader never sees. */
    private fun hiddenInk(screen: Bitmap): Int {
        val column = IntArray(screen.height)
        var dark = 0
        for (x in (0 until SIDE) + (screen.width - SIDE until screen.width)) {
            screen.getPixels(column, 0, 1, x, 0, 1, screen.height)
            for (pixel in column) if (((pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)) / 3 < 128) dark++
        }
        return dark
    }

    /** The first page of every screen whose cropped strips hold ink, and the number of screens. */
    private fun cutScreens(book: File, settings: EngineSettings): Pair<List<Int>, Int> = open(book, settings).use { doc ->
        val cut = mutableListOf<Int>()
        var screens = 0
        for (page in 0 until doc.pageCount step 2) {
            doc.goToPage(page)
            if (hiddenInk(draw(doc)) > 0) cut += page
            screens++
        }
        cut to screens
    }

    /** What a fresh opening with [settings] draws at [xpointer]. */
    private fun fresh(book: File, settings: EngineSettings, xpointer: String): Bitmap =
        open(copyOf(book, "fresh"), settings).use { doc ->
            assertTrue(doc.goToXPointer(xpointer))
            draw(doc)
        }

    @Test
    fun hyphensStayInTheVisiblePartOfTheMarginAtTheLargestSize() {
        val russian = TestBooks.russianFb2(dir)
        val (before, screens) = cutScreens(russian, twoPages(LARGEST, visible = null))
        // The oracle: without the limit, as before issue #154, some hyphen at the end of a right-hand line is cut off.
        assertTrue("without the limit something is cut off on some of $screens screens", before.isNotEmpty())
        val (after, screensAfter) = cutScreens(russian, twoPages(LARGEST, visible = SIDE))
        assertEquals("with it nothing is, on any screen", emptyList<Int>(), after)
        assertEquals("the pages fall as before", screens, screensAfter)

        val english = TestBooks.epub(dir)
        assertEquals(emptyList<Int>(), cutScreens(english, twoPages(LARGEST, visible = SIDE)).first)
    }

    @Test
    fun theOrdinarySizeIsDrawnAsBefore() {
        // At 18 dp on a 560 dpi screen nothing hangs as far as the limit: every screen is the one drawn without it.
        val book = TestBooks.russianFb2(dir)
        open(book, twoPages(ORDINARY, visible = null)).use { plain ->
            open(copyOf(book, "limited"), twoPages(ORDINARY, visible = SIDE)).use { limited ->
                assertEquals(plain.pageCount, limited.pageCount)
                for (page in 0 until plain.pageCount step 2) {
                    plain.goToPage(page)
                    limited.goToPage(page)
                    assertTrue("screen of page $page", draw(plain).sameAs(draw(limited)))
                }
            }
        }
    }

    @Test
    fun theLimitKeepsLayoutsApartAndLeavesTheOwnersCssAlone() {
        assertNull("a document opened without the app's settings has no limit", EngineSettings().visibleMarginHorizontal)
        val plain = twoPages(LARGEST, visible = null)
        val limited = twoPages(LARGEST, visible = SIDE)
        assertEquals(-1, plain.overhangLimit)
        assertFalse(plain.extraCss.contains("perigee-overhang"))
        assertEquals(SIDE, limited.overhangLimit)
        // The engine leaves the limit out of the hash it keeps a layout by: the stylesheet differs with it instead.
        assertTrue(limited.extraCss.contains(".perigee-overhang-$SIDE "))
        assertNotEquals(limited.extraCss, limited.copy(visibleMarginHorizontal = SIDE - 1).extraCss)
        assertEquals("no more than the margin", 2 * SIDE, limited.copy(visibleMarginHorizontal = 1000).overhangLimit)
        assertEquals("and nothing below none", 0, limited.copy(visibleMarginHorizontal = -5).overhangLimit)
        val owners = "p { text-align: left }"
        assertEquals(owners, limited.copy(userCss = owners).ownerCss)
        assertFalse(limited.copy(userCss = owners).extraCss.contains(owners))
    }

    @Test
    fun aLimitSetChangedOrTakenAwayOnTheOpenBookDrawsWhatAFreshOpeningDraws() {
        val book = TestBooks.russianFb2(dir)
        val page = cutScreens(book, twoPages(LARGEST, visible = null)).first.first()
        open(copyOf(book, "open"), twoPages(LARGEST, visible = null)).use { doc ->
            doc.goToPage(page)
            val place = doc.xpointer()
            val cut = draw(doc)
            assertTrue(hiddenInk(cut) > 0)
            for (visible in listOf(SIDE, SIDE / 2, null, SIDE)) {
                val settings = twoPages(LARGEST, visible)
                doc.apply(settings, CANVAS, HEIGHT)
                assertTrue(doc.goToXPointer(place))
                val live = draw(doc)
                assertTrue("visible $visible: the open book draws what a fresh opening draws", live.sameAs(fresh(book, settings, place)))
                if (visible != null) assertEquals("visible $visible: nothing cut off", 0, hiddenInk(live))
            }
        }
    }

    @Test
    fun twoBooksOpenAtOnceKeepTheirOwnLimits() {
        // As a book and its spare copy for the other orientation: one engine, two views.
        val book = TestBooks.russianFb2(dir)
        val page = cutScreens(book, twoPages(LARGEST, visible = null)).first.first()
        val limited = open(copyOf(book, "a"), twoPages(LARGEST, visible = SIDE))
        open(copyOf(book, "b"), twoPages(LARGEST, visible = null)).use { plain ->
            limited.goToPage(page)
            val place = limited.xpointer()
            assertTrue(plain.goToXPointer(place))
            val a = draw(limited)
            val b = draw(plain)
            assertEquals(0, hiddenInk(a))
            assertTrue("the other book keeps the whole margin", hiddenInk(b) > 0)

            limited.apply(twoPages(LARGEST, visible = SIDE / 2), CANVAS, HEIGHT)
            assertTrue(limited.goToXPointer(place))
            draw(limited)
            assertTrue(plain.goToXPointer(place))
            assertTrue("a change in one book leaves the other as it was", draw(plain).sameAs(b))
            limited.close()
            assertTrue(plain.goToXPointer(place))
            assertTrue("so does closing it", draw(plain).sameAs(b))
            open(copyOf(book, "a2"), twoPages(LARGEST, visible = SIDE)).use { again ->
                assertTrue(again.goToXPointer(place))
                assertTrue("opened again, it draws what it drew", draw(again).sameAs(a))
            }
        }
    }

    @Test
    fun aLayoutKeptInTheCacheIsReadOnlyWithItsOwnLimit() {
        // The engine keeps a layout by the book's name and contents; whether a reopening read it is not seen here
        // (the native check of issue #154 counts the engine's cache reads). What is drawn must be a fresh opening's.
        val book = copyOf(TestBooks.russianFb2(dir), "cached")
        assertTrue("large enough for the engine to cache", book.length() >= 30_000)
        val limited = twoPages(LARGEST, visible = SIDE)
        val plain = twoPages(LARGEST, visible = null)
        val place = open(book, limited, cacheable = true).use { doc ->
            doc.goToPage(doc.pageCount / 2)
            draw(doc)
            doc.saveCache()
            doc.xpointer()
        }
        for (settings in listOf(plain, limited, twoPages(LARGEST, visible = SIDE / 2), limited)) {
            open(book, settings, cacheable = true).use { doc ->
                assertTrue(doc.goToXPointer(place))
                assertTrue("reopened, ${settings.visibleMarginHorizontal}", draw(doc).sameAs(fresh(book, settings, place)))
                doc.saveCache()
            }
        }
    }

    @Test
    fun theReaderKeepsItsPlaceThroughTheLargestSizeOnePageAndBack() = runBlocking(Dispatchers.Default) {
        val book = TestBooks.russianFb2(dir)
        val ordinary = twoPages(ORDINARY, visible = SIDE)
        val reader = BookReader.open(context, copyOf(book, "reader"), CANVAS, HEIGHT, ordinary, densityDpi = DENSITY)
        try {
            reader.goToPage(6)
            val place = reader.position
            suspend fun shows(page: Page, twoPages: Boolean): Boolean {
                val at = reader.read { it.pageOf(place) }!!
                return if (twoPages) at == page.index || at == page.index + 1 else at == page.index
            }
            // A copy laid out for settings that are about to be left: it must not be shown for the new ones.
            reader.prepareSpare(Layout(twoPages(LARGEST, visible = null), CANVAS, HEIGHT))
            val largest = twoPages(LARGEST, visible = SIDE)
            val large = reader.apply(largest, CANVAS, HEIGHT)
            assertTrue("the place is on the screen at the largest size", shows(large, twoPages = true))
            assertEquals(place, reader.position)
            assertTrue("drawn with the limit, not from the copy without it", large.bitmap.sameAs(fresh(book, largest, place)))
            assertEquals(0, hiddenInk(large.bitmap))

            // One page across the whole width: the side margin is all seen, and has no limit.
            val onePage = largest.copy(twoPages = false, marginHorizontal = SIDE, visibleMarginHorizontal = null)
            val single = reader.apply(onePage, VIEW_WIDTH, HEIGHT)
            assertTrue("the place is on the one page", shows(single, twoPages = false))

            val back = reader.apply(ordinary, CANVAS, HEIGHT)
            assertTrue("and on the two pages again", shows(back, twoPages = true))
            assertEquals(place, reader.position)
        } finally {
            reader.close()
        }
    }

    private companion object {
        const val DENSITY = 560
        // 8 dp, the narrow side margin, at 560 dpi; the screen of a 2160 × 1080 phone turned sideways.
        const val SIDE = 28
        const val VIEW_WIDTH = 2160
        const val CANVAS = VIEW_WIDTH + 2 * SIDE
        const val HEIGHT = 1080
        // 18 dp and 48 dp, the default and the largest size, at 560 dpi.
        const val ORDINARY = 63
        const val LARGEST = 168
    }
}
