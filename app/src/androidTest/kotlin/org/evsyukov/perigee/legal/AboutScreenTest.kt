package org.evsyukov.perigee.legal

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.evsyukov.perigee.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The screen about the app: who made it, where its source is, and the way to its licenses. */
@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val activity get() = rule.activity
    private var closed = false
    private var licenses = false
    private val opened = mutableListOf<String>()

    private fun string(id: Int, vararg args: Any) = activity.getString(id, *args)

    private fun show() = rule.setContent {
        val browser = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        CompositionLocalProvider(LocalUriHandler provides browser) {
            MaterialTheme { AboutScreen(onLicenses = { licenses = true }, onClose = { closed = true }) }
        }
    }

    @Test
    fun appIsNamedWithItsVersionAndItsAuthorWhoseSiteOpens() {
        show()
        rule.onNodeWithText("Perigee").assertIsDisplayed()
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
        rule.onNodeWithText(string(R.string.about_version, version)).assertIsDisplayed()

        rule.onNodeWithText("Denis Evsyukov <https://denis.evsyukov.org>").performClick()
        rule.onNodeWithText("https://github.com/juev/perigee").performClick()

        assertEquals(listOf("https://denis.evsyukov.org", "https://github.com/juev/perigee"), opened)
    }

    @Test
    fun licensesAreReachedFromHereAndTheWayBackLeaves() {
        show()
        rule.onNodeWithText(string(R.string.action_licenses)).performClick()
        assertTrue(licenses)

        rule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        assertTrue(closed)
    }
}
