package org.evsyukov.perigee.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Lists and pages keep to a width that can be read across, in the middle of a wide screen. */
@RunWith(AndroidJUnit4::class)
class CentredTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun lineIn(screen: Int) {
        rule.setContent {
            Box(Modifier.requiredWidth(screen.dp).testTag("screen")) {
                Centred(PaddingValues(start = 10.dp, end = 10.dp)) { inner ->
                    Box(Modifier.padding(inner).fillMaxWidth().height(20.dp).testTag("line"))
                }
            }
        }
    }

    /** Where the line starts within the screen, and how wide it is. */
    private fun line(): Pair<Float, Float> {
        val screen = rule.onNodeWithTag("screen").getUnclippedBoundsInRoot()
        val line = rule.onNodeWithTag("line").getUnclippedBoundsInRoot()
        return (line.left - screen.left).value to (line.right - line.left).value
    }

    @Test
    fun onAWideScreenTheLineIsReadableWidthInTheMiddle() {
        lineIn(1000)
        val (start, width) = line()
        // 720 in the middle, and the side padding asked for within it.
        assertEquals(150f, start, 0.5f)
        assertEquals(700f, width, 0.5f)
    }

    @Test
    fun onAPhoneTheLineIsAsWideAsThePaddingLeaves() {
        lineIn(400)
        val (start, width) = line()
        assertEquals(10f, start, 0.5f)
        assertEquals(380f, width, 0.5f)
    }
}
