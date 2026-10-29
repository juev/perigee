package org.evsyukov.perigee.reader

import org.evsyukov.perigee.data.ColorScheme
import org.evsyukov.perigee.data.ReaderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OledSchemeTest {
    @Test
    fun oledIsWhiteOnPureBlack() {
        val oled = ReaderViewModel.colorsOf(ColorScheme.Oled)
        assertEquals(0xFFFFFF, oled.text)
        assertEquals(0x000000, oled.background)
        assertTrue(oled.isDark)
    }

    @Test
    fun theSchemesAreListedDayNightOledSepia() {
        assertEquals(listOf(ColorScheme.Day, ColorScheme.Night, ColorScheme.Oled, ColorScheme.Sepia), ColorScheme.entries)
    }

    @Test
    fun theCornerGoesToTheDarkSchemeChosenLast() {
        val oled = ReaderPrefs().withScheme(ColorScheme.Oled)
        val day = oled.toggledNight()
        assertEquals(ColorScheme.Day, day.colorScheme)
        assertEquals("and back to OLED, not Night", ColorScheme.Oled, day.toggledNight().colorScheme)
    }

    @Test
    fun theCornerGoesBackToTheLightSchemeChosenLast() {
        val sepia = ReaderPrefs().withScheme(ColorScheme.Sepia)
        val night = sepia.toggledNight()
        assertEquals("Night until another dark scheme is chosen", ColorScheme.Night, night.colorScheme)
        assertEquals(ColorScheme.Sepia, night.toggledNight().colorScheme)
        assertEquals("choosing a dark scheme keeps the light one to return to", ColorScheme.Sepia, sepia.withScheme(ColorScheme.Oled).toggledNight().colorScheme)
    }
}
