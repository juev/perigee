package org.evsyukov.perigee.reader

import org.evsyukov.perigee.data.ReaderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarginsTest {
    private fun margins(wide: Boolean, clearAbove: Int = 0, density: Float = 2f) =
        ReaderViewModel.laidOut(ReaderPrefs(pageMargins = wide), Viewport(1080, 2400, density, 320, clearAbove)).third

    @Test
    fun wideMarginsWidenTheSidesAndHardlyTheTopAndBottom() {
        val narrow = margins(wide = false)
        val wide = margins(wide = true)
        assertEquals(listOf(16, 24, 40), listOf(narrow.marginHorizontal, narrow.marginTop, narrow.marginBottom))
        assertEquals(listOf(68, 32, 46), listOf(wide.marginHorizontal, wide.marginTop, wide.marginBottom))
        assertTrue("the sides are what is seen to change", wide.marginHorizontal >= 4 * narrow.marginHorizontal)
    }

    @Test
    fun stripLeftClearUnderACameraCutoutCountsAsTheTopMargin() {
        // A strip of 80 px is more than the narrow margin and more than the wide one.
        val narrow = margins(wide = false, clearAbove = 80)
        val wide = margins(wide = true, clearAbove = 80)
        assertEquals("the text starts right under the strip", 2, narrow.marginTop)
        assertEquals(40, narrow.marginBottom)
        assertEquals("and a touch lower with wide margins", 4, wide.marginTop)
        assertEquals(46, wide.marginBottom)
        assertTrue(wide.marginTop > narrow.marginTop)
    }

    @Test
    fun stripLowerThanTheMarginIsMadeUpToIt() {
        assertEquals(32 - 20, margins(wide = true, clearAbove = 20).marginTop)
        assertEquals(24 - 10, margins(wide = false, clearAbove = 10).marginTop)
    }
}
