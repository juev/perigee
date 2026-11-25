package org.evsyukov.perigee.reader

import android.app.Application
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.evsyukov.perigee.data.ReaderPreferences
import org.evsyukov.perigee.engine.ReadAloudSpan
import org.evsyukov.perigee.engine.TextRange
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The service through an engine the test provides: phases, commands and the notes a failure carries. */
@RunWith(RobolectricTestRunner::class)
class ReadAloudEngineTest {
    private val defaultFactory = ReadAloudService.engineFactory
    private val defaultCacheClear = ReadAloudService.clearExternalTtsDetected
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun grantAudioFocus() {
        shadowOf(context.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @After
    fun restoreTheDefaultEngine() {
        ReadAloudService.engineFactory = defaultFactory
        ReadAloudService.clearExternalTtsDetected = defaultCacheClear
    }

    private class FakeEngine(
        val prepareResult: Boolean = true,
        override var failure: SpeechNote? = null,
        val beforePrepare: suspend () -> Unit = {},
    ) : AloudEngine {
        override val locale: Locale? = Locale.US
        val spoken = mutableListOf<String>()
        val prefetched = mutableListOf<String>()
        val released = CompletableDeferred<Unit>()
        private var stopped = false

        override suspend fun prepare(language: String): Boolean {
            beforePrepare()
            return prepareResult
        }

        override fun setRate(rate: Float) = true

        override suspend fun speak(text: String, onRange: (Int, Int) -> Unit): Boolean {
            if (stopped) return false
            spoken += text
            onRange(0, text.length.coerceAtMost(6))
            return true
        }

        override suspend fun prefetch(text: String) {
            prefetched += text
        }

        override fun stop() {
            stopped = true
        }

        override fun release() {
            stopped = true
            released.complete(Unit)
        }
    }

    private fun request(span: ReadAloudSpan, external: ExternalTtsConfig? = null, beforeLoad: () -> Unit = {}) = SpeechRequest(
        language = "en-US",
        start = span.range.start,
        load = { at, _ ->
            beforeLoad()
            span.takeIf { at == span.range.start }
        },
        previous = { null },
        moveTo = { true },
        mapTiming = { _, start, end -> SpeechChunk("s$start", "e$end") },
        externalTts = external,
    )

    /** Starts one session and pumps the service by hand, as the reader would. */
    private fun runSession(run: SpeechRequest, engine: AloudEngine?) {
        ReadAloudService.engineFactory = { _, _ -> engine }
        val service = Robolectric.buildService(ReadAloudService::class.java).create()
        try {
            ReadAloudService.start(context, run)
            service.get().onStartCommand(checkNotNull(shadowOf(context).nextStartedService), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            ReadAloudService.stop(run)
            service.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun aSessionSpeaksItsSpanAndEndsCleanly() {
        val span = ReadAloudSpan(TextRange("from", "to", "One sentence only."), null)
        val run = request(span)
        val engine = FakeEngine()
        runSession(run, engine)
        assertEquals("the span is spoken", listOf("One sentence only."), engine.spoken)
        assertEquals(SpeechPhase.Ended, run.state.value.phase)
        assertNull(run.state.value.note)
        assertTrue("the engine was released with the session", engine.released.isCompleted)
    }

    @Test
    fun aChosenEngineThatCannotBeMadeFailsTheSession() {
        val span = ReadAloudSpan(TextRange("from", "to", "Nothing is spoken."), null)
        val run = request(span, external = ExternalTtsConfig("http://tts.local", ""))
        runSession(run, engine = null)
        assertEquals(SpeechPhase.Failed, run.state.value.phase)
        assertNull(run.state.value.note)
    }

    @Test
    fun aFailingPrepareCarriesItsNoteToTheState() {
        val span = ReadAloudSpan(TextRange("from", "to", "Nothing is spoken."), null)
        val run = request(span, external = ExternalTtsConfig("http://tts.local", ""))
        val engine = FakeEngine(prepareResult = false, failure = SpeechNote.NotFound)
        runSession(run, engine)
        assertEquals(SpeechPhase.Failed, run.state.value.phase)
        assertEquals(SpeechNote.NotFound, run.state.value.note)
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun theNextSpanIsSynthesizedWhileTheCurrentOneIsSpoken() {
        val first = ReadAloudSpan(TextRange("from", "to", "First span text."), "from2")
        val second = ReadAloudSpan(TextRange("from2", "to2", "Second span text."), null)
        val run = SpeechRequest(
            language = "en-US",
            start = first.range.start,
            load = { at, _ -> if (at == first.range.start) first else second.takeIf { at == second.range.start } },
            previous = { null },
            moveTo = { true },
        )
        val engine = FakeEngine()
        runSession(run, engine)
        assertEquals(
            "both spans are spoken, in order",
            listOf("First span text.", "Second span text."),
            engine.spoken,
        )
        assertEquals(
            "the following span was prefetched during the speech of this one",
            listOf("Second span text."),
            engine.prefetched,
        )
    }

    @Test
    fun stoppingByHandClearsTheDetectedServerOfTheNextRun() = runBlocking<Unit> {
        val span = ReadAloudSpan(TextRange("from", "to", "One sentence only."), null)
        val run = request(span, external = ExternalTtsConfig("http://tts.local", ""))
        val prefs = ReaderPreferences.get(context)
        val detected = """{"base":"http://tts.local","path":"/v1/audio/speech","model":"tts-1","voice":"alloy","kind":"raw","field":""}"""
        prefs.update { it.copy(externalTtsDetected = detected) }
        val resetStarted = CompletableDeferred<Unit>()
        val allowReset = CompletableDeferred<Unit>()
        ReadAloudService.clearExternalTtsDetected = { context ->
            resetStarted.complete(Unit)
            allowReset.await()
            defaultCacheClear(context)
        }

        val service = Robolectric.buildService(ReadAloudService::class.java).create()
        try {
            ReadAloudService.start(context, run)
            service.get().onStartCommand(checkNotNull(shadowOf(context).nextStartedService), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            checkNotNull(run.dispatch)(SpeechCommand.Stop, 1f)
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            service.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(SpeechPhase.Ended, run.state.value.phase)
        resetStarted.await()

        val observed = mutableListOf<String>()
        val nextStarted = CompletableDeferred<Unit>()
        val next = request(span, external = ExternalTtsConfig("http://tts.local", ""), beforeLoad = { nextStarted.complete(Unit) })
        val engine = FakeEngine(beforePrepare = { observed += prefs.prefs.first().externalTtsDetected })
        val nextService = Robolectric.buildService(ReadAloudService::class.java).create()
        try {
            ReadAloudService.engineFactory = { _, _ -> engine }
            ReadAloudService.start(context, next)
            nextService.get().onStartCommand(checkNotNull(shadowOf(context).nextStartedService), 0, 2)
            nextStarted.await()
            allowReset.complete(Unit)
            withTimeout(5_000) {
                while (observed.isEmpty()) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(1)
                }
            }
            assertEquals("an immediate restart must wait for the manual invalidation", listOf(""), observed)
        } finally {
            allowReset.complete(Unit)
            ReadAloudService.stop(next)
            nextService.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
