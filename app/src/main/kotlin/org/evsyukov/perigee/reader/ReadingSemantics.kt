package org.evsyukov.perigee.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import org.evsyukov.perigee.engine.Page

/** A projection of the engine's displayed text, not a second rendering or a touch layer. */
@Composable
internal fun ReadingSemantics(page: Page, area: IntSize, offsetX: Int, book: String) {
    val visible = page.readingBlocks.mapNotNull { block ->
        val left = (block.left - offsetX).coerceAtLeast(0)
        val top = block.top.coerceAtLeast(0)
        val right = (block.right - offsetX).coerceAtMost(area.width)
        val bottom = block.bottom.coerceAtMost(area.height)
        if (left >= right || top >= bottom) null else block.copy(left = left, top = top, right = right, bottom = bottom)
    }
    Layout(
        modifier = Modifier.fillMaxSize().semantics { isTraversalGroup = true },
        content = {
            visible.forEachIndexed { index, block ->
                key(book, block.anchor) {
                    Box(Modifier.semantics {
                        text = AnnotatedString(block.text)
                        traversalIndex = index.toFloat()
                        if (block.heading) heading()
                    })
                }
            }
        },
    ) { measurables, constraints ->
        val nodes = measurables.zip(visible).map { (node, block) ->
            node.measure(Constraints.fixed(block.right - block.left, block.bottom - block.top))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            nodes.forEachIndexed { index, node ->
                val block = visible[index]
                // crengine returns physical bitmap coordinates, also in RTL UI locales.
                node.place(block.left, block.top)
            }
        }
    }
}
