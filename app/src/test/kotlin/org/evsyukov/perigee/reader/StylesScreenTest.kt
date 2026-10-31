package org.evsyukov.perigee.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.evsyukov.perigee.R
import org.evsyukov.perigee.ui.PerigeeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The editor of the owner's CSS: what it lets through to the reader's settings, and what it holds back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StylesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val saved = mutableListOf<String>()
    private var closed = 0

    private fun show(css: String = "", previous: String? = null) = compose.setContent {
        PerigeeTheme(dark = false) {
            StylesScreen(css, previous, onSave = { saved += it }, onClose = { closed++ })
        }
    }

    @Test
    fun anOpenBraceHoldsSavingBack() {
        show()
        compose.onNodeWithTag("styles-text").performTextInput("p {")
        compose.onNodeWithTag("styles-save").assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.styles_unbalanced)).assertIsDisplayed()
    }

    @Test
    fun balancedTextIsSaved() {
        show()
        val css = "p { text-indent: 0 !important; }"
        compose.onNodeWithTag("styles-text").performTextInput(css)
        compose.onNodeWithText(context.getString(R.string.styles_unbalanced)).assertDoesNotExist()
        compose.onNodeWithTag("styles-save").assertIsEnabled().performClick()
        assertEquals(listOf(css), saved)
        assertEquals(1, closed)
    }

    @Test
    fun theEditorStartsFromTheSavedCss() {
        show("title { color: #333; }")
        compose.onNodeWithText("title { color: #333; }").assertIsDisplayed()
        compose.onNodeWithTag("styles-save").assertIsEnabled()
    }

    @Test
    fun resetEmptiesTheOverlayOnlyOnceConfirmed() {
        show("p { color: white !important; }")
        compose.onNodeWithTag("styles-more").performClick()
        compose.onNodeWithText(context.getString(R.string.styles_reset)).performClick()
        compose.onNodeWithText(context.getString(R.string.styles_reset_text)).assertIsDisplayed()
        assertTrue("nothing is saved before the reset is confirmed", saved.isEmpty())
        compose.onNodeWithText(context.getString(R.string.styles_reset_confirm)).performClick()
        assertEquals(listOf(""), saved)
        assertEquals(1, closed)
    }

    @Test
    fun theLastVersionComesBackFromTheMenu() {
        show("title { color: white !important; }", previous = "p { margin: 0; }")
        compose.onNodeWithTag("styles-more").performClick()
        compose.onNodeWithText(context.getString(R.string.styles_previous)).performClick()
        compose.onNodeWithText("p { margin: 0; }").assertIsDisplayed()
        assertTrue("putting it back changes nothing until Save", saved.isEmpty())
        compose.onNodeWithTag("styles-save").performClick()
        assertEquals(listOf("p { margin: 0; }"), saved)
    }

    @Test
    fun cancelLeavesTheSavedCssAlone() {
        show("p { margin: 0 }")
        compose.onNodeWithTag("styles-text").performTextInput("title {")
        compose.onNodeWithTag("styles-cancel").performClick()
        assertTrue(saved.isEmpty())
        assertEquals(1, closed)
    }

    /**
     * The screen as a picture, for a look by eye; written where PERIGEE_SCREENSHOTS points, when it is set.
     * The window is drawn by hand: `captureToImage` of Compose 1.11 waits for a frame Robolectric never commits.
     */
    @Test
    fun screenIsDrawn() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            PerigeeTheme(dark = false) {
                StylesScreen(
                    "p {\n    text-indent: 0 !important;\n    margin-bottom: 0.5em !important;\n}\n\ntitle {\n    text-align: left;\n",
                    onSave = {}, onClose = {},
                )
            }
        }
        compose.waitForIdle()
        val root = view.rootView
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(image))
        // The error line is part of the picture: the text above has an open brace.
        compose.onNodeWithText(context.getString(R.string.styles_unbalanced)).assertIsDisplayed()
        val dir = System.getenv("PERIGEE_SCREENSHOTS") ?: return
        File(dir).mkdirs()
        File(dir, "styles-screen.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
