package org.evsyukov.perigee.reader

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.ColorScheme
import org.evsyukov.perigee.data.ReaderPreferences
import org.evsyukov.perigee.data.ReaderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppearanceProfileTest {
    @Test
    fun previewLayersOnlyTheReadersEditsOverNewerSavedValues() {
        val initial = ReaderPrefs(fontSize = 22)
        val updated = initial.copy(lineSpacingPercent = 150, highlightSpokenText = true)
        val draft = ReadingSetupDraft(initial).edit(updated) { it.copy(fontSize = 26) }
        assertEquals(initial.copy(fontSize = 26), draft.chosen)
        assertEquals(updated.copy(fontSize = 26), draft.preview(updated))
        val reverted = draft.edit(updated) { it.copy(fontSize = initial.fontSize) }
        assertEquals(updated, reverted.preview(updated))
    }

    @Test
    fun setupAppliesAndSavesOnlyChosenFieldsOverTheLatestSettings() = runBlocking {
        val preferences = ReaderPreferences.get(ApplicationProvider.getApplicationContext())
        val initial = ReaderPrefs(fontSize = 22)
        val current = initial.copy(fontWeight = 650, brightness = 0.4f, readingRuler = true, userCss = "p { color: inherit; }")
        val chosen = initial.copy(fontSize = 30, pageMargins = true, twoPagesInLandscape = false, lineSpacingPercent = 150)
            .withScheme(ColorScheme.Oled)
        preferences.update { current }
        val names = checkNotNull(preferences.appearanceProfiles.first())
        val name = "Guided ${System.nanoTime()}"
        try {
            assertEquals(current, preferences.prefs.first())
            assertTrue("finish applies and saves the guide", preferences.saveReadingSetup(name, initial, chosen))
            val expected = chosen.copy(fontWeight = current.fontWeight, brightness = current.brightness,
                readingRuler = current.readingRuler, userCss = current.userCss)
            assertEquals(expected, preferences.prefs.first())
            assertEquals((names + name).sorted(), preferences.appearanceProfiles.first())
            preferences.update { current }
            preferences.applyAppearanceProfile(name, setOf("Roboto"))
            assertEquals("the ordinary profile contains the committed appearance", expected, preferences.prefs.first())
        } finally {
            if (name in preferences.appearanceProfiles.first().orEmpty()) preferences.deleteAppearanceProfile(name)
        }
    }

    @Test
    fun setupRejectsAnEditToTheSameFieldWithoutSavingOrApplyingAnything() = runBlocking {
        val preferences = ReaderPreferences.get(ApplicationProvider.getApplicationContext())
        val initial = ReaderPrefs(fontSize = 22)
        val chosen = initial.copy(fontSize = 30, pageMargins = true)
        val newer = initial.copy(fontSize = 26, userCss = "p { color: inherit; }")
        preferences.update { newer }
        val names = checkNotNull(preferences.appearanceProfiles.first())
        val name = "Conflict ${System.nanoTime()}"
        assertFalse(preferences.saveReadingSetup(name, initial, chosen))
        assertEquals(newer, preferences.prefs.first())
        assertEquals(names, preferences.appearanceProfiles.first())
    }

    @Test
    fun setupCannotApplyIfAnExistingProfileHasTheSameName() = runBlocking {
        val preferences = ReaderPreferences.get(ApplicationProvider.getApplicationContext())
        val initial = ReaderPrefs(fontSize = 22)
        val name = "Duplicate ${System.nanoTime()}"
        preferences.update { initial }
        preferences.saveAppearanceProfile(name)
        try {
            val chosen = initial.copy(fontSize = 31)
            val failure = runCatching { preferences.saveReadingSetup(" $name ", initial, chosen) }.exceptionOrNull()
            assertTrue("a duplicate name must reject finish", failure is IllegalArgumentException)
            assertEquals(initial, preferences.prefs.first())
            preferences.update { chosen }
            preferences.applyAppearanceProfile(name, setOf("Roboto"))
            assertEquals(initial, preferences.prefs.first())
        } finally {
            preferences.deleteAppearanceProfile(name)
        }
    }

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
