package org.evsyukov.perigee.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeEngineTest {
    @Test
    fun reportsKoreaderDomVersion() {
        // The bookOrbit position converter assumes the DOM KOReader builds;
        // a different version here means xpointers stop matching the server.
        assertEquals(20260812, Engine.domVersion)
    }
}
