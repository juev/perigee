package org.evsyukov.perigee.reader

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** The device's own synthesis: a system TextToSpeech with an installed offline voice for the book's language. */
internal class AndroidTtsEngine(private val context: Context) : AloudEngine {
    override var failure: SpeechNote? = null
    private var tts: TextToSpeech? = null
    private var voiceLocale: Locale? = null
    override val locale: Locale? get() = voiceLocale
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var pending: PendingUtterance? = null

    private class PendingUtterance(val id: String) {
        val reply = CompletableDeferred<Boolean>()
        var onRange: ((Int, Int) -> Unit)? = null
    }

    override suspend fun prepare(language: String): Boolean {
        tts?.let { return true }
        val ready = CompletableDeferred<Int>()
        val engine = TextToSpeech(context) { ready.complete(it) }
        try {
            val status = withTimeoutOrNull(10_000) { ready.await() }
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "the device TTS did not init (status=$status)")
                return false
            }
            val voice = ReadAloudService.localVoice(engine.voices.orEmpty(), engine.defaultVoice, language)
            if (voice == null) return false // The service reports this as NoVoice, with its own line.
            if (engine.setVoice(voice) != TextToSpeech.SUCCESS || engine.setAudioAttributes(attributes) != TextToSpeech.SUCCESS) {
                Log.w(TAG, "the device TTS refused ${voice.name} or its audio attributes")
                return false
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    handler.post {
                        pending?.takeIf { it.id == utteranceId }?.onRange?.invoke(start, end)
                    }
                }
                override fun onDone(utteranceId: String?) = result(utteranceId, true)
                @Deprecated("Required by the Android TTS callback")
                override fun onError(utteranceId: String?) = result(utteranceId, false)
                override fun onError(utteranceId: String?, errorCode: Int) = result(utteranceId, false)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = result(utteranceId, false)
            })
            voiceLocale = voice.locale
            tts = engine
            return true
        } finally {
            if (tts !== engine) engine.shutdown()
        }
    }

    private fun result(id: String?, success: Boolean) {
        handler.post { pending?.takeIf { it.id == id }?.reply?.complete(success) }
    }

    override fun setRate(rate: Float): Boolean = tts?.setSpeechRate(rate) == TextToSpeech.SUCCESS

    override suspend fun speak(text: String, onRange: (Int, Int) -> Unit): Boolean {
        val engine = tts ?: run { Log.w(TAG, "the device TTS is not prepared"); return false }
        val utterance = PendingUtterance(UUID.randomUUID().toString()).also { it.onRange = onRange }
        pending = utterance
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance.id) != TextToSpeech.SUCCESS) {
            Log.w(TAG, "the device TTS refused the utterance")
            pending = null
            return false
        }
        val done = utterance.reply.await()
        if (!done) Log.w(TAG, "the device TTS stopped or erred mid-utterance")
        return done
    }

    override fun stop() {
        pending = null
        tts?.stop()
    }

    override fun release() {
        pending = null
        tts?.shutdown()
        tts = null
        voiceLocale = null
    }
}
