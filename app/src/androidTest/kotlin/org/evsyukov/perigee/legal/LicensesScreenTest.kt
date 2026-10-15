package org.evsyukov.perigee.legal

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.evsyukov.perigee.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The licenses the app shows: requirement R11. */
@RunWith(AndroidJUnit4::class)
class LicensesScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val activity get() = rule.activity
    private var closed = false

    private fun string(id: Int) = activity.getString(id)

    private fun show() = rule.setContent { MaterialTheme { LicensesScreen(onClose = { closed = true }) } }

    private fun waitForText(text: String, substring: Boolean = false) =
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun scrollTo(text: String) =
        rule.onNodeWithTag("legal-text").performScrollToNode(hasText(text, substring = true))

    /** A component is named in the notice too: its row is the text that is its name and nothing else. */
    private fun scrollToRow(name: String) = rule.onNodeWithTag("legal-text").performScrollToNode(hasText(name))

    @Test
    fun noticeSaysWhatTheAppIsUnderAndWhereItsSourceIs() {
        show()
        waitForText("GNU General Public License as published by the Free Software", substring = true)
        scrollTo("https://github.com/juev/perigee")
        scrollTo("WITHOUT ANY WARRANTY")
        // The engine's license is the one whose origin has to be told.
        scrollTo("Where that license comes from")
    }

    @Test
    fun licenseOfTheAppAndOfAComponentAreReadAndLeftByTheWayBack() {
        show()
        waitForText("Perigee, an e-book reader for Android", substring = true)

        scrollToRow(string(R.string.licenses_gpl))
        rule.onNodeWithText(string(R.string.licenses_gpl)).performClick()
        waitForText("GNU GENERAL PUBLIC LICENSE", substring = true)
        scrollTo("Version 3, 29 June 2007")

        rule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        waitForText("Perigee, an e-book reader for Android", substring = true)
        assertFalse("the way back from a license leads to the list", closed)

        scrollToRow("FreeType")
        rule.onNodeWithText("FreeType").performClick()
        waitForText("The FreeType Project LICENSE", substring = true)
        rule.onNodeWithText("FreeType").assertIsDisplayed()

        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForText("Perigee, an e-book reader for Android", substring = true)
        rule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
        assertTrue(closed)
    }

    @Test
    fun everyComponentComesWithItsText() {
        val licenses = componentLicenses(activity)
        assertEquals(
            listOf(
                "antiword", "Apache-2.0", "chmlib", "FreeType", "FriBidi", "HarfBuzz", "libjpeg-turbo", "libpng",
                "libunibreak", "libwebp", "utf8proc", "xxHash", "Zstandard",
            ),
            licenses.map { it.name },
        )
        for (license in licenses) {
            val text = activity.assets.open(license.asset).bufferedReader().use { it.readText() }
            assertTrue("${license.name} has a text", paragraphs(text).size > 3)
        }
    }

    private companion object {
        // An emulator on a shared CI machine can stall for seconds; only a failing wait lasts this long.
        const val TIMEOUT = 30_000L
    }
}
