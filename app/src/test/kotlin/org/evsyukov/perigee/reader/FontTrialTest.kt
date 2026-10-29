package org.evsyukov.perigee.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontTrialTest {
    private fun sentence(n: Int) = "Word $n goes on and on to make it ten."

    /** A paragraph of [sentences] sentences of ten words each. */
    private fun paragraph(sentences: Int, from: Int = 0) = (from until from + sentences).joinToString(" ") { sentence(it) }

    @Test
    fun longParagraphsAreCutAtTheEndOfASentencePastTheAim() {
        val text = paragraph(50)
        val passages = FontTrial.passages(text, count = 3)
        assertEquals(3, passages.size)
        for (passage in passages) {
            assertEquals("whole sentences of ten words, ending past 120", 120, FontTrial.wordCount(passage))
            assertTrue(passage.endsWith("."))
        }
        assertTrue("passages follow one another without overlap", passages[1].startsWith("Word 12 "))
    }

    @Test
    fun shortParagraphsAreJoinedUntilThereAreEnoughWords() {
        val text = (0 until 30).joinToString("\n") { paragraph(3, it * 3) }
        val passages = FontTrial.passages(text, count = 2)
        assertEquals(2, passages.size)
        assertEquals("paragraphs stay apart within a passage", 4, passages[0].split('\n').size)
        assertEquals(120, FontTrial.wordCount(passages[0]))
    }

    @Test
    fun aParagraphWithNoSentenceEndIsCutAtTheAim() {
        val text = List(400) { "word" }.joinToString(" ")
        assertEquals(List(3) { 120 }, FontTrial.passages(text, count = 3).map(FontTrial::wordCount))
    }

    @Test
    fun tooLittleTextGivesFewerPassages() {
        assertEquals(1, FontTrial.passages(paragraph(15), count = 5).size)
        assertEquals("under half a passage is not one", 0, FontTrial.passages(paragraph(5), count = 5).size)
        assertEquals(0, FontTrial.passages("", count = 5).size)
    }

    @Test
    fun wordsPerMinute() {
        assertEquals(240, FontTrial.wordsPerMinute(120, 30_000))
        assertEquals(0, FontTrial.wordsPerMinute(120, 0))
    }

    @Test
    fun theFastestFontIsKnownOnceAllAreRead() {
        val trial = FontTrialState(listOf("A", "B"), listOf("x", "y"), step = 1, results = listOf("A" to 200))
        assertNull(trial.fastest)
        val done = trial.copy(step = 2, results = trial.results + ("B" to 250))
        assertTrue(done.done)
        assertEquals("B", done.fastest)
    }

    @Test
    fun thePassageIsEscapedAndKeepsTheBooksLanguage() {
        val html = ReaderViewModel.trialHtml("a < b & c\nnext", "ru")
        assertTrue(html.contains("""lang="ru""""))
        assertTrue(html.contains("<p>a &lt; b &amp; c</p><p>next</p>"))
    }
}
