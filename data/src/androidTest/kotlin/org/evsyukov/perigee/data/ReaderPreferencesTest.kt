package org.evsyukov.perigee.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What an installation kept from an older version reads as now. */
@RunWith(AndroidJUnit4::class)
class ReaderPreferencesTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.filesDir, "reader-test.preferences_pb").apply { delete() }
    private val fresh = File(context.filesDir, "reader-fresh-test.preferences_pb").apply { delete() }
    private val jobs = mutableListOf<Job>()

    @After
    fun stop() = runBlocking<Unit> {
        jobs.forEach { it.cancelAndJoin() }
        file.delete()
        fresh.delete()
    }

    /** A store over [file] for as long as its job lives; only one may be open on a file at a time. */
    private fun store(file: File): DataStore<Preferences> {
        val job = Job().also { jobs += it }
        return PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
    }

    /** The app's preferences are one per process over a store of their own; a test puts them over its store by the private constructor. */
    private fun preferences(store: DataStore<Preferences>): ReaderPreferences =
        ReaderPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
            .apply { isAccessible = true }
            .newInstance(store)

    @Test
    fun appearanceProfilesAndUndoSurviveStoreRecreation() = runBlocking<Unit> {
        val first = preferences(store(file))
        first.update { ReaderPrefs(fontSize = 32, fullSizeFootnotes = true, userCss = "p { color: red; }") }
        first.saveAppearanceProfile("Крупный текст")
        first.update { ReaderPrefs(fontSize = 21, userCss = "p { color: blue; }") }
        jobs.removeAt(jobs.lastIndex).cancelAndJoin()
        val second = preferences(store(file))
        assertEquals(listOf("Крупный текст"), second.appearanceProfiles.first())
        assertEquals(21, second.prefs.first().fontSize)
        second.applyAppearanceProfile("Крупный текст", setOf(ReaderPrefs().fontFace))
        assertEquals(ReaderPrefs(fontSize = 32, fullSizeFootnotes = true, userCss = "p { color: blue; }"), second.prefs.first())
        jobs.removeAt(jobs.lastIndex).cancelAndJoin()
        val third = preferences(store(file))
        assertTrue(third.canUndoAppearanceProfile.first())
        third.undoAppearanceProfile(setOf(ReaderPrefs().fontFace))
        assertEquals(ReaderPrefs(fontSize = 21, userCss = "p { color: blue; }"), third.prefs.first())
        assertFalse(third.canUndoAppearanceProfile.first())
    }

    @Test
    fun unknownProfileVersionCannotOverwriteTheCatalogOrActivePreferences() = runBlocking<Unit> {
        val stored = store(file)
        val key = stringPreferencesKey("appearance_profiles")
        val future = """{"version":2,"profiles":{"future":{}}}"""
        stored.edit {
            it[key] = future
            it[intPreferencesKey("font_size")] = 27
        }
        val reader = preferences(stored)
        assertNull(reader.appearanceProfiles.first())
        val result = runCatching { reader.saveAppearanceProfile("New") }
        assertTrue(result.isFailure)
        assertEquals(future, stored.data.first()[key])
        assertEquals(27, reader.prefs.first().fontSize)
    }

    @Test
    fun theBooksOwnFontsSwitchedOffByAnOlderVersionAreBackAndTheOtherSettingsKept() = runBlocking<Unit> {
        val installed = preferences(store(fresh)).prefs.first()
        assertEquals("a fresh install reads the defaults", ReaderPrefs(), installed)

        // As an older version left it: the reader had switched the book's own fonts off, the override off as
        // well, and set a line spacing of their own.
        val old = store(file)
        old.edit {
            it[booleanPreferencesKey("embedded_fonts")] = false
            it[booleanPreferencesKey("override_book_font")] = false
            it[intPreferencesKey("line_spacing")] = 150
        }
        val upgraded = preferences(old)
        val kept = installed.copy(overrideBookFont = false, lineSpacingPercent = 150)
        assertEquals("the old switch is not read, the rest is", kept, upgraded.prefs.first())

        // A setting changed since writes everything again; the old value does not come back with it.
        upgraded.update { it.copy(fontSize = it.fontSize + 1) }
        val changed = kept.copy(fontSize = kept.fontSize + 1)
        assertEquals(changed, upgraded.prefs.first())

        // The app started again: a new store reads the same file.
        jobs.removeAt(jobs.lastIndex).cancelAndJoin()
        val restarted = preferences(store(file)).prefs.first()
        assertEquals("after a restart as well", changed, restarted)
    }

    @Test
    fun notesStayCompactUntilTheReaderChoosesTheMainTextSizeAndTheChoiceOutlivesARestart() = runBlocking<Unit> {
        assertFalse("a fresh install has compact notes", preferences(store(fresh)).prefs.first().fullSizeFootnotes)

        // As a version without the choice left it: other settings of the reader's own, and no key for the notes.
        val old = store(file)
        old.edit {
            it[intPreferencesKey("font_size")] = 24
            it[intPreferencesKey("line_spacing")] = 150
            it[stringPreferencesKey("user_css")] = "p { color: #333 }"
        }
        val upgraded = preferences(old)
        val kept = ReaderPrefs(fontSize = 24, lineSpacingPercent = 150, userCss = "p { color: #333 }")
        assertEquals("an older installation keeps compact notes and its own settings", kept, upgraded.prefs.first())

        upgraded.update { it.copy(fullSizeFootnotes = true) }
        assertEquals("the notes change, nothing else does", kept.copy(fullSizeFootnotes = true), upgraded.prefs.first())

        // The app started again: a new store reads the same file.
        jobs.removeAt(jobs.lastIndex).cancelAndJoin()
        val restarted = preferences(store(file))
        assertEquals("main-size notes after a restart", kept.copy(fullSizeFootnotes = true), restarted.prefs.first())

        restarted.update { it.copy(fullSizeFootnotes = false) }
        assertEquals(kept, restarted.prefs.first())
        jobs.removeAt(jobs.lastIndex).cancelAndJoin()
        assertEquals("compact again after another restart", kept, preferences(store(file)).prefs.first())
    }

    @Test
    fun theLargestSizeAndOnePageInLandscapeOutliveARestart() = runBlocking<Unit> {
        val defaults = preferences(store(fresh)).prefs.first()
        assertEquals("a fresh install reads at 18 dp", 18, defaults.fontSize)
        assertEquals("with two pages in landscape", true, defaults.twoPagesInLandscape)

        // A reader at 40 dp, the largest size before issue #154, with settings of their own.
        val old = store(file)
        old.edit {
            it[intPreferencesKey("font_size")] = 40
            it[intPreferencesKey("line_spacing")] = 150
            it[stringPreferencesKey("user_css")] = "p { color: #333 }"
        }
        val upgraded = preferences(old)
        val kept = ReaderPrefs(fontSize = 40, lineSpacingPercent = 150, userCss = "p { color: #333 }")
        assertEquals("the size and the rest are read as they were", kept, upgraded.prefs.first())

        upgraded.update { it.copy(fontSize = ReaderPrefs.FONT_SIZES.last, twoPagesInLandscape = false) }
        jobs.removeAt(jobs.lastIndex).cancelAndJoin()
        val restarted = preferences(store(file))
        assertEquals("48 dp and one page after a restart", kept.copy(fontSize = 48, twoPagesInLandscape = false), restarted.prefs.first())

        restarted.update { it.copy(fontSize = 40, twoPagesInLandscape = true) }
        jobs.removeAt(jobs.lastIndex).cancelAndJoin()
        assertEquals("and back, after another", kept, preferences(store(file)).prefs.first())
    }

    @Test
    fun theReadAloudEngineAndItsExternalServerSettingsOutliveARestart() = runBlocking<Unit> {
        val defaults = preferences(store(fresh)).prefs.first()
        assertEquals("a fresh install reads with the device's own speech", ReadAloudEngine.Device, defaults.readAloudEngine)
        assertEquals("with no server address", "", defaults.externalTtsUrl)
        assertEquals("no key", "", defaults.externalTtsKey)
        assertEquals("no voice picked", "", defaults.externalTtsVoice)
        assertEquals("and no detection cached", "", defaults.externalTtsDetected)

        val detected = """{"base":"https://tts.local","path":"/v1/audio/speech","model":"tts-1","voice":"alloy","kind":"raw","field":"","voices":["alloy","nova"]}"""
        preferences(store(file)).update {
            it.copy(
                readAloudEngine = ReadAloudEngine.External,
                externalTtsUrl = "http://tts.local:8000",
                externalTtsKey = "sk-test",
                externalTtsVoice = "nova",
                externalTtsDetected = detected,
            )
        }
        jobs.removeAt(jobs.lastIndex).cancelAndJoin()
        val restarted = preferences(store(file)).prefs.first()
        assertEquals(ReadAloudEngine.External, restarted.readAloudEngine)
        assertEquals("http://tts.local:8000", restarted.externalTtsUrl)
        assertEquals("sk-test", restarted.externalTtsKey)
        assertEquals("nova", restarted.externalTtsVoice)
        assertEquals(detected, restarted.externalTtsDetected)
    }
}
