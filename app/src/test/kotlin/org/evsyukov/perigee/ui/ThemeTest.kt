package org.evsyukov.perigee.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    /** The contrast of two colours as WCAG counts it: 1 for the same colour, 21 for black on white. */
    private fun contrast(a: Color, b: Color): Float {
        val (dark, light) = listOf(a.luminance(), b.luminance()).sorted()
        return (light + 0.05f) / (dark + 0.05f)
    }

    /** Every pair of colours the screens put text in, and the surface it is put on. */
    private fun pairs(scheme: ColorScheme) = with(scheme) {
        mapOf(
            "text on the background" to (onBackground to background),
            "text on a surface" to (onSurface to surface),
            "text on the highest surface" to (onSurface to surfaceContainerHighest),
            "secondary text on a surface" to (onSurfaceVariant to surface),
            "secondary text on a card" to (onSurfaceVariant to surfaceContainer),
            "text on a variant surface" to (onSurfaceVariant to surfaceVariant),
            "text on a button" to (onPrimary to primary),
            "a text button on the background" to (primary to background),
            "a text button on a card" to (primary to surfaceContainer),
            "text on a selected chip" to (onSecondaryContainer to secondaryContainer),
            "text on a primary container" to (onPrimaryContainer to primaryContainer),
            "text on a tertiary container" to (onTertiaryContainer to tertiaryContainer),
            "text on an error" to (onError to error),
            "text on a snackbar" to (inverseOnSurface to inverseSurface),
            "an action on a snackbar" to (inversePrimary to inverseSurface),
        )
    }

    private fun readable(scheme: ColorScheme) {
        for ((what, colors) in pairs(scheme)) {
            val ratio = contrast(colors.first, colors.second)
            assertTrue("$what: contrast $ratio is under 4.5", ratio >= 4.5f)
        }
    }

    @Test
    fun textIsReadableByDay() = readable(LightColors)

    @Test
    fun textIsReadableByNight() = readable(DarkColors)
}
