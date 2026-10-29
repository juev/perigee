package org.evsyukov.perigee.engine

/** Text and page colours as 0xRRGGBB. */
data class PageColors(val text: Int, val background: Int) {
    /** Dark pages get highlights painted over them instead of multiplied in. */
    val isDark: Boolean
        get() {
            val r = (background shr 16) and 0xFF
            val g = (background shr 8) and 0xFF
            val b = background and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000 < 128
        }

    companion object {
        val Day = PageColors(text = 0x000000, background = 0xFFFFFF)
        val Night = PageColors(text = 0xC8C8C8, background = 0x1E1E1E)
        val Oled = PageColors(text = 0xFFFFFF, background = 0x000000)
        val Sepia = PageColors(text = 0x644D32, background = 0xF0DEC7)
    }
}

enum class TextAlign(internal val css: String) {
    Justify("justify"), Left("left"), Center("center"), Right("right")
}

/**
 * How a book is laid out; the set of knobs follows ReadEra's reading settings.
 * Sizes are in device pixels.
 */
data class EngineSettings(
    val fontFace: String = DEFAULT_FONT_FACE,
    val fontSize: Int = 28,
    /** CSS weight of regular text: 400 is the font as designed, bold text gets heavier with it. */
    val fontWeight: Int = 400,
    val lineSpacingPercent: Int = 100,
    val marginHorizontal: Int = 24,
    val marginTop: Int = 24,
    val marginBottom: Int = 24,
    val textAlign: TextAlign = TextAlign.Justify,
    val hyphenation: Boolean = true,
    /** Gap between paragraphs in percent of the font size; null leaves it to the book. */
    val paragraphSpacingPercent: Int? = null,
    /** First-line indent on or off; null leaves it to the book. */
    val paragraphIndent: Boolean? = null,
    val colors: PageColors = PageColors.Day,
    /**
     * How dark the strokes of the letters are drawn, 0 as the font has them up to [CONTRASTS].last: the
     * engine's gamma on the glyphs' coverage. Letters keep their widths, so no line moves.
     */
    val contrast: Int = 0,
    /** Footnotes at the bottom of the page that refers to them. */
    val footnotes: Boolean = true,
    /** Two pages side by side; meant for landscape tablets. */
    val twoPages: Boolean = false,
    /** The opening letters of each word in bold, in the font's own bold face; the book is laid out again. */
    val boldWordStarts: Boolean = false,
) {
    /** The engine's gamma for [contrast]. */
    internal val gamma: Double get() = GAMMAS[contrast.coerceIn(CONTRASTS)]

    /** Overrides appended to the format stylesheet, in the manner of KOReader style tweaks. */
    internal val extraCss: String
        get() = buildString {
            append("body, p, li { text-align: ${textAlign.css} !important; }\n")
            paragraphSpacingPercent?.let {
                append("p { margin-top: 0 !important; margin-bottom: ${it / 100.0}em !important; }\n")
            }
            paragraphIndent?.let {
                append("p { text-indent: ${if (it) PARAGRAPH_INDENT else "0"} !important; }\n")
            }
            append(FOOTNOTE_CSS)
            // A rule that matches nothing: it makes the stylesheet, and so the layout kept in the engine's
            // cache, differ with bold word starts on and off.
            if (boldWordStarts) append(".perigee-bold-word-starts { font-style: normal }\n")
        }

    companion object {
        const val DEFAULT_FONT_FACE = "Roboto"

        /** Weights the engine can synthesize when the font file has no such cut. */
        val FONT_WEIGHTS = listOf(100, 200, 300, 400, 425, 450, 475, 500, 525, 550, 600, 650, 700, 800, 900, 950)

        /** Contrast steps; each is a gamma in [GAMMAS]. */
        val CONTRASTS = 0..4

        /** Gamma per contrast step. Above 1 the engine raises the coverage of glyph pixels, so strokes darken and thicken. */
        internal val GAMMAS = listOf(1.0, 1.2, 1.4, 1.6, 1.8)

        private const val PARAGRAPH_INDENT = "1.2em"

        // Which blocks are footnotes: the notes body of FB2 and the EPUB 3
        // structural semantics. A book that marks its notes in neither way keeps
        // them where the author put them.
        private val FOOTNOTES = listOf(
            "body[name=\"notes\"] section", "body[name=\"comments\"] section",
            "*[type~=\"footnote\"]", "*[type~=\"note\"]", "*[type~=\"endnote\"]", "*[type~=\"rearnote\"]",
            "*[role~=\"doc-footnote\"]", "*[role~=\"doc-endnote\"]",
        )

        // Notes are set smaller than the text, as on paper, so that one of a few
        // lines fits under the text that cites it; the line height follows the
        // size. `-cr-only-if` keeps the size off inline matches such as the FB2
        // reference `<a type="note">`, which has its own size in fb2.css. Note
        // paragraphs get no paragraph spacing: the descendant selectors outrank
        // the bare `p` of the spacing setting.
        private val FOOTNOTE_CSS =
            FOOTNOTES.joinToString(", ") +
                " { -cr-hint: footnote-inpage; margin: 0 !important; -cr-only-if: not-inline; font-size: 80% !important; }\n" +
                FOOTNOTES.joinToString(", ") { "$it p" } +
                " { margin-top: 0 !important; margin-bottom: 0 !important; }\n"
    }
}
