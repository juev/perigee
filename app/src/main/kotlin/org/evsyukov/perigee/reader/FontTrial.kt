package org.evsyukov.perigee.reader

import org.evsyukov.perigee.engine.Page

/**
 * A rough trial of which shipped font a reader reads fastest: each font shows one passage of the open
 * book, the reader taps Next when done, and the words per minute are compared. Wallace et al. 2022
 * found one reader's speed differed by about 35 % between their fastest and slowest font, and their
 * stated preference did not predict it. One passage per font and no comprehension check make the
 * result rough, and the screen says so.
 */
internal object FontTrial {
    /** Words a passage aims at. */
    const val WORDS = 120

    private val SENTENCE_END = Regex("""[.!?…]["»”’)]*$""")

    fun wordCount(text: String): Int = text.split(Regex("\\s+")).count { it.isNotEmpty() }

    /**
     * Up to [count] passages of about [words] words from [text], in its order: paragraphs are joined
     * until there are enough words, and a long one is cut at the end of a sentence past the aim, or at
     * the aim when no sentence ends within half as many words again.
     */
    fun passages(text: String, count: Int, words: Int = WORDS): List<String> {
        val paragraphs = text.split('\n').map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var gathered = mutableListOf<String>()
        for (paragraph in paragraphs) {
            var rest = paragraph.split(' ')
            while (rest.isNotEmpty() && out.size < count) {
                val have = gathered.sumOf { wordCount(it) }
                val need = words - have
                if (rest.size < need) {
                    gathered += rest.joinToString(" ")
                    rest = emptyList()
                } else {
                    val cut = cutAt(rest, need.coerceAtLeast(1), words / 2)
                    gathered += rest.take(cut).joinToString(" ")
                    rest = rest.drop(cut)
                    out += gathered.joinToString("\n")
                    gathered = mutableListOf()
                }
            }
            if (out.size >= count) break
        }
        if (out.size < count && gathered.sumOf { wordCount(it) } >= words / 2) out += gathered.joinToString("\n")
        return out
    }

    /** Where to cut [words]: after the first sentence ending from [aim] on, within [slack] more words; else at [aim]. */
    private fun cutAt(words: List<String>, aim: Int, slack: Int): Int {
        if (words.size <= aim) return words.size
        for (i in aim - 1 until minOf(words.size, aim + slack)) {
            if (SENTENCE_END.containsMatchIn(words[i])) return i + 1
        }
        return aim
    }

    fun wordsPerMinute(words: Int, millis: Long): Int = if (millis <= 0) 0 else (words * 60_000L / millis).toInt()
}

/** A font trial under way, or its result. */
data class FontTrialState(
    /** The fonts in the order they are shown. */
    val faces: List<String>,
    val passages: List<String>,
    /** The font shown now; equal to the number of fonts once all are read. */
    val step: Int = 0,
    /** The passage set in the font of [step]; null while it is drawn. */
    val page: Page? = null,
    /** Words per minute per font read so far, in the order read. */
    val results: List<Pair<String, Int>> = emptyList(),
) {
    val done: Boolean get() = step >= faces.size

    /** The font read fastest, once all are read. */
    val fastest: String? get() = if (done) results.maxByOrNull { it.second }?.first else null
}
