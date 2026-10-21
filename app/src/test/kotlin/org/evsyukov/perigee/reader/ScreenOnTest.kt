package org.evsyukov.perigee.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.evsyukov.perigee.data.ReaderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenOnTest {
    private val minute = 60_000L

    @Test
    fun screenStaysOnForTheChosenTimeAfterOpeningThenGoesToTheSystem() = runTest {
        var on: Boolean? = null
        val job = launch { keepScreenOn(5, MutableSharedFlow()) { on = it } }
        runCurrent()
        assertEquals(true, on)
        advanceTimeBy(5 * minute - 1)
        assertEquals(true, on)
        advanceTimeBy(2)
        assertEquals(false, on)
        job.cancel()
    }

    @Test
    fun eachTouchOrTurnStartsTheTimeAgain() = runTest {
        var on: Boolean? = null
        val activity = MutableSharedFlow<Unit>()
        val job = launch { keepScreenOn(5, activity) { on = it } }
        runCurrent()
        advanceTimeBy(4 * minute)
        activity.emit(Unit)
        advanceTimeBy(4 * minute)
        assertEquals("four minutes after the touch", true, on)
        advanceTimeBy(minute + 1)
        assertEquals(false, on)

        // A touch after the screen was let go keeps it on again.
        activity.emit(Unit)
        runCurrent()
        assertEquals(true, on)
        job.cancel()
    }

    @Test
    fun systemLeavesTheScreenAloneAndAlwaysKeepsItOn() = runTest {
        var on: Boolean? = null
        val system = launch { keepScreenOn(ReaderPrefs.SCREEN_ON_SYSTEM, MutableSharedFlow()) { on = it } }
        runCurrent()
        assertEquals(false, on)
        system.cancel()

        val always = launch { keepScreenOn(ReaderPrefs.SCREEN_ON_ALWAYS, MutableSharedFlow()) { on = it } }
        runCurrent()
        advanceTimeBy(24 * 60 * minute)
        assertEquals(true, on)
        always.cancel()
    }

    @Test
    fun leavingTheBookOrGoingToTheBackgroundHandsTheScreenBack() = runTest {
        var on = false
        val job = launch { keepScreenOn(ReaderPrefs.SCREEN_ON_ALWAYS, MutableSharedFlow()) { on = it } }
        runCurrent()
        assertTrue(on)
        job.cancel()
        runCurrent()
        assertFalse(on)
    }
}
