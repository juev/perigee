package org.evsyukov.perigee.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class DimmingTest {
    private val lowest = 0.01f

    @Test
    fun offTheGestureSetsTheBacklightAlone() {
        assertEquals(0.05f to 0f, Dimming.split(0.05f, enabled = false, lowest))
        assertEquals("the system's brightness", null to 0f, Dimming.split(null, enabled = true, lowest))
    }

    @Test
    fun aboveTheFloorTheBacklightAloneChanges() {
        assertEquals(0.6f to 0f, Dimming.split(0.6f, enabled = true, lowest))
        assertEquals(Dimming.FLOOR to 0f, Dimming.split(Dimming.FLOOR, enabled = true, lowest))
    }

    @Test
    fun belowTheFloorTheBacklightStaysAndThePageDarkens() {
        val (window, half) = Dimming.split((Dimming.FLOOR + lowest) / 2, enabled = true, lowest)
        assertEquals(Dimming.FLOOR, window)
        assertEquals(Dimming.MAX_DIM / 2, half, 0.001f)
        assertEquals(Dimming.FLOOR to Dimming.MAX_DIM, Dimming.split(lowest, enabled = true, lowest))
    }
}
