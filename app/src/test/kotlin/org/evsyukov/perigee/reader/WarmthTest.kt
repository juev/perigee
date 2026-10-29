package org.evsyukov.perigee.reader

import org.evsyukov.perigee.data.ReaderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmthTest {
    private val on = ReaderPrefs(warmFilter = true)

    @Test
    fun hoursRunOverMidnight() {
        val from = 22 * 60
        val to = 7 * 60
        assertTrue(Warmth.isOn(from, to, 22 * 60))
        assertTrue(Warmth.isOn(from, to, 0))
        assertTrue(Warmth.isOn(from, to, 7 * 60 - 1))
        assertFalse("the end is not included", Warmth.isOn(from, to, 7 * 60))
        assertFalse(Warmth.isOn(from, to, 12 * 60))
    }

    @Test
    fun hoursWithinADay() {
        assertTrue(Warmth.isOn(13 * 60, 15 * 60, 14 * 60))
        assertFalse(Warmth.isOn(13 * 60, 15 * 60, 16 * 60))
        assertFalse("the same start and end is never", Warmth.isOn(13 * 60, 13 * 60, 13 * 60))
    }

    @Test
    fun noTintWhenOffOrOutsideTheHours() {
        assertNull("off by default", Warmth.tintAt(ReaderPrefs(), 23 * 60))
        assertNull(Warmth.tintAt(on, 12 * 60))
    }

    @Test
    fun strongerStepsTakeMoreBlueAndGreenAndKeepTheRed() {
        val tints = ReaderPrefs.WARM_STRENGTHS.map { Warmth.tintAt(on.copy(warmStrength = it), 23 * 60)!! }
        assertTrue(tints.all { it.red == 1f })
        assertEquals("green falls step by step", tints.map { it.green }.sortedDescending(), tints.map { it.green })
        assertTrue("blue falls faster than green", tints.all { it.blue <= it.green })
        assertEquals(102 / 255f, tints.last().blue, 0.01f)
    }
}
