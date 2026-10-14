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

/** An open book: two pages that meet at the spine. */
val OpenBookIcon: ImageVector = icon("OpenBook") {
    moveTo(2f, 5f)
    lineTo(11f, 6.5f)
    lineTo(11f, 20f)
    lineTo(2f, 18.5f)
    close()
    moveTo(13f, 6.5f)
    lineTo(22f, 5f)
    lineTo(22f, 18.5f)
    lineTo(13f, 20f)
    close()
}

/** Books standing on a shelf: a library, local or on the network. */
val ShelfIcon: ImageVector = icon("Shelf") {
    box(4f, 6f, 3f, 12f)
    box(8f, 4f, 3f, 14f)
    box(12f, 7f, 3f, 11f)
    // The last book leans on the others.
    moveTo(16.2f, 7.4f)
    lineTo(19.1f, 6.6f)
    lineTo(21.9f, 17.2f)
    lineTo(19f, 18f)
    close()
    box(3f, 19f, 19f, 1.5f)
}
