package org.evsyukov.perigee.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.data.ReaderPreferences

/**
 * Speaks through an OpenAI-format server. A span is told sentence by sentence: each sentence is
 * fetched (one ahead is prefetched while the last one plays), written to a temp file and played
 * back. Speed is asked of the server in the request; a server that ignores it speaks at 1×.
 * Audio focus and the wake lock stay with the service, as with the device's own TTS.
 */
internal class HttpTtsEngine(private val context: Context, private val config: ExternalTtsConfig) : AloudEngine {
    override var failure: SpeechNote? = null
        private set
    private var voiceLocale: Locale? = null
    override val locale: Locale? get() = voiceLocale
    private var client: ExternalTtsClient? = null
    private var rate = 1f
    private val generation = AtomicInteger()
    private var player: MediaPlayer? = null
    private var current: CompletableDeferred<Boolean>? = null
    private var audioFile: File? = null
    /** The audio a [prefetch] made for the following span's first sentence, keyed by that sentence. */
    private var carried: Pair<String, ByteArray>? = null
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    override suspend fun prepare(language: String): Boolean {
        voiceLocale = Locale.forLanguageTag(language.trim().replace('_', '-'))
        val prefs = ReaderPreferences.get(context)
        val chosen = prefs.prefs.first().externalTtsVoice
        val session = ExternalTtsClient(
            url = config.baseUrl,
            key = config.apiKey,
            cached = Detected.fromJson(prefs.prefs.first().externalTtsDetected),
            chosenVoice = chosen,
            store = { json -> prefs.update { it.copy(externalTtsDetected = json) } },
        )
        // The probes are network: never on the main thread the service speaks from.
        if (withContext(Dispatchers.IO) { session.detect() } == null) {
            failure = session.failure ?: SpeechNote.Synthesis
            Log.w(TAG, "external speech did not prepare: $failure")
            return false
        }
        client = session
        return true
    }

    override fun setRate(rate: Float): Boolean {
        this.rate = rate
        return true
    }

    override suspend fun speak(text: String, onRange: (Int, Int) -> Unit): Boolean = coroutineScope {
        val session = client ?: return@coroutineScope false
        val gen = generation.get()
        val sentences = splitSentences(text, voiceLocale)
        var prefetched: Deferred<ByteArray?>? = null
        var ok = true
        for ((index, range) in sentences.withIndex()) {
            var bytes: ByteArray? = null
            // What [prefetch] synthesized while the last span was still playing, for its first sentence.
            if (index == 0) {
                carried?.takeIf { it.first == text.substring(range) }?.let { bytes = it.second }
                carried = null
            }
            prefetched?.let { bytes = bytes ?: it.await(); prefetched = null }
            if (bytes == null) {
                bytes = withContext(Dispatchers.IO) { session.synthesize(text.substring(range), rate) }
                if (bytes == null) {
                    failure = session.failure ?: SpeechNote.Synthesis
                    ok = false
                    break
                }
            }
            if (generation.get() != gen) {
                ok = false
                break
            }
            prefetched = sentences.getOrNull(index + 1)?.let { next ->
                async(Dispatchers.IO) { session.synthesize(text.substring(next), rate) }
            }
            onRange(range.first, range.last + 1)
            if (!play(bytes)) {
                ok = false
                break
            }
            if (generation.get() != gen) {
                ok = false
                break
            }
        }
        prefetched?.cancel()
        ok
    }

    override suspend fun prefetch(text: String) {
        val session = client ?: return
        val gen = generation.get()
        val first = splitSentences(text, voiceLocale).firstOrNull() ?: return
        val sentence = text.substring(first)
        val bytes = withContext(Dispatchers.IO) { session.synthesize(sentence, rate) } ?: return
        if (generation.get() != gen) return
        carried = sentence to bytes
    }

    /** One sentence spoken aloud; a fetch that 404s has already had its one re-probe inside [ExternalTtsClient]. */
    private suspend fun play(bytes: ByteArray): Boolean {
        val ext = client?.audioExt ?: ".mp3"
        val file = withContext(Dispatchers.IO) {
            audioFile?.delete()
            File.createTempFile("perigee-tts", ext, context.cacheDir).also { saved ->
                saved.writeBytes(bytes)
                audioFile = saved
            }
        }
        // The player is made and released on the main thread, so [stop] may reach it from there too.
        val done = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            val media = MediaPlayer()
            player = media
            current = done
            media.setAudioAttributes(attributes)
            media.setDataSource(file.path)
            // Prepared is not playing: without start() the player sits silent and completes nothing.
            media.setOnPreparedListener { it.start() }
            media.setOnCompletionListener { if (current === done) done.complete(true) }
            media.setOnErrorListener { _, _, _ -> if (current === done) done.complete(false); true }
            media.prepareAsync()
        }
        try {
            val finished = done.await()
            if (!finished) Log.w(TAG, "the player stopped or erred mid-playback")
            return finished
        } finally {
            withContext(Dispatchers.Main) {
                if (current === done) {
                    player?.release()
                    player = null
                    current = null
                }
            }
        }
    }

    override fun stop() {
        generation.incrementAndGet()
        current?.complete(false)
        current = null
        player?.release()
        player = null
    }

    override fun release() {
        stop()
        audioFile?.delete()
        audioFile = null
        carried = null
        client = null
        voiceLocale = null
    }

    internal companion object {
        /** Where each sentence of [text] begins and ends, trimmed of the whitespace around it. */
        internal fun splitSentences(text: String, locale: Locale?): List<IntRange> {
            val boundaries = BreakIterator.getSentenceInstance(locale ?: Locale.ROOT).also { it.setText(text) }
            val ranges = mutableListOf<IntRange>()
            var from = boundaries.first()
            var to = boundaries.next()
            while (to != BreakIterator.DONE) {
                var start = from
                var end = to
                while (start < end && text[start].isWhitespace()) start++
                while (end > start && text[end - 1].isWhitespace()) end--
                if (start < end) ranges += start until end
                from = to
                to = boundaries.next()
            }
            return ranges
        }
    }
}
