package org.evsyukov.perigee.reader

import android.content.Context
import android.graphics.Rect
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.data.ReaderPrefs
import org.evsyukov.perigee.engine.BookReader
import org.json.JSONArray
import org.json.JSONObject

private val generationCounter = AtomicInteger(0)

/** One sentence on the page the reader speaks. */
data class Sentence(
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
)

/** Android built-in TextToSpeech API. Used when no server URL is configured. */
private class AndroidTtsEngine(
    context: Context,
    private val onComplete: (String) -> Unit,
) {
    private var tts: TextToSpeech? = null
    private var isInit = false
    private var pendingText: String? = null
    private val TAG = "AndroidTts"

    init {
        tts = try {
            TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isInit = true
                    Log.i(TAG, "init OK")
                    pendingText?.let { speakInternal(it) }
                    pendingText = null
                } else {
                    Log.w(TAG, "init failed status=$status")
                }
            }
        } catch (e: Exception) { Log.w(TAG, "init exc: ${e.message}"); null }
    }

    fun speak(text: String) {
        if (!isInit) {
            pendingText = text
            Log.i(TAG, "queued: ${text.take(40)}")
            return
        }
        speakInternal(text)
    }

    fun stop() {
        pendingText = null
        runCatching { tts?.stop() }
    }

    fun release() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun speakInternal(text: String) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(utteranceId: String) {
                Log.i(TAG, "#$utteranceId done")
                onComplete(utteranceId)
            }
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "#$utteranceId err")
                onComplete(utteranceId ?: "")
            }
            override fun onStart(utteranceId: String?) {}
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "0")
    }
}

