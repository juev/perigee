package org.evsyukov.perigee.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LightSensorSwitchTest {
    private val sides = mutableListOf<Boolean>()

    private fun TestScope.switch() = LightSensorSwitch(this, holdMillis = 5_000) { sides += it }

    private fun TestScope.wait(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun aDarkRoomTurnsThePageDarkAfterTheLevelHolds() = runTest {
        val switch = switch()
        switch.onLux(3f)
        wait(4_000)
        assertEquals("not before five seconds", emptyList<Boolean>(), sides)
        wait(1_000)
        assertEquals(listOf(true), sides)
        switch.onLux(500f)
        wait(5_000)
        assertEquals(listOf(true, false), sides)
    }

    @Test
    fun aPassingShadowChangesNothing() = runTest {
        val switch = switch()
        switch.onLux(3f)
        wait(2_000)
        switch.onLux(200f)
        wait(10_000)
        assertEquals("the light came back before the dark held, and nothing was decided yet", listOf(false), sides)
    }

    @Test
    fun betweenTheThresholdsNothingChanges() = runTest {
        val switch = switch()
        switch.onLux(3f)
        wait(5_000)
        switch.onLux(25f)
        wait(30_000)
        assertEquals(listOf(true), sides)
        switch.onLux(5f)
        wait(30_000)
        assertEquals("dark already: no second switch", listOf(true), sides)
    }

    @Test
    fun aSchemeChosenByHandHoldsUntilResumed() = runTest {
        val switch = switch()
        switch.onLux(3f)
        wait(2_000)
        switch.pause()
        wait(10_000)
        switch.onLux(1f)
        wait(10_000)
        assertEquals("paused: the reading and the one waiting are dropped", emptyList<Boolean>(), sides)
        switch.resume()
        switch.onLux(1f)
        wait(5_000)
        assertEquals(listOf(true), sides)
    }
}
