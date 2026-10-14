package org.evsyukov.perigee.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnownViewportsTest {
    private val stored = HashMap<String, String>()
    private val known = KnownViewports({ stored[it] }, { key, value -> stored[key] = value })

    private val tall = Viewport(1272, 2631, 3.5f, 560, clearAbove = 141)
    private val wide = Viewport(2490, 1272, 3.5f, 560)

    @Test
    fun theOtherOrientationIsWhatWasSeenOfIt() {
        assertNull("nothing seen turned yet", known.otherThan(tall))
        known.remember(tall)
        assertNull(known.otherThan(tall))
        known.remember(wide)
        assertEquals(wide, known.otherThan(tall))
        assertEquals(tall, known.otherThan(wide))
    }

    @Test
    fun theLatestAreaOfAnOrientationCounts() {
        known.remember(wide)
        val narrower = wide.copy(width = 2400)
        known.remember(narrower)
        assertEquals(narrower, known.otherThan(tall))
    }

    @Test
    fun anAreaOfAnotherDensityIsNotUsed() {
        known.remember(wide.copy(density = 2.625f, densityDpi = 420))
        assertNull(known.otherThan(tall))
    }

    @Test
    fun whatIsNotAnAreaIsIgnored() {
        stored["wide"] = "not,an,area"
        assertNull(known.otherThan(tall))
        assertEquals(tall, KnownViewports.decode(KnownViewports.encode(tall)))
    }
}
