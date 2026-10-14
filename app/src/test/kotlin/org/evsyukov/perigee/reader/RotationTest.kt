package org.evsyukov.perigee.reader

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class RotationTest {
    @Test
    fun deviceTurnedClockwiseWantsTheScreenTurnedTheOtherWay() {
        assertEquals(Surface.ROTATION_270, rotationFor(90, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_90, rotationFor(270, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_180, rotationFor(180, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_0, rotationFor(355, Surface.ROTATION_90))
        assertEquals(Surface.ROTATION_0, rotationFor(20, Surface.ROTATION_90))
    }

    @Test
    fun halfwayOrFlatKeepsWhatWasThere() {
        assertEquals("tipped 45 degrees is neither", Surface.ROTATION_0, rotationFor(45, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_270, rotationFor(130, Surface.ROTATION_270))
        assertEquals("lying on the table", Surface.ROTATION_90, rotationFor(-1, Surface.ROTATION_90))
    }
}
