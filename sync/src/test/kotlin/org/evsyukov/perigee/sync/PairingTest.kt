package org.evsyukov.perigee.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which of the server's marks a mark here is taken for. */
class PairingTest {
    private fun pair(mine: List<String>, theirs: List<String>) = Pairing.pair(mine, theirs, { it.substringBefore('#') }, { it.substringBefore('#') })

    @Test
    fun marksThatSayTheSameArePairedInTheOrderTheyWereMade() {
        val pairs = pair(listOf("a#1", "b#1", "a#2"), listOf("a#x", "c#x", "a#y", "b#x"))
        assertEquals(listOf("a#1" to "a#x", "b#1" to "b#x", "a#2" to "a#y"), pairs)
    }

    @Test
    fun aMarkWithNoPartnerIsLeftOutAndAPartnerIsUsedOnce() {
        assertEquals(listOf("a#1" to "a#x"), pair(listOf("a#1", "a#2", "d#1"), listOf("a#x", "b#x")))
        assertEquals(emptyList<Pair<String, String>>(), pair(emptyList(), listOf("a#x")))
    }

    @Test
    fun aQuoteIsKnownByItsWordsWithoutSpacesAtTheEnds() {
        assertEquals("brown fox", Pairing.quoteWords(" brown fox\n", 100))
        assertEquals("bro", Pairing.quoteWords("brown", 3))
    }

    @Test
    fun aBookmarkIsKnownByTheTitleTheServerGaveIt() {
        assertEquals("first words", Pairing.bookmarkTitle(" first words ", "Chapter", 255, 500))
        assertEquals("Chapter", Pairing.bookmarkTitle("  ", " Chapter ", 255, 500))
        assertEquals("Bookmark", Pairing.bookmarkTitle("", null, 255, 500))
        assertEquals("fir", Pairing.bookmarkTitle("first words", null, 3, 500))
    }
}
