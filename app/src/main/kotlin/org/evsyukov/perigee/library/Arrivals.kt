package org.evsyukov.perigee.library

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.Fb2Archive
import org.evsyukov.perigee.data.FoundBook
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LibraryPreferences
import org.evsyukov.perigee.engine.Engine

/**
 * Where downloaded books go: into the folder the reader chose for them, as
 * ordinary files, or into the app's own storage.
 */
class Arrivals(
    private val context: Context,
    private val library: Library,
    private val covers: Covers,
    private val preferences: LibraryPreferences,
    /** What a downloaded file says about itself. */
    private val describe: Describe = Engine::describe,
    /** The folder downloads are put into, by its tree URI. */
    private val sinkOf: (String) -> BookSink,
) {
    constructor(context: Context) : this(
        context.applicationContext, Library.get(context), Covers.get(context), LibraryPreferences.get(context),
        sinkOf = { uri -> TreeSink(context.applicationContext.contentResolver, Uri.parse(uri)) },
    )

    /** Whether the reader was asked where downloads should go, whatever they answered. */
    suspend fun asked(): Boolean = preferences.prefs.first().downloadsAsked

    /** The reader chose a folder for downloaded books. */
    suspend fun into(uri: String, name: String) {
        library.addFolder(uri, name)
        preferences.update { it.copy(downloadsFolder = uri, downloadsAsked = true) }
    }

    /** The reader would rather keep downloaded books in the app's own storage. */
    suspend fun intoApp() = preferences.update { it.copy(downloadsAsked = true) }

    /**
     * Puts a downloaded file into the library and returns the book's id; null
     * when the file is not a book the engine reads. The file is moved or left
     * for the caller to delete.
     */
    suspend fun keep(file: File, format: String): String? = withContext(Dispatchers.IO) {
        val book = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            readBook(context, covers, it, file.name, format, describe)
        } ?: return@withContext null
        val folderUri = preferences.prefs.first().downloadsFolder
        if (folderUri != null) {
            // A book that lies in a folder already is not put there twice.
            if (library.book(book.id)?.uri != null) return@withContext book.id
            val placed = sinkOf(folderUri).put(file.name, file)
            if (placed != null) {
                val folder = library.addFolder(folderUri, "")
                library.found(
                    folder.id,
                    FoundBook(
                        book.id, placed.uri, placed.size, placed.modified, book.format, book.title, book.authors,
                        book.language, book.series, book.seriesNumber, book.hasCover, book.description,
                    ),
                )
                return@withContext book.id
            }
            // The folder cannot be written to: the book is kept in the app rather than lost.
        }
        val kept = File(File(context.filesDir, "books").apply { mkdirs() }, "${book.id}.${book.format.ifEmpty { "book" }}")
        if (book.format != format) {
            // An archive of one FB2: the app keeps the book, not its wrapping.
            if (!file.inputStream().use { Fb2Archive.unpack(it, kept) }) return@withContext null
        } else if (!file.renameTo(kept)) {
            file.copyTo(kept, overwrite = true)
        }
        library.keep(kept, book)
        book.id
    }
}
