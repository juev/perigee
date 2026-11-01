package org.evsyukov.perigee.library

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.evsyukov.perigee.library.ImmersiveNoticeSeen.Companion.CONFIRMED
import org.evsyukov.perigee.library.ImmersiveNoticeSeen.Companion.KEY
import org.evsyukov.perigee.library.ImmersiveNoticeSeen.Companion.read
import org.evsyukov.perigee.library.ImmersiveNoticeSeen.Companion.write
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** [ImmersiveNoticeSeen] on the device's own setting, which each test leaves as it found it. */
@RunWith(AndroidJUnit4::class)
class ImmersiveNoticeSeenTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private var found: String? = null

    @Before
    fun keep() {
        found = read()
    }

    @After
    fun putBack() {
        write(found)
    }

    /** Runs [test] under the rule; what the setting was while it ran. */
    private fun underTheRule(test: () -> Unit = {}): String? {
        var during: String? = "not run"
        val body = object : Statement() {
            override fun evaluate() {
                during = read()
                test()
            }
        }
        ImmersiveNoticeSeen().apply(body, Description.EMPTY).evaluate()
        return during
    }

    @Test
    fun noSettingIsConfirmedForTheTestAndNoneIsLeftAfter() {
        write(null)

        assertEquals(CONFIRMED, underTheRule())
        assertNull(read())
    }

    @Test
    fun aSettingThereIsPutBackAsItWas() {
        // "null" is a word here, not the setting gone.
        for (value in listOf("null", "an earlier value", CONFIRMED)) {
            write(value)

            assertEquals(value, CONFIRMED, underTheRule())
            assertEquals(value, read())
        }
    }

    @Test
    fun aFailedTestFailsAsItDidWithTheSettingPutBack() {
        for (value in listOf(null, "an earlier value")) {
            write(value)
            val failure = AssertionError("the test failed")

            val thrown = assertThrows(AssertionError::class.java) { underTheRule { throw failure } }

            assertSame(failure, thrown)
            assertEquals(value, read())
        }
    }

    @Test
    fun theTestRunsWithoutTheShellsRightToWriteSettings() {
        write(null)

        underTheRule {
            assertThrows(SecurityException::class.java) { Settings.Secure.putString(resolver, KEY, "written by the test") }
        }
        assertNull(read())
    }
}
