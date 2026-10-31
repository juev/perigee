package org.evsyukov.perigee.reader

/** Checks on CSS the owner writes, made before it reaches the engine. */
object CssText {
    /**
     * Whether every brace is closed by one after it, and every comment and string is closed.
     * crengine drops what it cannot parse without a word: a stray `}` ends the rules after it, and an
     * open comment or string swallows the font size the app appends after the owner's text. Braces
     * inside comments and strings do not count.
     */
    fun balanced(text: String): Boolean {
        var depth = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '/' && text.startsWith("*", i + 1) -> {
                    val end = text.indexOf("*/", i + 2)
                    if (end < 0) return false
                    i = end + 2
                    continue
                }
                c == '"' || c == '\'' -> {
                    i++
                    while (i < text.length && text[i] != c) {
                        // An escaped character, the quote among them, is part of the string.
                        i += if (text[i] == '\\') 2 else 1
                    }
                    if (i >= text.length) return false
                }
                c == '{' -> depth++
                c == '}' -> if (--depth < 0) return false
            }
            i++
        }
        return depth == 0
    }
}
