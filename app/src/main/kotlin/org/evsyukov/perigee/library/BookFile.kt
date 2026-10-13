package org.evsyukov.perigee.library

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LocalBook
import org.evsyukov.perigee.engine.Engine

/**
 * Reads what a book file says about itself and keeps its cover. A book that
 * does not name itself is titled by its file name. Null when the engine
 * cannot read the file.
 *
 * @param name the file name, ending as [format] does
 */
internal suspend fun readBook(
    context: Context, covers: Covers, file: ParcelFileDescriptor, name: String, format: String,
): LocalBook? {
    // Not closed here: the stream shares the descriptor, which the caller closes.
    val id = FileHash.partialMd5(FileInputStream(file.fileDescriptor).channel)
    val said = Engine.describe(context, file, name) ?: return null
    return LocalBook(
        id, format,
        title = said.title.ifBlank { name.dropLast(format.length + 1) },
        authors = said.authors, language = said.language, series = said.series, seriesNumber = said.seriesNumber,
        hasCover = said.cover?.let { image -> covers.save(id, image) } ?: false,
    )
}

/**
 * The file to open a book from. The engine opens files by path, so a book
 * that lies in a folder of the library is copied into the app's storage the
 * first time it is opened. Null when the book cannot be got at.
 */
internal suspend fun localCopy(context: Context, library: Library, book: Book): File? = withContext(Dispatchers.IO) {
    val copy = File(book.path)
    if (book.path.isNotEmpty() && copy.isFile) return@withContext copy
    val uri = book.uri ?: return@withContext null
    val target = File(File(context.filesDir, "books").apply { mkdirs() }, "${book.id}.${book.format.ifEmpty { "book" }}")
    val partial = File(target.path + ".part")
    try {
        val input = context.contentResolver.openInputStream(Uri.parse(uri)) ?: return@withContext null
        input.use { from -> partial.outputStream().use { from.copyTo(it) } }
        // Only a whole copy takes the name the book is opened by.
        if (!partial.renameTo(target)) return@withContext null
    } catch (e: IOException) {
        partial.delete()
        return@withContext null
    } catch (e: SecurityException) {
        partial.delete()
        return@withContext null
    }
    library.setLocalCopy(book.id, target)
    target
}
