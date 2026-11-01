package org.evsyukov.perigee.library

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.Fb2Archive
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.engine.Engine

/** Why a file handed to the app did not become a book of the library. */
enum class ImportFailure {
    /** No file came, or the app it came from could not give it. */
    NoFile,

    /** The app it came from does not let this one read it, or no longer does. */
    Denied,

    /** Its name does not end as a book the engine reads does. */
    NotABook,

    /** The engine cannot read it: a damaged file, or not the book its name says. */
    Unreadable,

    /** It could not be copied whole into the app's storage. */
    NotCopied,
}

/** What became of one file handed to the app. */
sealed interface Imported {
    /** It is a book of the library, read from [path]. */
    data class Kept(val id: String, val title: String, val path: String) : Imported

    /** It is not, for [failure]; [name] is the file's name as its app gave it, empty when it gave none. */
    data class Failed(val name: String, val failure: ImportFailure) : Imported
}

/**
 * A book file handed to the app — picked through "Open a book" or shared by
 * another app — is copied into the app's storage and joins the library. The
 * file it came from is only read.
 *
 * The copy is kept under the book's fingerprint, as a book copied from a
 * folder is: two books that came under one name are two files, and a book
 * the library has already is read from the copy it has, with everything the
 * reader left in it. An archive of one FB2 is kept as the FB2 inside. Until
 * it is whole and read, the copy lies under a name of its own, and it is
 * removed when anything goes wrong, with a cover read from it for a book
 * the library does not have; one left by an app that died meanwhile goes
 * with the copies no book is read from (`removeStrayCopies`).
 *
 * Copying waits for an app that is slow to give its file only as long as the
 * import is not called off. A provider that hangs while it is asked for the
 * file's name or descriptor holds the import until it answers.
 */
class BookImport(
    private val context: Context,
    private val library: Library,
    private val covers: Covers,
    private val describe: Describe = Engine::describe,
    private val dir: File = File(context.filesDir, "books"),
    /** The file's name as the app it comes from gives it; null when it gives none. */
    private val nameOf: (Uri) -> String? = { uri -> displayName(context.contentResolver, uri) },
    /** What the file holds; null when the app it comes from gives nothing. */
    private val open: (Uri) -> InputStream? = { uri -> context.contentResolver.openAssetFileDescriptor(uri, "r")?.let(::Whole) },
) {
    constructor(context: Context) : this(context.applicationContext, Library.get(context), Covers.get(context))

    /** Takes in the file of a content address; any other address is not read. */
    suspend fun take(uri: Uri): Imported = withContext(Dispatchers.IO) {
        val addressed = fileName(uri.lastPathSegment.orEmpty())
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext Imported.Failed(addressed, ImportFailure.NoFile)
        val name = try {
            nameOf(uri)?.let(::fileName)?.ifEmpty { null } ?: addressed
        } catch (e: SecurityException) {
            return@withContext Imported.Failed(addressed, ImportFailure.Denied)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // A provider that cannot be asked may still give the file; it is known by its address meanwhile.
            addressed
        }
        val format = Library.formatOf(name)
        if (format.isEmpty()) return@withContext Imported.Failed(name, ImportFailure.NotABook)
        val partial = File(dir, ".import-${UUID.randomUUID()}.$format")
        try {
            val input = try {
                open(uri)
            } catch (e: FileNotFoundException) {
                null
            } ?: return@withContext Imported.Failed(name, ImportFailure.NoFile)
            dir.mkdirs()
            input.use { from -> partial.outputStream().use { copy(from, it) } }
            // Imports in two screens at once put their books in place one after the other.
            placing.withLock { keep(partial, name, format) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Imported.Failed(name, ImportFailure.Denied)
        } catch (e: IOException) {
            Imported.Failed(name, ImportFailure.NotCopied)
        } catch (e: RuntimeException) {
            // A provider failing in a way of its own, as an app that went away while giving the file.
            Imported.Failed(name, ImportFailure.NotCopied)
        } finally {
            partial.delete()
        }
    }

    /** The whole copy becomes a book of the library, or is left for the caller to remove. */
    private suspend fun keep(partial: File, name: String, format: String): Imported {
        val book = ParcelFileDescriptor.open(partial, ParcelFileDescriptor.MODE_READ_ONLY).use {
            readBook(context, covers, it, name, format, describe)
        } ?: return Imported.Failed(name, ImportFailure.Unreadable)
        var made: File? = null
        try {
            // A book the library has a copy of already is read from that copy: it is the same book, and it may be open now.
            val had = library.book(book.id)?.path?.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
            val target = had ?: File(dir, "${book.id}.${book.format}")
            if (!target.isFile) {
                made = target
                // An archive of one FB2: the app keeps the book, not its wrapping.
                val placed = if (book.format != format) partial.inputStream().use { Fb2Archive.unpack(it, target) } else partial.renameTo(target)
                if (!placed) {
                    forgetCover(book.id)
                    return Imported.Failed(name, ImportFailure.NotCopied)
                }
            }
            // Once in place, the copy is told of whether or not the import is called off meanwhile.
            val kept = withContext(NonCancellable) { library.keep(target, book) }
            return Imported.Kept(kept.id, kept.title, kept.path)
        } catch (e: Throwable) {
            // No book is read from a copy the library was not told of, nor shown with a cover it did not keep.
            made?.delete()
            withContext(NonCancellable) { forgetCover(book.id) }
            throw e
        }
    }

    /** The cover just read goes when its book did not join the library; the cover of a book there stays. */
    private suspend fun forgetCover(bookId: String) {
        if (library.book(bookId) == null) covers.file(bookId).delete()
    }

    /** Copies to the end, stopping when the import is called off, also while the app giving the file is slow to. */
    private suspend fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (from is Whole && !from.ready(WAIT_MILLIS)) continue
            val read = from.read(buffer)
            if (read < 0) break
            to.write(buffer, 0, read)
        }
    }

    /**
     * A file as an app gives it. A file sent through a pipe ends early when its
     * app fails on the way; the end is then an error, not a shorter file.
     */
    private class Whole(private val file: AssetFileDescriptor) : FilterInputStream(file.createInputStream()) {
        /**
         * Whether reading now would not wait: there is something to read, the
         * end, or an error. A file on a disk always is; a pipe is not while the
         * app at its other end gives nothing.
         */
        fun ready(millis: Int): Boolean = try {
            val wanted = StructPollfd().apply {
                fd = file.fileDescriptor
                events = OsConstants.POLLIN.toShort()
            }
            Os.poll(arrayOf(wanted), millis) > 0
        } catch (e: ErrnoException) {
            // Interrupted by a signal: looked at again.
            false
        }

        override fun read(): Int = super.read().also { if (it < 0) file.parcelFileDescriptor.checkError() }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it < 0) file.parcelFileDescriptor.checkError() }
    }

    private companion object {
        /** How long a read waits for a slow app before it looks whether the import was called off. */
        const val WAIT_MILLIS = 200

        val placing = Mutex()
    }
}

/** The name the app a file comes from gives it; null when it gives none. */
private fun displayName(resolver: ContentResolver, uri: Uri): String? =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }

/** A name another app gives a file, as one to show and to take its format from: no folders, no control characters. */
internal fun fileName(given: String): String =
    given.substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }.trim()
