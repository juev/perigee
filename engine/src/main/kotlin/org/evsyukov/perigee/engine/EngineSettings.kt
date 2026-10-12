package org.evsyukov.perigee.engine

/** How a book is laid out. Sizes are in device pixels. */
data class EngineSettings(
    val fontFace: String = DEFAULT_FONT_FACE,
    val fontSize: Int = 28,
    val lineSpacingPercent: Int = 100,
    val marginHorizontal: Int = 24,
    val marginVertical: Int = 24,
    val hyphenation: Boolean = true,
    val justify: Boolean = true,
    /** Two pages side by side; meant for landscape tablets. */
    val twoPages: Boolean = false,
) {
    /** Overrides appended to the format stylesheet, in the manner of KOReader style tweaks. */
    internal val extraCss: String
        get() = "body, p, li { text-align: ${if (justify) "justify" else "left"} !important; }"

    companion object {
        const val DEFAULT_FONT_FACE = "Noto Serif"
    }
}
