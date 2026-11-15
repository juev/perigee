package org.evsyukov.perigee.reader

import android.graphics.Rect
import android.media.MediaPlayer
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

class TtsController(
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

    fun play() {
        val reader = getReader() ?: return
        if (sentences.isNotEmpty() && currentIndex < sentences.size) { speakSentence(currentIndex); return }
        loadPage(reader)
    }

    fun pause() { runCatching { mediaPlayer?.pause() } }
    fun resume() { runCatching { if (mediaPlayer?.isPlaying == false) mediaPlayer?.start() } }

    fun stop() {
        generation = generationCounter.incrementAndGet()
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
        } catch (_: Exception) { emptyList() }
    }

    fun release() { stop() }

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
            currentIndex = 0
            if (sentences.isNotEmpty()) speakSentence(0)
        }
    }

    private fun speakSentence(index: Int) {
        if (index !in sentences.indices) return
        val sentence = sentences[index]; currentIndex = index
        val gen = generation

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

        val cached = prefetchedAudio; prefetchedAudio = null
        scope.launch {
            if (generation != gen) return@launch
            val audio = cached ?: synthesize(sentence.text) ?: run {
                if (generation != gen) return@launch
                delay(5000); if (generation != gen) return@launch
                onSentenceEnd(gen); return@launch
            }
            if (generation != gen) return@launch
            playAudio(audio) { onSentenceEnd(gen) }
        }

        prefetchNext(index + 1)
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
            conn.inputStream.use { it.readBytes() }
        } catch (_: Exception) { null }
    }

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