class TtsController(
    context: Context,
    private val getReader: () -> BookReader?,
    private val getPrefs: () -> ReaderPrefs,
    private val turnPage: suspend () -> Unit,
    private val onState: (TtsState) -> Unit,
    private val scope: CoroutineScope,
) {
    private var sentences: List<Sentence> = emptyList()
    private var currentIndex = 0
    private var mediaPlayer: MediaPlayer? = null
    private var mediaFile: File? = null
    private var currentPageXPointer: String? = null
    private var selectedVoice: String = ""
    private var speed: Float = 1.0f
    private var prefetchedAudio: ByteArray? = null
    private var prefetchIndex = -1
    private var prefetchJob: Job? = null
    private var generation = 0
    private val TAG = "TtsCtl"
    private var androidTts: AndroidTtsEngine? = null
    private val appContext: Context = context.applicationContext

    // Engine priority: external server (if on and URL set) → Android TTS (if on) → none.
    private fun wantServer(): Boolean {
        val prefs = getPrefs()
        return prefs.ttsUseServer && prefs.ttsSynthUrl.isNotBlank()
    }

    private fun log(msg: String) { Log.i(TAG, msg) }

    private fun logw(msg: String) { Log.w(TAG, msg) }

    fun play() {
        log("play idx=$currentIndex s=${sentences.size}")
        val reader = getReader() ?: return
        if (sentences.isNotEmpty() && currentIndex < sentences.size) { speakSentence(currentIndex); return }
        loadPage(reader)
    }

    fun pause() {
        runCatching { mediaPlayer?.pause() }
        // Android TTS has no pause: stop now, re-read the sentence on resume.
        androidTts?.stop()
    }

    fun resume() {
        if (androidTts != null && mediaPlayer == null) { speakSentence(currentIndex); return }
        runCatching { if (mediaPlayer?.isPlaying == false) mediaPlayer?.start() }
    }

    fun stop() {
        generation = generationCounter.incrementAndGet()
        log("stop gen=$generation")
        androidTts?.stop()
        prefetchJob?.cancel(); prefetchJob = null; prefetchedAudio = null
        runCatching { mediaPlayer?.stop() }; runCatching { mediaPlayer?.release() }
        mediaPlayer = null; mediaFile?.delete(); mediaFile = null
        sentences = emptyList(); currentIndex = 0; currentPageXPointer = null
    }

    fun setSpeed(s: Float) { speed = s }

    fun selectVoice(voiceId: String) { selectedVoice = voiceId }

    fun onPageChanged() {
        if (sentences.isEmpty()) return
        generation = generationCounter.incrementAndGet()
        prefetchJob?.cancel(); prefetchJob = null; prefetchedAudio = null
        runCatching { mediaPlayer?.stop() }; runCatching { mediaPlayer?.release() }
        mediaPlayer = null; sentences = emptyList(); currentIndex = 0
        val reader = getReader() ?: return
        loadPage(reader)
    }

    fun restartCurrentSentence() {
        prefetchJob?.cancel(); prefetchedAudio = null
        runCatching { mediaPlayer?.stop() }; runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        if (currentIndex >= 0 && currentIndex < sentences.size) speakSentence(currentIndex)
    }

    suspend fun fetchVoices(): List<TtsVoice> = withContext(Dispatchers.IO) {
        val url = getPrefs().ttsVoicesUrl
        if (url.isBlank()) return@withContext emptyList()
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val code = conn.responseCode
            if (code !in 200..299) return@withContext emptyList()
            val json = conn.inputStream.use { it.reader().readText() }
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("voices") ?: obj.optJSONArray("data") ?: return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val id = arr.optString(i, ""); if (id.isNotBlank()) TtsVoice(id, id) else null
            }.filterNotNull()
        } catch (e: Exception) { logw("fetchVoices: ${e.message}"); emptyList() }
    }

    fun release() {
        androidTts?.release(); androidTts = null
        stop()
    }

    // ─── private ──────────────────────────────────

    private fun loadPage(reader: BookReader) {
        scope.launch {
            val pageText = reader.read { doc ->
                val top = doc.xpointer()
                val next = doc.xpointerOfPage(doc.currentPage + 1)
                if (next != null) doc.textBetween(top, next).orEmpty() else ""
            }
            val bookLang = try { reader.read { doc -> doc.info().language } } catch (_: Exception) { "" }
            if (pageText.isBlank()) return@launch
            currentPageXPointer = reader.read { doc -> doc.xpointer() }
            sentences = splitSentences(pageText, localeOf(bookLang))
            log("loadPage: ${pageText.length}c ${sentences.size}s")
            currentIndex = 0
            if (sentences.isNotEmpty()) speakSentence(0)
        }
    }

    private fun speakSentence(index: Int) {
        if (index !in sentences.indices) return
        val sentence = sentences[index]; currentIndex = index
        val gen = generation
        log("spk #$index: ${sentence.text.take(40)}")

        onState(TtsState(playing=true, sentenceText=sentence.text, sentenceIndex=index,
            sentenceCount=sentences.size, highlightRects=emptyList(),
            speed=speed, selectedVoice=selectedVoice, generation=gen))

        scope.launch {
            delay(200)
            if (generation != gen) return@launch
            val rects = findHighlightRects(sentence)
            if (generation != gen) return@launch
            onState(TtsState(playing=true, sentenceText=sentence.text, sentenceIndex=index,
                sentenceCount=sentences.size, highlightRects=rects,
                speed=speed, selectedVoice=selectedVoice, generation=gen))
        }

        // Pick the engine by the CURRENT prefs: server on + URL → HTTP, else Android TTS, else none.
        if (wantServer()) {
            // HTTP: stop the native engine if it was speaking.
            androidTts?.stop()
            val cached = prefetchedAudio; prefetchedAudio = null
            scope.launch {
                if (generation != gen) return@launch
                val audio = cached ?: synthesize(sentence.text) ?: run {
                    if (generation != gen) return@launch
                    // Show why nothing plays, then wait and move on.
                    onState(TtsState(playing=false, sentenceText=sentence.text, sentenceIndex=index,
                        sentenceCount=sentences.size, speed=speed, selectedVoice=selectedVoice,
                        generation=gen, error=lastError ?: "TTS: synthesis failed"))
                    delay(5000); if (generation != gen) return@launch
                    onSentenceEnd(gen); return@launch
                }
                if (generation != gen) return@launch
                onState(TtsState(playing=true, sentenceText=sentence.text, sentenceIndex=index,
                    sentenceCount=sentences.size, speed=speed, selectedVoice=selectedVoice,
                    generation=gen, error=null))
                playAudio(audio) { onSentenceEnd(gen) }
            }

            prefetchNext(index + 1)
        } else if (getPrefs().ttsUseAndroid) {
            // Android TTS speaks directly (no HTTP, no MediaPlayer).
            val nativeTts = androidTts ?: AndroidTtsEngine(appContext) { onSentenceEnd(generation) }
                .also { androidTts = it; log("engine=AndroidTtsEngine") }
            nativeTts.speak(sentence.text)
        } else {
            logw("no TTS engine: both toggles off")
            onState(TtsState(playing=false, sentenceText=sentence.text, sentenceIndex=index,
                sentenceCount=sentences.size, speed=speed, selectedVoice=selectedVoice,
                generation=gen, unavailable=true))
        }
    }

    private fun prefetchNext(index: Int) {
        if (index >= sentences.size || prefetchIndex == index) return
        prefetchJob?.cancel(); prefetchIndex = index
        prefetchJob = scope.launch {
            val audio = synthesize(sentences[index].text)
            if (audio != null) prefetchedAudio = audio
        }
    }

    private fun onSentenceEnd(gen: Int) {
        if (generation != gen) return
        val next = currentIndex + 1
        log("onEnd n=$next s=${sentences.size}")
        if (next < sentences.size) { speakSentence(next); return }
        currentIndex = 0; sentences = emptyList()
        scope.launch {
            if (generation != gen) return@launch
            val reader = getReader() ?: return@launch
            if (reader.read { doc -> doc.currentPage >= doc.pageCount - 1 }) return@launch
            if (generation != gen) return@launch
            turnPage()
            delay(300)
            val reader2 = getReader() ?: return@launch
            loadPage(reader2)
        }
    }

    private suspend fun findHighlightRects(sentence: Sentence): List<Rect> {
        val reader = getReader() ?: return emptyList()
        val words = sentence.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val startKey = words.take(4).joinToString(" ")
        val endKey = words.takeLast(4).joinToString(" ")
        if (startKey.length < 2) return emptyList()
        return try { reader.read { doc ->
            val cp = doc.currentPage
            val sh = doc.search(startKey, caseInsensitive=true, maxHits=5)
                .firstOrNull { it.page == cp } ?: return@read emptyList()
            if (endKey == startKey || words.size <= 4) doc.rects(sh.range.start, sh.range.end) else {
                val eh = doc.search(endKey, caseInsensitive=true, maxHits=5)
                    .firstOrNull { it.page == cp }
                if (eh != null) doc.rects(sh.range.start, eh.range.end) else doc.rects(sh.range.start, sh.range.end)
            }
        } } catch (_: Exception) { emptyList() }
    }

    private suspend fun synthesize(text: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val prefs = getPrefs()
            if (prefs.ttsSynthUrl.isBlank()) return@withContext null
            val url = URL(prefs.ttsSynthUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            val hdr = prefs.ttsCustomHeader?.trim() ?: ""
            if (hdr.isNotBlank()) {
                val colon = hdr.indexOf(':')
                if (colon > 0) conn.setRequestProperty(hdr.substring(0, colon).trim(), hdr.substring(colon + 1).trim())
            }
            conn.connectTimeout = 10000; conn.readTimeout = 60000; conn.doOutput = true
            val body = JSONObject().apply {
                put("model", if (selectedVoice.isNotBlank()) selectedVoice else "tts-1")
                put("input", text); put("voice", if (selectedVoice.isNotBlank()) selectedVoice else "")
                put("response_format", "mp3"); put("speed", speed.toDouble())
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = runCatching { conn.errorStream?.use { it.reader().readText() }?.take(200) }.getOrNull()
                logw("synthesize: HTTP $code $detail")
                lastError = "HTTP $code" + (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                return@withContext null
            }
            lastError = null
            conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            logw("synthesize: ${e.message}")
            lastError = e.message ?: "network error"
            null
        }
    }

    /** The last synthesis error, or null. Read right after synthesize returns null. */
    private var lastError: String? = null

    private suspend fun playAudio(mp3: ByteArray, onDone: () -> Unit) {
        runCatching { mediaPlayer?.stop() }; runCatching { mediaPlayer?.release() }
        val file = withContext(Dispatchers.IO) {
            mediaFile?.delete()
            val f = File.createTempFile("tts", ".mp3"); f.writeBytes(mp3); f
        }
        mediaFile = file; mediaPlayer = null
        withContext(Dispatchers.IO) {
            val player = MediaPlayer().apply {
                setDataSource(file.path); setOnCompletionListener { onDone() }; prepare(); start()
            }
            mediaPlayer = player
        }
    }
}

// — test helpers — //

internal fun splitSentences(text: String, locale: Locale = Locale.US): List<Sentence> {
    if (text.isBlank()) return emptyList()
    val it = BreakIterator.getSentenceInstance(locale); it.setText(text)
    val result = mutableListOf<Sentence>(); var start = it.first(); var end = it.next()
    while (end != BreakIterator.DONE) {
        val st = text.substring(start, end).trim()
        if (st.isNotBlank()) result.add(Sentence(st, start, end))
        start = end; end = it.next()
    }
    return result
}

internal fun localeOf(lang: String): Locale {
    if (lang.isBlank()) return Locale.US
    val parts = lang.split("-")
    return when {
        parts.size >= 2 -> if (parts[1].length == 2) Locale(parts[0], parts[1]) else Locale.US
        lang.length == 2 -> Locale(lang)
        else -> Locale.US
    }
}