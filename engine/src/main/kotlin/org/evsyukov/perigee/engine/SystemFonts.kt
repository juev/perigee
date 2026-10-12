package org.evsyukov.perigee.engine

import android.util.Xml
import java.io.File
import org.xmlpull.v1.XmlPullParser

/**
 * Fonts that come with the device. Android names the families an app may ask
 * for ("serif", "sans-serif", …) and keeps a long tail of unnamed ones, one per
 * script, used only when a glyph is missing. The named ones are offered for
 * reading; the tail would bury them under a hundred "Noto Sans <script>" entries.
 */
internal class SystemFonts(val reading: List<File>, val scripts: List<File>) {
    companion object {
        private val FONT_DIR = File("/system/fonts")
        private val FONT_CONFIG = File("/system/etc/fonts.xml")

        fun find(): SystemFonts {
            val all = FONT_DIR.listFiles { f -> f.extension.lowercase() in setOf("ttf", "otf", "ttc") }
                .orEmpty()
                .sortedBy { it.name }
            val named = runCatching { FONT_CONFIG.inputStream().use(::namedFamilyFiles) }.getOrDefault(emptySet())
            // Without a readable config every font is offered rather than none.
            if (named.isEmpty()) return SystemFonts(all, emptyList())
            val (reading, scripts) = all.partition { it.name in named }
            return SystemFonts(reading, scripts)
        }

        /** File names of the fonts listed under `<family name="…">`. */
        fun namedFamilyFiles(config: java.io.InputStream): Set<String> {
            val parser = Xml.newPullParser().apply { setInput(config, null) }
            val files = mutableSetOf<String>()
            var inNamedFamily = false
            var inFont = false
            val name = StringBuilder()
            while (true) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> return files
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "family" -> inNamedFamily = parser.getAttributeValue(null, "name") != null
                        "font" -> {
                            inFont = inNamedFamily
                            name.clear()
                        }
                        // <axis> and other children of <font> carry no file name.
                        else -> Unit
                    }
                    XmlPullParser.TEXT -> if (inFont) name.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "family" -> inNamedFamily = false
                        "font" -> {
                            if (inFont && name.isNotBlank()) files += name.trim().toString()
                            inFont = false
                        }
                        else -> Unit
                    }
                    else -> Unit
                }
            }
        }
    }
}
