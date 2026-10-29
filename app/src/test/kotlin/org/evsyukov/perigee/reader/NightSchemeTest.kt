package org.evsyukov.perigee.reader

import org.evsyukov.perigee.data.ColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightSchemeTest {
    @Test
    fun nightIsLightTextOnDarkGreyNotOnBlack() {
        val night = ReaderViewModel.colorsOf(ColorScheme.Night)
        assertEquals(0xC8C8C8, night.text)
        assertEquals(0x1E1E1E, night.background)
        assertTrue("highlights are painted over a dark grey page as over a black one", night.isDark)
    }
}
