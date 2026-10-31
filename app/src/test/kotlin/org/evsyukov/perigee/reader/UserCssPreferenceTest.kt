package org.evsyukov.perigee.reader

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.ReaderPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The owner's CSS goes through the reader's preferences and comes back as written, with the versions before it. */
@RunWith(RobolectricTestRunner::class)
class UserCssPreferenceTest {
    private val preferences = ReaderPreferences.get(ApplicationProvider.getApplicationContext())

    @Test
    fun ownersCssIsStoredAndReadBack() = runBlocking {
        val css = "p { text-indent: 0 !important; }\n/* «ёлка» */\ntitle { color: #333; }"
        preferences.update { it.copy(userCss = css) }
        assertEquals(css, preferences.prefs.first().userCss)
        // Another setting written later leaves it as it was.
        preferences.update { it.copy(fontSize = it.fontSize + 1) }
        assertEquals(css, preferences.prefs.first().userCss)
    }

    @Test
    fun theLastThreeVersionsAreKept() = runBlocking {
        for (css in listOf("a { }", "b { }", "c { }", "d { }")) preferences.update { it.copy(userCss = css) }
        // The same text saved again is not a new version.
        preferences.update { it.copy(userCss = "d { }") }
        // Emptied, as by the reset: the text before it is among those kept.
        preferences.update { it.copy(userCss = "") }
        assertEquals("", preferences.prefs.first().userCss)
        assertEquals(listOf("d { }", "c { }", "b { }"), preferences.previousUserCss.first())
        // An empty overlay is not a version worth keeping.
        preferences.update { it.copy(userCss = "e { }") }
        assertEquals(listOf("d { }", "c { }", "b { }"), preferences.previousUserCss.first())
    }
}
