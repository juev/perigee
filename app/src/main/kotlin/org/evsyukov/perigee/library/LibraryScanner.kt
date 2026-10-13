package org.evsyukov.perigee.library

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.Folder
import org.evsyukov.perigee.data.FoundBook
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.engine.Engine

/** What a look through a folder changed. */
data class ScanResult(val found: Int, val lost: Int, val unreadable: Int)

/**
 * Brings the library in line with a folder: books that appeared or changed
 * are read for title, authors, series and cover; books that went away leave.
 * A file is read where it lies, nothing is copied.
 */
class LibraryScanner(
    private val context: Context,
    private val library: Library,
    private val covers: Covers,
    /** Notes about files being read, kept across runs of the app. */
    private val notes: File = File(context.filesDir, "scan"),
) {
    private val reading = File(notes, "reading")
    private val skipped = File(notes, "skipped")

    /**
     * Returns null, changing nothing, when the folder cannot be listed: its
     * books may be back the next time, as with a card taken out.
     */
    suspend fun scan(folder: Folder, source: BookSource): ScanResult? = withContext(Dispatchers.IO) {
        val files = try {
            source.files()
        } catch (e: IOException) {
            return@withContext null
        }
        val avoid = filesToAvoid()
        val known = library.filesIn(folder.id).associateBy { it.uri }
        val present = HashSet<String>()
        var found = 0
        var unreadable = 0
        for (file in files) {
            ensureActive()
            val format = Library.formatOf(file.name)
            if (format.isEmpty()) continue
            val was = known[file.uri]
            if (was != null && was.fileSize == file.size && was.fileModified == file.modified) {
                present += file.uri
                continue
            }
            val book = if (file.key() in avoid) null else read(file, format)
            if (book == null) {
                unreadable++
                continue
            }
            library.found(folder.id, book)
            present += file.uri
            found++
        }
        val gone = known.keys - present
        library.lost(folder.id, gone)
        ScanResult(found, gone.size, unreadable)
    }

    private suspend fun read(file: SourceFile, format: String): FoundBook? {
        notes.mkdirs()
        reading.writeText(file.key())
        try {
            val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(file.uri), "r") ?: return null
            return descriptor.use {
                // Not closed here: the stream shares the descriptor that is closed above.
                val id = FileHash.partialMd5(FileInputStream(it.fileDescriptor).channel)
                val said = Engine.describe(context, it, file.name) ?: return null
                FoundBook(
                    id, file.uri, file.size, file.modified, format,
                    title = said.title.ifBlank { file.name.dropLast(format.length + 1) },
                    authors = said.authors, language = said.language, series = said.series,
                    seriesNumber = said.seriesNumber,
                    hasCover = said.cover?.let { image -> covers.save(id, image) } ?: false,
                )
            }
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        } finally {
            reading.delete()
        }
    }

    /**
     * The engine is native code reading whatever the reader has in a folder.
     * If the app went down while a file was read, the note about it is still
     * there. Once may be chance, as when the app is swiped away in the middle
     * of a look; a file this happened to twice is passed by from then on, so
     * that the app does not fall over it at every start. A changed file gets
     * another try.
     */
    private fun filesToAvoid(): Set<String> {
        if (reading.isFile) {
            skipped.appendText(reading.readText() + "\n")
            reading.delete()
        }
        if (!skipped.isFile) return emptySet()
        return skipped.readLines().groupingBy { it }.eachCount().filterValues { it >= STRIKES }.keys
    }

    private fun SourceFile.key() = "$uri $size $modified"

    private companion object {
        const val STRIKES = 2
    }
}
