package org.evsyukov.perigee.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The lists the library is looked at through. */
enum class Shelf { Reading, All, Favorites, ToRead, Read, Authors, Series, Collections }

enum class BookSort {
    /** Last opened first, then last added. */
    Recent,
    Title,
    Author,

    /** Last added first. */
    Added,
}

/** How the reader left the library screen. */
data class LibraryPrefs(val shelf: Shelf = Shelf.All, val sort: BookSort = BookSort.Recent)

private val Context.libraryStore: DataStore<Preferences> by preferencesDataStore("library")

class LibraryPreferences private constructor(private val store: DataStore<Preferences>) {
    val prefs: Flow<LibraryPrefs> = store.data.map(::read)

    suspend fun update(change: (LibraryPrefs) -> LibraryPrefs) {
        store.edit { stored ->
            val changed = change(read(stored))
            stored[SHELF] = changed.shelf.name
            stored[SORT] = changed.sort.name
        }
    }

    /** A value written by a newer version that this one does not know reads as the default. */
    private fun read(p: Preferences): LibraryPrefs {
        val d = LibraryPrefs()
        return LibraryPrefs(
            shelf = Shelf.entries.firstOrNull { it.name == p[SHELF] } ?: d.shelf,
            sort = BookSort.entries.firstOrNull { it.name == p[SORT] } ?: d.sort,
        )
    }

    companion object {
        private val SHELF = stringPreferencesKey("shelf")
        private val SORT = stringPreferencesKey("sort")

        @Volatile
        private var instance: LibraryPreferences? = null

        fun get(context: Context): LibraryPreferences = instance ?: synchronized(this) {
            instance ?: LibraryPreferences(context.applicationContext.libraryStore).also { instance = it }
        }
    }
}
