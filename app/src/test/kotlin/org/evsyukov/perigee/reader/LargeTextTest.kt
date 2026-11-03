package org.evsyukov.perigee.reader

import org.evsyukov.perigee.data.ReaderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sizes up to 48 dp, and how much of the side margin a line may hang into (issue #154). */
class LargeTextTest {
    private fun laidOut(prefs: ReaderPrefs, width: Int, height: Int, density: Float, clearAbove: Int = 0) =
        ReaderViewModel.laidOut(prefs, Viewport(width, height, density, (density * 160).toInt(), clearAbove))

    @Test
    fun theSizesRunFromTenToFortyEightAndTheDefaultsStay() {
        assertEquals(10..48, ReaderPrefs.FONT_SIZES)
        assertEquals(18, ReaderPrefs().fontSize)
        assertTrue(ReaderPrefs().twoPagesInLandscape)
    }

    @Test
    fun theLargestSizeIsMadePixelsAsEveryOther() {
        assertEquals(96, laidOut(ReaderPrefs(fontSize = 48), 720, 1280, 2f).third.fontSize)
        assertEquals(168, laidOut(ReaderPrefs(fontSize = 48), 1080, 2160, 3.5f).third.fontSize)
        assertEquals(192, laidOut(ReaderPrefs(fontSize = 48), 1440, 2560, 4f).third.fontSize)
    }

    @Test
    fun twoPagesHangIntoTheVisibleHalfOfTheDoubledMarginOnly() {
        val (width, height, settings) = laidOut(ReaderPrefs(fontSize = 48), 2160, 1080, 3.5f)
        assertTrue(settings.twoPages)
        // 8 dp at 3.5: the canvas is wider by it on each side, the margin doubled, and the screen shows 28 px of it.
        assertEquals(2160 + 2 * 28, width)
        assertEquals(1080, height)
        assertEquals(2 * 28, settings.marginHorizontal)
        assertEquals(28, settings.visibleMarginHorizontal)

        val wide = laidOut(ReaderPrefs(pageMargins = true), 2160, 1080, 3.5f).third
        assertEquals("wide margins: 34 dp", 119, wide.visibleMarginHorizontal)
        assertEquals(2 * 119, wide.marginHorizontal)
        assertEquals("a strip under a cutout is the top's, not the sides'", 28,
            laidOut(ReaderPrefs(), 2160, 1080, 3.5f, clearAbove = 90).third.visibleMarginHorizontal)
    }

    @Test
    fun onePageAndPortraitUseTheWholeMarginAsBefore() {
        val portrait = laidOut(ReaderPrefs(fontSize = 48), 1080, 2160, 3.5f)
        assertNull(portrait.third.visibleMarginHorizontal)
        assertEquals(1080, portrait.first)
        val onePage = laidOut(ReaderPrefs(fontSize = 48, twoPagesInLandscape = false), 2160, 1080, 3.5f)
        assertNull(onePage.third.visibleMarginHorizontal)
        assertEquals(2160, onePage.first)
        assertEquals(28, onePage.third.marginHorizontal)
    }
}
