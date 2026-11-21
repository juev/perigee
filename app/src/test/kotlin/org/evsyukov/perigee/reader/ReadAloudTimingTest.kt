package org.evsyukov.perigee.reader

import android.app.Application
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.evsyukov.perigee.engine.ReadAloudSpan
import org.evsyukov.perigee.engine.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
class ReadAloudTimingTest {
    @Test
    fun aVoiceRangeHighlightsTheContainingSentenceInsteadOfTheWholeChunk() {
        val first = "🐉 Первый абзац."
        val second = "Второе предложение здесь!"
        val text = "$first $second Текст дальше."
        val span = ReadAloudSpan(TextRange("chunk-start", "chunk-end", text), null)
        val whole = SpeechChunk(span.range.start, span.range.end)
        val sentence = SpeechChunk("sentence-start", "sentence-end")
        val from = text.indexOf(second)
        val request = SpeechRequest(
            language = "ru-RU",
            start = span.range.start,
            load = { at, _ -> span.takeIf { at == span.range.start } },
            previous = { null },
            moveTo = { true },
            mapTiming = { submitted, start, end ->
                sentence.takeIf { submitted == span && start == from && end == from + second.length }
            },
        )
        withVoice(request) { voice ->
            assertEquals(text, voice.text)
            assertEquals(SpeechState(SpeechPhase.Playing, 1f, whole), request.state.value)
            val word = text.indexOf("предложение")
            voice.listener.onRangeStart(voice.id, word, word + "предложение".length, 0)
            drain()
            assertEquals("voice range must highlight only its sentence", sentence, request.state.value.chunk)
        }
    }

    @Test
    fun callbacksFromARetiredUtteranceCannotRestoreItsHighlight() {
        val span = ReadAloudSpan(TextRange("from", "to", "Wait here. Next sentence."), null)
        val whole = SpeechChunk("from", "to")
        val sentence = SpeechChunk("middle", "to")
        val entered = CompletableDeferred<Unit>()
        val mapped = CompletableDeferred<SpeechChunk?>()
        var calls = 0
        val request = SpeechRequest(
            language = "en-US",
            start = span.range.start,
            load = { _, _ -> span },
            previous = { null },
            moveTo = { true },
            mapTiming = { _, _, _ ->
                if (++calls == 1) {
                    entered.complete(Unit)
                    mapped.await()
                } else sentence
            },
        )
        withVoice(request) { voice ->
            val oldId = voice.id
            voice.listener.onRangeStart(oldId, 11, 15, 0)
            drain()
            assertEquals("the previous map is in flight", true, entered.isCompleted)
            checkNotNull(request.dispatch)(SpeechCommand.Pause, 1f)
            drain()
            assertEquals(SpeechState(SpeechPhase.Paused), request.state.value)
            mapped.complete(sentence)
            voice.listener.onRangeStart(oldId, 16, 24, 1)
            drain()
            assertNull(request.state.value.chunk)

            checkNotNull(request.dispatch)(SpeechCommand.Play, 1f)
            drain()
            assertNotEquals(oldId, voice.id)
            voice.listener.onRangeStart(oldId, 11, 15, 0)
            voice.listener.onDone(oldId)
            drain()
            assertEquals(1, calls)
            assertEquals(SpeechState(SpeechPhase.Playing, 1f, whole), request.state.value)
            voice.listener.onRangeStart(voice.id, 11, 15, 0)
            drain()
            assertEquals(sentence, request.state.value.chunk)
            checkNotNull(request.dispatch)(SpeechCommand.Stop, 1f)
            voice.listener.onRangeStart(voice.id, 16, 24, 1)
            drain()
            assertEquals(SpeechState(SpeechPhase.Ended), request.state.value)
        }
    }

    @Test
    fun optInMapsTheCurrentSentenceOnlyOnceWithoutRestartingTheVoice() {
        val span = ReadAloudSpan(TextRange("from", "to", "Before. Next sentence here."), null)
        val whole = SpeechChunk("from", "to")
        val sentence = SpeechChunk("middle", "to")
        val enabled = MutableStateFlow(false)
        val offsets = mutableListOf<Pair<Int, Int>>()
        val request = SpeechRequest(
            language = "en-US",
            start = span.range.start,
            load = { _, _ -> span },
            previous = { null },
            moveTo = { true },
            mapTiming = { _, start, end ->
                offsets += start to end
                sentence
            },
            timingEnabled = enabled,
        )
        withVoice(request) { voice ->
            val id = voice.id
            voice.listener.onRangeStart(id, 8, 12, 0)
            drain()
            assertEquals(emptyList<Pair<Int, Int>>(), offsets)
            assertEquals(whole, request.state.value.chunk)
            enabled.value = true
            drain()
            assertEquals(sentence, request.state.value.chunk)
            voice.listener.onRangeStart(id, 13, 21, 1)
            drain()
            voice.listener.onRangeStart(id, 22, 26, 2)
            drain()
            assertEquals(listOf(8 to span.range.text.length), offsets)
            enabled.value = false
            drain()
            assertEquals(id, voice.id)
            assertEquals(SpeechState(SpeechPhase.Playing, 1f, whole), request.state.value)
        }
    }

    private class VoicePlayback(
        val text: String,
        private val utterance: () -> String,
        val listener: UtteranceProgressListener,
    ) {
        val id get() = utterance()
    }

    // The platform shadow supplies a reproducible voice without imposing real-time speech.
    // Intercept its automatic completion; timing events are fed to the service's real listener.
    private fun withVoice(request: SpeechRequest, check: (VoicePlayback) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = Robolectric.buildService(ReadAloudService::class.java).create()
        try {
            ShadowTextToSpeech.addVoice(Voice("timing-fixture", Locale.forLanguageTag(request.language), 300, 300, false, emptySet()))
            shadowOf(context.getSystemService(AudioManager::class.java))
                .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            ReadAloudService.start(context, request)
            service.get().onStartCommand(checkNotNull(shadowOf(context).nextStartedService), 0, 1)
            val engine = checkNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
            val voice = shadowOf(engine)
            voice.onInitListener.onInit(TextToSpeech.SUCCESS)
            val listener = checkNotNull(voice.utteranceProgressListener)
            var id: String? = null
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { id = utteranceId }
                override fun onDone(utteranceId: String?) = Unit
                @Deprecated("Required by the Android TTS callback")
                override fun onError(utteranceId: String?) = Unit
            })
            drain()
            checkNotNull(id)
            check(VoicePlayback(checkNotNull(voice.lastSpokenText), { checkNotNull(id) }, listener))
        } finally {
            ReadAloudService.stop(request)
            service.destroy()
            drain()
        }
    }

    private fun drain() = shadowOf(Looper.getMainLooper()).idle()
}
