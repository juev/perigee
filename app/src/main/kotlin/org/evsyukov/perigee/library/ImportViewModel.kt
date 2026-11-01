package org.evsyukov.perigee.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Book files that came to the reader's screen: picked there, or shared with
 * the app and taken in by [ShareActivity]. One file that became a book is
 * opened; anything else — several files, or one that did not become a book —
 * is listed with what became of each, until the reader puts the list away.
 *
 * A picked file is copied by this model, which outlives the activity being
 * made anew while the right to read the file, given to the activity, lasts.
 */
class ImportViewModel(application: Application) : AndroidViewModel(application) {
    private val importer = BookImport(application)
    private val turn = Mutex()
    private val _results = MutableStateFlow<List<Imported>>(emptyList())
    private val opened = Channel<String>(Channel.CONFLATED)

    /** What became of the files of the deliveries not opened, oldest first; empty when there is nothing to show. */
    val results: StateFlow<List<Imported>> = _results.asStateFlow()

    /** The file of a book to open, once each. */
    val toOpen: Flow<String> = opened.receiveAsFlow()

    /** Takes in the files picked at once, one after another. */
    fun take(uris: List<Uri>) {
        viewModelScope.launch {
            turn.withLock { arrived(takeIn(importer, uris)) }
        }
    }

    /** What became of the files of one delivery, taken in here or elsewhere, is shown. */
    fun arrived(taken: List<Imported>) {
        val one = taken.singleOrNull()
        if (one is Imported.Kept) opened.trySend(one.path) else _results.update { it + taken }
    }

    /** The reader put the list away. */
    fun dismiss() {
        _results.value = emptyList()
    }
}

/** Takes in the files of one delivery; none at all is told as a file that did not come. */
internal suspend fun takeIn(importer: BookImport, uris: List<Uri>): List<Imported> =
    if (uris.isEmpty()) listOf(Imported.Failed("", ImportFailure.NoFile)) else uris.map { importer.take(it) }
