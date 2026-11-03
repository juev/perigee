package org.evsyukov.perigee.reader

import org.evsyukov.perigee.data.ReaderPrefs
import org.evsyukov.perigee.engine.EngineSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FootnoteSizeTest {
    private val portrait = Viewport(1080, 2400, 2f, 320, 0)
    private val landscape = Viewport(2400, 1080, 2f, 320, 0)

    private fun laidOut(prefs: ReaderPrefs, viewport: Viewport = portrait) = ReaderViewModel.laidOut(prefs, viewport)

    @Test
    fun notesAreCompactUntilTheReaderChoosesTheMainTextSize() {
        assertFalse(ReaderPrefs().fullSizeFootnotes)
        assertFalse(laidOut(ReaderPrefs()).third.fullSizeFootnotes)
        // A document opened without the reader's settings, as the font trial's passage, gets the same.
        assertFalse(EngineSettings().fullSizeFootnotes)
        assertTrue(laidOut(ReaderPrefs(fullSizeFootnotes = true)).third.fullSizeFootnotes)
    }

    @Test
    fun theChoiceReachesTheEngineInEitherOrientation() {
        for (viewport in listOf(portrait, landscape)) {
            for (full in listOf(false, true)) {
                assertEquals("$viewport, $full", full, laidOut(ReaderPrefs(fullSizeFootnotes = full), viewport).third.fullSizeFootnotes)
            }
        }
        // The second copy for the other orientation is laid out from the same mapping, two pages and all.
        val wide = laidOut(ReaderPrefs(fullSizeFootnotes = true), landscape).third
        assertTrue(wide.twoPages && wide.fullSizeFootnotes)
    }

    @Test
    fun changingTheNoteSizeLaysTheBookOutAgainAndNothingElse() {
        val prefs = ReaderPrefs(fontSize = 22, lineSpacingPercent = 140)
        val compact = laidOut(prefs)
        val full = laidOut(prefs.copy(fullSizeFootnotes = true))
        // The model lays the book out again only when what it would apply differs from what it applied.
        assertNotEquals(compact, full)
        assertEquals(compact.third.copy(fullSizeFootnotes = true), full.third)
        assertEquals(compact.first to compact.second, full.first to full.second)
    }
}
