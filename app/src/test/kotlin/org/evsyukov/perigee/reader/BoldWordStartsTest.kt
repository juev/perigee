package org.evsyukov.perigee.reader

import org.evsyukov.perigee.data.ReaderPrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoldWordStartsTest {
    private fun settings(prefs: ReaderPrefs) = ReaderViewModel.laidOut(prefs, Viewport(1080, 2400, 2f, 320, 0)).third

    @Test
    fun offByDefaultAndPassedOnWhenChosen() {
        assertFalse(settings(ReaderPrefs()).boldWordStarts)
        assertTrue(settings(ReaderPrefs(boldWordStarts = true)).boldWordStarts)
    }
}
