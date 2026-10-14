package org.evsyukov.perigee.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How wide lines of a list or of text get on a tablet: wider rows are hard to follow across. */
val READABLE_WIDTH: Dp = 720.dp

/** A screen this wide or wider is a tablet's or a phone's on its side. */
val WIDE_SCREEN: Dp = 600.dp

/**
 * [padding] with as much added at the sides as keeps what is inside within
 * [READABLE_WIDTH] and in the middle. The space at the sides stays part of
 * the list, so a list still scrolls wherever it is touched.
 */
@Composable
fun Centred(padding: PaddingValues, content: @Composable (PaddingValues) -> Unit) {
    BoxWithConstraints {
        val direction = LocalLayoutDirection.current
        val side = ((maxWidth - READABLE_WIDTH) / 2).coerceAtLeast(0.dp)
        content(
            PaddingValues(
                start = padding.calculateStartPadding(direction) + side,
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(direction) + side,
                bottom = padding.calculateBottomPadding(),
            ),
        )
    }
}
