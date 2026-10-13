package org.evsyukov.perigee.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private fun icon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply { path(fill = SolidColor(Color.Black), pathBuilder = draw) }.build()

private fun androidx.compose.ui.graphics.vector.PathBuilder.box(x: Float, y: Float, width: Float, height: Float) {
    moveTo(x, y)
    horizontalLineToRelative(width)
    verticalLineToRelative(height)
    horizontalLineToRelative(-width)
    close()
}

/** Books as a grid of covers: four covers. */
val GridIcon: ImageVector = icon("Grid") {
    box(4f, 4f, 7f, 7f)
    box(13f, 4f, 7f, 7f)
    box(4f, 13f, 7f, 7f)
    box(13f, 13f, 7f, 7f)
}

/** Books as a list: two rows, each a cover and the lines beside it. */
val RowsIcon: ImageVector = icon("Rows") {
    box(4f, 4f, 5f, 7f)
    box(11f, 5f, 9f, 2f)
    box(11f, 8f, 6f, 2f)
    box(4f, 13f, 5f, 7f)
    box(11f, 14f, 9f, 2f)
    box(11f, 17f, 6f, 2f)
}
