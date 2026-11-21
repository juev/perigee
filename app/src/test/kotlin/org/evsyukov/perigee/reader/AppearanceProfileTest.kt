package org.evsyukov.perigee.reader

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.ColorScheme
import org.evsyukov.perigee.data.ReaderPreferences
import org.evsyukov.perigee.data.ReaderPrefs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppearanceProfileTest {
    @Test
    fun profileEditsChangeOnlyTheNamedSnapshot() = runBlocking {
        val preferences = ReaderPreferences.get(ApplicationProvider.getApplicationContext())
        preferences.update { ReaderPrefs(fontSize = 23) }
        val before = checkNotNull(preferences.appearanceProfiles.first())
        preferences.saveAppearanceProfile("Day")
        preferences.saveAppearanceProfile("Late evening")
        assertEquals((before + listOf("Day", "Late evening")).sorted(), preferences.appearanceProfiles.first())
        preferences.update { it.copy(fontSize = 31) }
        preferences.updateAppearanceProfile("Late evening")
        preferences.renameAppearanceProfile("Late evening", "Reading")
        assertEquals("rename replaces the selected name, not the other profile",
            (before + listOf("Day", "Reading")).sorted(), preferences.appearanceProfiles.first())
        preferences.applyAppearanceProfile("Reading", setOf("Roboto"))
        assertEquals(31, preferences.prefs.first().fontSize)
        preferences.deleteAppearanceProfile("Reading")
        assertEquals((before + "Day").sorted(), preferences.appearanceProfiles.first())
        preferences.applyAppearanceProfile("Day", setOf("Roboto"))
        assertEquals(23, preferences.prefs.first().fontSize)
    }

    @Test
    fun undoRestoresPreviousAppearanceAndRetainsLaterNonAppearanceChanges() = runBlocking {
        val preferences = ReaderPreferences.get(ApplicationProvider.getApplicationContext())
        preferences.update { ReaderPrefs(fontSize = 32, fontFace = "Missing fixture face") }
        preferences.saveAppearanceProfile("Large")
        preferences.update { ReaderPrefs(fontSize = 22, fontFace = "Roboto") }
        assertEquals("Missing fixture face", preferences.applyAppearanceProfile("Large", setOf("Roboto")))
        assertEquals(32, preferences.prefs.first().fontSize)
        assertEquals("Roboto", preferences.prefs.first().fontFace)
        preferences.update { it.copy(highlightSpokenText = true) }
        preferences.undoAppearanceProfile(setOf("Roboto"))
        assertEquals("undo restores the appearance before Apply", ReaderPrefs(fontSize = 22, highlightSpokenText = true),
            preferences.prefs.first())
    }

    @Test
    fun aNamedSnapshotRestoresAppearanceWithoutChangingOtherSettings() = runBlocking {
        val preferences = ReaderPreferences.get(ApplicationProvider.getApplicationContext())
        val saved = ReaderPrefs(fontSize = 31, fullSizeFootnotes = true, twoPagesInLandscape = false)
            .withScheme(ColorScheme.Sepia)
        preferences.update { saved }
        assertEquals(saved, preferences.prefs.first())
        preferences.saveAppearanceProfile("Evening")
        val current = ReaderPrefs(fontSize = 21, brightness = 0.4f, highlightSpokenText = true, userCss = "p { color: inherit; }")
        preferences.update { current }
        assertEquals(current, preferences.prefs.first())
        preferences.applyAppearanceProfile("Evening", setOf(saved.fontFace))
        assertEquals("apply the saved appearance without rewriting unrelated preferences", saved.copy(
            brightness = current.brightness, highlightSpokenText = current.highlightSpokenText, userCss = current.userCss,
        ), preferences.prefs.first())
    }
}
