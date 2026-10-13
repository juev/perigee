package org.evsyukov.perigee.reader

import androidx.compose.ui.unit.IntSize
import org.evsyukov.perigee.data.ReaderPrefs
import org.evsyukov.perigee.data.TapScheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoneTest {
    private val screen = IntSize(900, 1800)

    private fun zone(x: Int, y: Int, prefs: ReaderPrefs = ReaderPrefs()) = zoneOf(x.toFloat(), y.toFloat(), screen, prefs)

    @Test
    fun middleOfThePageOpensTheMenuInEveryScheme() {
        for (scheme in TapScheme.entries) {
            assertEquals(scheme.name, Zone.Menu, zone(450, 900, ReaderPrefs(tapScheme = scheme)))
        }
    }

    @Test
    fun leftRightSchemeGoesBackOnlyFromTheLeftThird() {
        assertEquals(Zone.Back, zone(100, 900))
        assertEquals(Zone.Back, zone(100, 1700))
        assertEquals(Zone.Forward, zone(800, 900))
        assertEquals("above and below the menu the middle column goes forward", Zone.Forward, zone(450, 1700))
        assertEquals(Zone.Forward, zone(450, 400))
    }

    @Test
    fun topBottomSchemeSplitsThePageInHalves() {
        val prefs = ReaderPrefs(tapScheme = TapScheme.TopBottom)
        assertEquals(Zone.Back, zone(800, 400, prefs))
        assertEquals(Zone.Forward, zone(100, 1500, prefs))
    }

    @Test
    fun anyForwardSchemeGoesForwardEverywhereButTheMiddle() {
        val prefs = ReaderPrefs(tapScheme = TapScheme.AnyForward)
        assertEquals(Zone.Forward, zone(100, 900, prefs))
        assertEquals(Zone.Forward, zone(800, 400, prefs))
    }

    @Test
    fun switchedOffSchemeLeavesTouchesAlone() {
        assertEquals(Zone.None, zone(800, 900, ReaderPrefs(tapScheme = TapScheme.Off)))
    }

    @Test
    fun topCornersAreShortcutsUnlessSwitchedOff() {
        assertEquals(Zone.DayNight, zone(50, 50))
        assertEquals(Zone.Bookmark, zone(850, 50))
        assertEquals("between the corners the top strip turns pages", Zone.Forward, zone(450, 50))

        val plain = ReaderPrefs(dayNightByCorner = false, bookmarkByCorner = false)
        assertEquals(Zone.Back, zone(50, 50, plain))
        assertEquals(Zone.Forward, zone(850, 50, plain))
    }
}
