package org.evsyukov.perigee.reader

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Tests for TtsController's sentence splitting and locale detection. */
class TtsSplitTest {
    @Test
    fun splitsEnglishBySentence() {
        val text = "Hello world. This is a test. Goodbye!"
        val result = TtsController.splitSentencesForTest(text, Locale.US)
        assertEquals(3, result.size)
        assertEquals("Hello world.", result[0].text)
        assertEquals("This is a test.", result[1].text)
        assertEquals("Goodbye!", result[2].text)
    }

    @Test
    fun splitsRussianBySentence() {
        val text = "Привет мир. Это тест. Пока!"
        val result = TtsController.splitSentencesForTest(text, Locale("ru"))
        assertEquals(3, result.size)
        assertEquals("Привет мир.", result[0].text)
        assertEquals("Это тест.", result[1].text)
        assertEquals("Пока!", result[2].text)
    }

    @Test
    fun blankTextReturnsEmpty() {
        val result = TtsController.splitSentencesForTest("", Locale.US)
        assertEquals(0, result.size)
    }

    @Test
    fun singleSentence() {
        val text = "Just one sentence."
        val result = TtsController.splitSentencesForTest(text, Locale.US)
        assertEquals(1, result.size)
        assertEquals("Just one sentence.", result[0].text)
    }

    @Test
    fun multipleWhitespaceIsCollapsed() {
        val text = "Hello.   World. "
        val result = TtsController.splitSentencesForTest(text, Locale.US)
        assertEquals(2, result.size)
        assertEquals("Hello.", result[0].text.trim())
        assertEquals("World.", result[1].text.trim())
    }

    @Test
    fun localeOf2letter() {
        val loc = TtsController.localeOfForTest("ru")
        assertEquals("ru", loc.language)
    }

    @Test
    fun localeOf5letter() {
        val loc = TtsController.localeOfForTest("en-US")
        assertEquals("en", loc.language)
        assertEquals("US", loc.country)
    }

    @Test
    fun localeOfBlankReturnsUS() {
        val loc = TtsController.localeOfForTest("")
        assertEquals("US", loc.country)
    }
}