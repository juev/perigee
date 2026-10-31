package org.evsyukov.perigee.reader

import org.evsyukov.perigee.data.Hinting
import org.evsyukov.perigee.data.ReaderPrefs
import org.evsyukov.perigee.engine.EngineSettings
import org.evsyukov.perigee.engine.HintingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphSettingsTest {
    private fun settings(prefs: ReaderPrefs): EngineSettings =
        ReaderViewModel.laidOut(prefs, Viewport(1080, 2400, 2f, 320, 0)).third

    @Test
    fun aReaderWhoChangesNothingGetsTheGlyphsTheAppAlwaysDrew() {
        val settings = settings(ReaderPrefs())
        assertTrue("smoothing on, antialiasing mode 2", settings.antialiasing)
        assertEquals("hinting by the autohinter, mode 2", 2, settings.hinting.engine)
        assertFalse("no ligatures: light shaping unless the script needs full", settings.ligatures)
        assertEquals("glyphs on whole pixels", 0, settings.fractionalPositioning)
        assertEquals("code at the engine's own size", 100, settings.monospaceSizePercent)
        assertTrue("the book's own fonts allowed", settings.embeddedFonts)
        // The engine's own defaults, which a document opened without the reader's settings gets, are the same.
        val engine = EngineSettings()
        assertEquals(engine.antialiasing, settings.antialiasing)
        assertEquals(engine.hinting, settings.hinting)
        assertEquals(engine.ligatures, settings.ligatures)
        assertEquals(engine.fractionalPositioning, settings.fractionalPositioning)
        assertEquals(engine.monospaceSizePercent, settings.monospaceSizePercent)
        assertEquals(engine.embeddedFonts, settings.embeddedFonts)
    }

    @Test
    fun eachChoiceReachesTheEngine() {
        assertFalse(settings(ReaderPrefs(antialiasing = false)).antialiasing)
        assertEquals(HintingMode.Auto, settings(ReaderPrefs(hinting = Hinting.Auto)).hinting)
        assertEquals(HintingMode.Native, settings(ReaderPrefs(hinting = Hinting.Native)).hinting)
        assertEquals(HintingMode.Off, settings(ReaderPrefs(hinting = Hinting.Off)).hinting)
        assertEquals(listOf(2, 1, 0), HintingMode.entries.map { it.engine })
        assertTrue(settings(ReaderPrefs(ligatures = true)).ligatures)
        for (step in ReaderPrefs.FRACTIONAL_STEPS) {
            assertEquals(step, settings(ReaderPrefs(fractionalPositioning = step)).fractionalPositioning)
        }
        for (percent in ReaderPrefs.MONOSPACE_SIZES) {
            assertEquals(percent, settings(ReaderPrefs(monospaceSizePercent = percent)).monospaceSizePercent)
        }
        assertFalse(settings(ReaderPrefs(embeddedFonts = false)).embeddedFonts)
    }

    @Test
    fun aValueOutOfRangeIsTheNearestOneThere() {
        assertEquals(ReaderPrefs.FRACTIONAL_STEPS.last, settings(ReaderPrefs(fractionalPositioning = 9)).fractionalPositioning)
        assertEquals(ReaderPrefs.FRACTIONAL_STEPS.first, settings(ReaderPrefs(fractionalPositioning = -1)).fractionalPositioning)
        assertEquals(ReaderPrefs.MONOSPACE_SIZES.last, settings(ReaderPrefs(monospaceSizePercent = 500)).monospaceSizePercent)
        assertEquals(ReaderPrefs.MONOSPACE_SIZES.first, settings(ReaderPrefs(monospaceSizePercent = 10)).monospaceSizePercent)
    }

    @Test
    fun theRangesAreTheEngines() {
        assertEquals(0..3, ReaderPrefs.FRACTIONAL_STEPS)
        assertEquals(listOf(60, 70, 80, 90, 100, 110, 120, 130, 140), ReaderPrefs.MONOSPACE_SIZES.toList())
    }
}
