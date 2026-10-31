package org.evsyukov.perigee.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The check that keeps a stray brace of the owner's from swallowing the rest of the stylesheet. */
class CssTextTest {
    @Test
    fun emptyTextIsBalanced() = assertTrue(CssText.balanced(""))

    @Test
    fun rulesWithPairedBracesAreBalanced() {
        assertTrue(CssText.balanced("p { text-indent: 0 !important; }\ntitle { font-size: 120%; }"))
        assertTrue(CssText.balanced("@media screen { p { margin: 0 } }"))
    }

    @Test
    fun anExtraClosingBraceIsNot() = assertFalse(CssText.balanced("p { margin: 0 } }"))

    @Test
    fun aClosingBraceBeforeItsOpeningOneIsNot() = assertFalse(CssText.balanced("} p {"))

    @Test
    fun anOpenBraceIsNot() = assertFalse(CssText.balanced("p {"))

    @Test
    fun anUnclosedCommentIsNot() = assertFalse(CssText.balanced("p { margin: 0 } /* the rest"))

    @Test
    fun bracesInsideACommentDoNotCount() {
        assertTrue(CssText.balanced("p { margin: 0 } /* { */"))
        assertFalse(CssText.balanced("p { margin: 0 /* } */"))
    }

    @Test
    fun bracesInsideAStringDoNotCount() {
        assertTrue(CssText.balanced("p::before { content: \"}\" }"))
        assertTrue(CssText.balanced("p::before { content: '{' }"))
        assertTrue(CssText.balanced("p::before { content: \"\\\"}\" }"))
        assertFalse(CssText.balanced("p::before { content: \"{\" "))
    }

    @Test
    fun anUnclosedStringIsNot() = assertFalse(CssText.balanced("p::before { content: \"} }"))
}
