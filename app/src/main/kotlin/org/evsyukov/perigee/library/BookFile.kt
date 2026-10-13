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
import org.evsyukov.perigee.data.Fb2Archive
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LocalBook
import org.evsyukov.perigee.engine.Engine

/** The format of an FB2 kept in a zip archive, as file names end. */
internal const val ARCHIVE = "fb2.zip"

/**
 * A picked or downloaded file as the app keeps it: an archive of one FB2 is
 * unpacked beside itself and removed, anything else is left as it is.
 */
internal fun unpacked(file: File): File {
    if (Library.formatOf(file.name) != ARCHIVE) return file
    val book = File(file.parentFile, file.name.dropLast(".zip".length))
    if (!file.inputStream().use { Fb2Archive.unpack(it, book) }) return file
    file.delete()
    return book
}

/**
 * Books the app keeps as archives, from before it unpacked them, become the
 * FB2 inside, with what the reader left in them.
 */
internal suspend fun unpackKept(context: Context, library: Library) = withContext(Dispatchers.IO) {
    for (book in library.keptOfFormat(ARCHIVE)) {
        val archive = File(book.path)
        if (!archive.isFile) continue
        val id = archive.inputStream().use { Fb2Archive.fingerprint(it) } ?: continue
        val target = File(File(context.filesDir, "books").apply { mkdirs() }, "$id.fb2")
        if (!archive.inputStream().use { Fb2Archive.unpack(it, target) }) continue
        library.reidentify(book.id, id, "fb2")
        library.setLocalCopy(id, target)
        archive.delete()
    }
}

/**
 * A book file in the app's storage is read for what it says about itself —
 * cover, authors, series — and joins the library with that. An archive of one
 * FB2 must have been unpacked before. False when the engine cannot read the file.
 */
internal suspend fun describeKeptFile(context: Context, library: Library, covers: Covers, file: File): Boolean =
    withContext(Dispatchers.IO) {
        val format = Library.formatOf(file.name)
        if (format.isEmpty() || format == ARCHIVE) return@withContext false
        val said = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            readBook(context, covers, it, file.name, format)
        } ?: return@withContext false
        library.keep(file, said)
        true
    }

/**
 * Books the app keeps of which only a title is known — opened by hand before
 * such books were read for what they say — get their cover, authors and
 * series. One kept as an archive becomes the FB2 inside on the way, with what
 * the reader left in it.
 */
internal suspend fun describeKept(context: Context, library: Library, covers: Covers) = withContext(Dispatchers.IO) {
    for (book in library.keptUndescribed()) {
        val file = File(book.path)
        if (!file.isFile) continue
        val format = book.format.ifEmpty { Library.formatOf(file.name) }
        if (format.isEmpty()) continue
        val said = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            readBook(context, covers, it, file.name, format)
        } ?: continue
        var kept = file
        if (said.id != book.id) {
            // Known by its archive until now: the book is the file inside.
            val target = File(File(context.filesDir, "books").apply { mkdirs() }, "${said.id}.${said.format}")
            if (!file.inputStream().use { Fb2Archive.unpack(it, target) }) continue
            library.reidentify(book.id, said.id, said.format)
            file.delete()
            kept = target
        }
        library.keep(kept, said)
    }
}

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
    val channel = FileInputStream(file.fileDescriptor).channel
    // A book in an archive is the FB2 inside: known by that file, whatever it is wrapped in.
    val inside = if (format == ARCHIVE) {
        channel.position(0)
        Fb2Archive.fingerprint(FileInputStream(file.fileDescriptor)).also { channel.position(0) }
    } else {
        null
    }
    val id = inside ?: FileHash.partialMd5(channel)
    val said = Engine.describe(context, file, name) ?: return null
    return LocalBook(
        id, if (inside != null) "fb2" else format,
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
    // Not the name unpacking writes under: an archive is unpacked from this file into the target.
    val partial = File(target.path + ".copy")
    try {
        val input = context.contentResolver.openInputStream(Uri.parse(uri)) ?: return@withContext null
        input.use { from -> partial.outputStream().use { from.copyTo(it) } }
        if (book.format == "fb2" && Fb2Archive.isZip(partial)) {
            // The book lies in an archive; what is read, and sent to a server, is the book itself.
            val unpacked = partial.inputStream().use { Fb2Archive.unpack(it, target) }
            partial.delete()
            if (!unpacked) return@withContext null
        } else if (!partial.renameTo(target)) {
            // Only a whole copy takes the name the book is opened by.
            return@withContext null
        }
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
