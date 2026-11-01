package org.evsyukov.perigee.legal

import android.content.ActivityNotFoundException
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.evsyukov.perigee.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    private var primary: Color? = null
    private val opened = mutableListOf<String>()

    private fun string(id: Int, vararg args: Any) = activity.getString(id, *args)

    private fun show(
        browser: UriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        },
    ) = rule.setContent {
        CompositionLocalProvider(LocalUriHandler provides browser) {
            MaterialTheme {
                primary = MaterialTheme.colorScheme.primary
                AboutScreen(onLicenses = { licenses = true }, onClose = { closed = true })
            }
        }
    }

    /** The text [address] is drawn in carries the theme's primary colour and an underline over the whole address. */
    private fun assertDrawnAsLink(address: String) {
        val texts = rule.onNodeWithText(address, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty()
        val text = texts.single { it.text.contains(address) }
        val start = text.text.indexOf(address)
        val span = text.spanStyles.firstOrNull {
            it.start <= start && it.end >= start + address.length && it.item.textDecoration != null
        }
        assertNotNull("no styled span covers $address", span)
        assertEquals(TextDecoration.Underline, span!!.item.textDecoration)
        assertNotNull(primary)
        assertEquals(primary, span.item.color)
    }

    @Test
    fun appIsNamedWithItsVersionAndItsAuthorWhoseSiteOpens() {
        show()
        rule.onNodeWithText("Perigee").assertIsDisplayed()
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
        rule.onNodeWithText(string(R.string.about_version, version)).assertIsDisplayed()
        rule.onNodeWithText("Denis Evsyukov", useUnmergedTree = true).assertIsDisplayed()

        rule.onNodeWithText("https://denis.evsyukov.org").performClick()
        rule.onNodeWithText("https://github.com/juev/perigee").performClick()

        assertEquals(listOf("https://denis.evsyukov.org", "https://github.com/juev/perigee"), opened)
    }

    @Test
    fun bothAddressesAreDrawnAsLinks() {
        show()
        assertDrawnAsLink("https://denis.evsyukov.org")
        assertDrawnAsLink("https://github.com/juev/perigee")
    }

    @Test
    fun aBrowserThatRefusesIsSaidSoAndTheScreenStays() {
        show(
            object : UriHandler {
                override fun openUri(uri: String) {
                    throw ActivityNotFoundException()
                }
            },
        )
        rule.onNodeWithText("https://github.com/juev/perigee").performClick()

        rule.onNodeWithText(string(R.string.about_open_failed)).assertIsDisplayed()
        rule.onNodeWithText("Perigee").assertIsDisplayed()
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
        rule.onNodeWithText(string(R.string.about_version, version)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_licenses)).assertIsDisplayed()
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
