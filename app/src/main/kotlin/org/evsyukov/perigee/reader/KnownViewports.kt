package org.evsyukov.perigee.reader

import android.content.Context

/**
 * The page area last seen with the screen upright and turned on its side,
 * kept across starts of the app. The second copy of a book is laid out for
 * the area of the orientation not shown, so that a rotation shows it at once;
 * the area has to be the very one the screen will give, so it is learnt from
 * the screen rather than worked out.
 */
internal class KnownViewports(private val read: (String) -> String?, private val write: (String, String) -> Unit) {
    /** The screen gave this area, and a book was laid out for it. */
    fun remember(viewport: Viewport) = write(keyOf(viewport.wide), encode(viewport))

    /** Whether this is the area last seen in its orientation. */
    fun isKnown(viewport: Viewport): Boolean = read(keyOf(viewport.wide))?.let(::decode) == viewport

    /** The area of the other orientation, when one was seen on a screen of the same density. */
    fun otherThan(viewport: Viewport): Viewport? =
        read(keyOf(!viewport.wide))?.let(::decode)?.takeIf { it.density == viewport.density && it.densityDpi == viewport.densityDpi }

    companion object {
        fun of(context: Context): KnownViewports {
            val prefs = context.getSharedPreferences("reader-viewports", Context.MODE_PRIVATE)
            return KnownViewports({ prefs.getString(it, null) }, { key, value -> prefs.edit().putString(key, value).apply() })
        }

        private val Viewport.wide: Boolean get() = width > height

        private fun keyOf(wide: Boolean) = if (wide) "wide" else "tall"

        internal fun encode(v: Viewport) = "${v.width},${v.height},${v.density},${v.densityDpi},${v.clearAbove}"

        internal fun decode(text: String): Viewport? {
            val parts = text.split(',')
            if (parts.size != 5) return null
            return Viewport(
                parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null,
                parts[2].toFloatOrNull() ?: return null, parts[3].toIntOrNull() ?: return null, parts[4].toIntOrNull() ?: return null,
            )
        }
    }
}
