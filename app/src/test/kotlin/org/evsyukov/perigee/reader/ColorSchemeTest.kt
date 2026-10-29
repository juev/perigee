package org.evsyukov.perigee.reader

import org.evsyukov.perigee.data.ColorScheme
import org.evsyukov.perigee.data.ReaderPrefs
import org.evsyukov.perigee.engine.PageColors
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSchemeTest {
    @Test
    fun aNewReaderStartsWithDarkTextOnALightPage() {
        // Dark text on a light page reads better than the reverse; see "Colour schemes" in docs/specs/reader.md.
        val prefs = ReaderPrefs()
        assertEquals(ColorScheme.Day, prefs.colorScheme)
        assertEquals(ColorScheme.Day, prefs.dayScheme)
        assertEquals(PageColors.Day, ReaderViewModel.colorsOf(prefs.colorScheme))
    }
}
