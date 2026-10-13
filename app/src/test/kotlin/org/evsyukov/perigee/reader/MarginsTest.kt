package org.evsyukov.perigee.reader

import org.evsyukov.perigee.data.ReaderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarginsTest {
    private fun margins(wide: Boolean, clearAbove: Int = 0, density: Float = 2f) =
        ReaderViewModel.laidOut(ReaderPrefs(pageMargins = wide), Viewport(1080, 2400, density, 320, clearAbove)).third

    @Test
    fun wideMarginsAreSeenToBeWiderOnEverySide() {
        val narrow = margins(wide = false)
        val wide = margins(wide = true)
        assertEquals(listOf(16, 24, 24), listOf(narrow.marginHorizontal, narrow.marginTop, narrow.marginBottom))
        assertEquals(listOf(64, 72, 72), listOf(wide.marginHorizontal, wide.marginTop, wide.marginBottom))
    }

    @Test
    fun stripLeftClearUnderACameraCutoutCountsAsTheTopMargin() {
        // A strip of 80 px is more than the narrow margin and more than the wide one.
        val narrow = margins(wide = false, clearAbove = 80)
        val wide = margins(wide = true, clearAbove = 80)
        assertEquals("the text starts right under the strip", 4, narrow.marginTop)
        assertEquals(24, narrow.marginBottom)
        assertEquals("and a step lower with wide margins", 40, wide.marginTop)
        assertEquals(72, wide.marginBottom)
        assertTrue(wide.marginTop > narrow.marginTop)
    }

    @Test
    fun stripLowerThanTheMarginIsMadeUpToIt() {
        assertEquals(72 - 30, margins(wide = true, clearAbove = 30).marginTop)
        assertEquals(24 - 10, margins(wide = false, clearAbove = 10).marginTop)
    }
}
