package org.evsyukov.perigee.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTextTest {
    @Test
    fun markupIsTakenOutAndParagraphsAreKeptAsLines() {
        assertEquals(
            "First of two.\nSecond, with a break\nin it.",
            plainText("<div><p>First <b>of</b> two.</p>\n  <p>Second, with a break<br/>in it.</p></div>"),
        )
    }

    @Test
    fun namedCharactersAreWritten() {
        assertEquals("Tom & Jerry say \"1 < 2\", don't they", plainText("Tom &amp; Jerry say &quot;1 &lt; 2&quot;,&nbsp;don&#39;t they"))
        // Written once: what was an ampersand's name stays the text it stood for.
        assertEquals("&lt;", plainText("&amp;lt;"))
    }

    @Test
    fun plainTextIsLeftAsItIs() {
        assertEquals("One line.\nAnother.", plainText("One line.\n\n  Another.  "))
        assertEquals("", plainText("  "))
        assertEquals("2 < 3 and 5 > 4", plainText("2 < 3 and 5 > 4"))
    }
}
