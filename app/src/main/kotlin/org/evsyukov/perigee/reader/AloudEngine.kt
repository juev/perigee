package org.evsyukov.perigee.reader

import android.util.Log
import java.util.Locale

/** The logcat tag of everything that speaks a book aloud. Never the book's text in these lines. */
internal const val TAG = "Perigee.Tts"

/** Why a Read aloud session could not go on. Null while healthy; [SpeechPhase.NoVoice] needs no note. */
internal enum class SpeechNote { Unreachable, Unauthorized, NotFound, Synthesis }

/** What the external speech server needs, as the reader typed it. The endpoint and format are found by probing. */
internal data class ExternalTtsConfig(val baseUrl: String, val apiKey: String)

/** One Read aloud engine: the device's own TTS, or an OpenAI-format server. Main thread only. */
internal interface AloudEngine {
    /** Set when [prepare] or [speak] failed; null while healthy, and null for a device that has no voice. */
    val failure: SpeechNote?

    /** The locale the engine speaks, for telling one spoken sentence from the next. */
    val locale: Locale?

    /** False when the engine cannot speak this language; [failure] says why, or is null for "no voice". */
    suspend fun prepare(language: String): Boolean

    /** False when the engine refused the rate. */
    fun setRate(rate: Float): Boolean

    /**
     * Speaks [text] to the end, offering the UTF-16 (start, end) of each sentence as it begins.
     * False when the engine failed or was [stop]ped. Suspends until the speech is done.
     */
    suspend fun speak(text: String, onRange: (Int, Int) -> Unit): Boolean

    /**
     * Synthesizes ahead the first sentence of [text], the span that follows the one being
     * spoken, so the spans follow one another without silence. A best effort: the engine may
     * say nothing, and [speak] uses what was fetched only when the text matches its own.
     * Default: nothing.
     */
    suspend fun prefetch(text: String) {}

    fun stop()
    fun release()
}
