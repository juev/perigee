package org.evsyukov.perigee.reader

import org.evsyukov.perigee.data.ReaderPrefs
import org.evsyukov.perigee.engine.EngineSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ContrastTest {
    private fun contrast(prefs: ReaderPrefs) =
        ReaderViewModel.laidOut(prefs, Viewport(1080, 2400, 2f, 320, 0)).third.contrast

    @Test
    fun theChosenStepReachesTheEngine() {
        assertEquals(0, contrast(ReaderPrefs()))
        for (step in EngineSettings.CONTRASTS) assertEquals(step, contrast(ReaderPrefs(contrast = step)))
    }

    @Test
    fun aStepOutOfRangeIsTheNearestOneThere() {
        assertEquals(EngineSettings.CONTRASTS.last, contrast(ReaderPrefs(contrast = 99)))
        assertEquals(EngineSettings.CONTRASTS.first, contrast(ReaderPrefs(contrast = -1)))
    }
}
