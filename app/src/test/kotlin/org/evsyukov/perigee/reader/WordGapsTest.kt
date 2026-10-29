package org.evsyukov.perigee.reader

import org.evsyukov.perigee.data.Alignment
import org.evsyukov.perigee.data.ReaderPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class WordGapsTest {
    @Test
    fun onlyJustifiedTextWithoutHyphenationGetsTheHint() {
        val warned = Alignment.entries.flatMap { alignment ->
            listOf(true, false).map { hyphenation -> Triple(alignment, hyphenation, widensWordGaps(ReaderPrefs(alignment = alignment, hyphenation = hyphenation))) }
        }.filter { it.third }.map { it.first to it.second }
        assertEquals(listOf(Alignment.Justify to false), warned)
    }

    @Test
    fun theDefaultsGetNoHint() {
        assertEquals(false, widensWordGaps(ReaderPrefs()))
    }
}
