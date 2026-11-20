package org.evsyukov.perigee.reader

import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.evsyukov.perigee.engine.ReadAloudSpan
import org.evsyukov.perigee.engine.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android's real engine and service; the disposable emulator may not have an offline voice. */
@RunWith(AndroidJUnit4::class)
class ReadAloudPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val power = instrumentation.targetContext.getSystemService(PowerManager::class.java)

    @Test
    fun completedUtterancesAdvanceAndStopWithTheEmulatorScreenOff() {
        assumeTrue("requires a disposable Android emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeTrue("requires an initially interactive emulator", power.isInteractive)
        val startedNextInDark = AtomicBoolean()
        val reachedEndInDark = AtomicBoolean()
        val first = ReadAloudSpan(TextRange("first", "between", "A page in a book. ".repeat(12)), "second")
        val last = ReadAloudSpan(TextRange("second", "end", "This is the second passage."), null)
        val request = SpeechRequest(
            language = "en-US",
            start = "first",
            load = { at, _ -> when (at) { "first" -> first; "second" -> last; else -> null } },
            previous = { null },
            moveTo = { at ->
                if (at == "second") startedNextInDark.set(!power.isInteractive)
                if (at == "end") reachedEndInDark.set(!power.isInteractive)
                true
            },
        )
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { ReadAloudService.start(it, request) }
                await { request.state.value.phase != SpeechPhase.Starting }
                assumeTrue("no installed offline English voice in this emulator", request.state.value.phase != SpeechPhase.NoVoice)
                assertEquals(SpeechPhase.Playing, request.state.value.phase)
                val controls = findMediaControls()
                controls.pause()
                await { request.state.value.phase == SpeechPhase.Paused }
                controls.play()
                await { request.state.value.phase == SpeechPhase.Playing }
                sendKey(223) // KEYCODE_SLEEP, never a toggle.
                await { !power.isInteractive }
                await { request.state.value.phase in setOf(SpeechPhase.Ended, SpeechPhase.Failed, SpeechPhase.NoVoice) }
                assertEquals(SpeechPhase.Ended, request.state.value.phase)
                assertTrue("another utterance began after the screen went off", startedNextInDark.get())
                assertTrue("the last utterance completed while the screen remained off", reachedEndInDark.get())
                assertFalse(power.isInteractive)
            } finally {
                sendKey(224) // KEYCODE_WAKEUP.
                instrumentation.runOnMainSync { ReadAloudService.stop(request) }
                await { power.isInteractive }
                shell("wm dismiss-keyguard")
            }
        }
    }

    private fun findMediaControls(): MediaController.TransportControls {
        val automation = instrumentation.uiAutomation
        // The shell may discover active sessions; commands below use the real session token.
        automation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        return try {
            val context = instrumentation.targetContext
            context.getSystemService(MediaSessionManager::class.java).getActiveSessions(null)
                .single { it.packageName == context.packageName }.transportControls
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun sendKey(code: Int) = shell("input keyevent $code")

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(45_000) { while (!condition()) delay(40) }
    }
}
