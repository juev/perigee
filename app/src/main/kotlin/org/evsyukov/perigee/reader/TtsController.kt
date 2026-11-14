package org.evsyukov.perigee.reader

import android.graphics.Rect
import android.media.MediaPlayer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.BreakIterator
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.data.ReaderPrefs
import org.evsyukov.perigee.engine.BookReader
import org.json.JSONArray
import org.json.JSONObject

/** One sentence on the page the reader speaks. */
data class Sentence(
    val text: String,
    /** Offset of the sentence start within the page text, in characters. */
    val startOffset: Int,
    /** Offset of the sentence end within the page text, in characters. */
    val endOffset: Int,
)

/**
 * Reads a book aloud over HTTP POST (text → MP3) and plays it with MediaPlayer.
 *
 * Holds no reference to the reader or prefs — gets them fresh through lambdas so
 * that the ViewModel can swap the underlying book without the controller knowing.
 */
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
    /** The temp file the current [mediaPlayer] plays; deleted when the next file is written. */
    private var mediaFile: File? = null
    private var currentPageXPointer: String? = null
    private var selectedVoice: String = ""
    private var speed: Float = 1.0f
    /** Audio bytes of the next sentence, pre-fetched while the current one plays. */
    private var prefetchedAudio: ByteArray? = null
    /** Cancels the prefetch when the player moves to a different sentence. */
    private var prefetchJob: kotlinx.coroutines.Job? = null

    /** Starts or resumes reading. */
    fun play() {
        val reader = getReader() ?: return
        if (sentences.isNotEmpty() && currentIndex < sentences.size) {
            speakSentence(currentIndex)
            return
        }
        loadPage(reader)
    }

    /** Pauses without resetting position. */
    fun pause() {
        mediaPlayer?.pause()
    }

    /** Resumes after pause. */
    fun resume() {
        mediaPlayer?.start()
    }

    /** Stops and releases everything. */
    fun stop() {
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchedAudio = null
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        mediaFile?.delete()
        mediaFile = null
        sentences = emptyList()
        currentIndex = 0
        currentPageXPointer = null
    }

    fun setSpeed(s: Float) { speed = s }

    fun selectVoice(voiceId: String) { selectedVoice = voiceId }

    /** Stops the current sentence and re-reads it with the new voice. */
    fun restartCurrentSentence() {
        prefetchJob?.cancel()
        prefetchedAudio = null
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        if (currentIndex >= 0 && currentIndex < sentences.size) {
            speakSentence(currentIndex)
        }
    }

    /** Fetches the voice list from the server URL. */
    suspend fun fetchVoices(): List<TtsVoice> = withContext(Dispatchers.IO) {
        val url = getPrefs().ttsVoicesUrl
        if (url.isBlank()) return@withContext emptyList()
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val json = conn.inputStream.use { it.reader().readText() }
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("voices") ?: obj.optJSONArray("data") ?: JSONArray(json)
            (0 until arr.length()).map { i ->
                val id = arr.optString(i, "")
                if (id.isNotBlank()) TtsVoice(id, id) else null
            }.filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Releases the player; the controller is not usable after this. */
    fun release() {
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
            if (pageText.isBlank()) return@launch
            currentPageXPointer = reader.read { doc -> doc.xpointer() }
            sentences = splitSentences(pageText)
            currentIndex = 0
            if (sentences.isNotEmpty()) speakSentence(0)
        }
    }

    private fun speakSentence(index: Int) {
        if (index < 0 || index >= sentences.size) return
        val sentence = sentences[index]
        currentIndex = index

        // Update state with the sentence text and progress.
        onState(
            TtsState(
                playing = true,
                sentenceText = sentence.text,
                sentenceIndex = index,
                sentenceCount = sentences.size,
                highlightRects = emptyList(),
                speed = speed,
                selectedVoice = selectedVoice,
            ),
        )

        // Fetch highlight rects from the engine.
        scope.launch {
            val rects = findHighlightRects(sentence)
            onState(TtsState(
                playing = true,
                sentenceText = sentence.text,
                sentenceIndex = index,
                sentenceCount = sentences.size,
                highlightRects = rects,
                speed = speed,
                selectedVoice = selectedVoice,
            ))
        }

        // Use pre-fetched audio or synthesize now.
        val cached = prefetchedAudio
        prefetchedAudio = null
        scope.launch {
            val audio = cached ?: synthesize(sentence.text) ?: return@launch
            playAudio(audio) { onSentenceEnd() }
        }

        // Prefetch the next sentence while this one plays.
        prefetchNext(index + 1)
    }

    /** Starts fetching the next sentence's audio in the background. */
    private fun prefetchNext(index: Int) {
        prefetchJob?.cancel()
        if (index >= sentences.size) return
        prefetchJob = scope.launch {
            val audio = synthesize(sentences[index].text)
            if (audio != null) prefetchedAudio = audio
        }
    }

    private fun onSentenceEnd() {
        val next = currentIndex + 1
        if (next < sentences.size) {
            speakSentence(next)
        } else {
            // End of page — turn and continue.
            currentIndex = 0
            sentences = emptyList()
            currentPageXPointer = null
            scope.launch {
                turnPage()
                // Wait for the page to settle.
                delay(500)
                val reader = getReader() ?: return@launch
                loadPage(reader)
            }
        }
    }

    /**
     * Finds the highlight rectangles for a sentence on the current page.
     * Searches for the first few words to get the start xpointer and the last
     * few words to get the end xpointer, so the full sentence span is covered.
     * Filters results to the current page to avoid matches from elsewhere.
     */
    private suspend fun findHighlightRects(sentence: Sentence): List<Rect> {
        val reader = getReader() ?: return emptyList()
        val words = sentence.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val startKey = words.take(4).joinToString(" ")
        val endKey = words.takeLast(4).joinToString(" ")
        if (startKey.length < 2) return emptyList()
        return reader.read { doc ->
            val currentPage = doc.currentPage
            val startHit = doc.search(startKey, caseInsensitive = true, maxHits = 5)
                .firstOrNull { it.page == currentPage } ?: return@read emptyList()
            if (endKey == startKey || words.size <= 4) {
                doc.rects(startHit.range.start, startHit.range.end)
            } else {
                val endHit = doc.search(endKey, caseInsensitive = true, maxHits = 5)
                    .firstOrNull { it.page == currentPage }
                if (endHit != null) doc.rects(startHit.range.start, endHit.range.end)
                else doc.rects(startHit.range.start, startHit.range.end)
            }
        }
    }

    /** POSTs text to the server and returns the MP3 bytes. */
    private suspend fun synthesize(text: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val prefs = getPrefs()
            if (prefs.ttsSynthUrl.isBlank()) return@withContext null
            val url = URL(prefs.ttsSynthUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 60000
            conn.doOutput = true
            val body = JSONObject().apply {
                put("model", if (selectedVoice.isNotBlank()) selectedVoice else "tts-1")
                put("input", text)
                put("voice", if (selectedVoice.isNotBlank()) selectedVoice else "")
                put("response_format", "mp3")
                put("speed", speed.toDouble())
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    /** Plays MP3 bytes with MediaPlayer. The old temp file is deleted. */
    private suspend fun playAudio(mp3: ByteArray, onDone: () -> Unit) {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        val file = withContext(Dispatchers.IO) {
            mediaFile?.delete()
            val f = File.createTempFile("tts", ".mp3")
            f.writeBytes(mp3)
            f
        }
        mediaFile = file
        val player = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { onDone() }
            prepare()
            start()
        }
        mediaPlayer = player
    }

    /** Splits text into sentences using BreakIterator. */
    private fun splitSentences(text: String): List<Sentence> {
        if (text.isBlank()) return emptyList()
        val it = BreakIterator.getSentenceInstance(Locale.US)
        it.setText(text)
        val result = mutableListOf<Sentence>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val sentenceText = text.substring(start, end).trim()
            if (sentenceText.isNotBlank()) {
                result.add(Sentence(sentenceText, start, end))
            }
            start = end
            end = it.next()
        }
        return result
    }
}