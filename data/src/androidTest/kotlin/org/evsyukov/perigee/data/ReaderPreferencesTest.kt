package org.evsyukov.perigee.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
}
