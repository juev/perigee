package org.evsyukov.perigee.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * An FB2 book kept in a zip archive, as "book.fb2.zip". The book is the FB2:
 * it is known by the fingerprint of that file, and read and sent to a server
 * unpacked. An archive that holds anything but one FB2 is not such a book.
 */
object Fb2Archive {
    /** A book larger than this unpacked is not unpacked: an archive can claim any size. */
    private const val MAX_UNPACKED = 512L * 1024 * 1024

    /** The fingerprint of the FB2 in the archive, see [FileHash]; null when it is not an archive of one FB2. */
    fun fingerprint(archive: InputStream): String? = single(archive) { FileHash.partialMd5(Capped(it)) }

    /**
     * Unpacks the FB2 of the archive into [target]. False, with nothing left
     * at [target], when it is not an archive of one FB2.
     */
    fun unpack(archive: InputStream, target: File): Boolean {
        val partial = File(target.path + ".part")
        try {
            single(archive) { book -> partial.outputStream().use { Capped(book).copyTo(it) } } ?: return false
            // Only a whole file takes the name a book is opened by.
            return partial.renameTo(target)
        } finally {
            partial.delete()
        }
    }

    /** Whether a file begins as a zip archive does. */
    fun isZip(file: File): Boolean = try {
        file.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
    } catch (e: IOException) {
        false
    }

    /** Gives [read] the one FB2 of the archive; null when there is none, or anything else beside it, or it cannot be read. */
    private fun <T> single(archive: InputStream, read: (InputStream) -> T): T? = try {
        val zip = ZipInputStream(archive)
        var entry = zip.nextEntry
        while (entry != null && entry.isDirectory) entry = zip.nextEntry
        if (entry == null || !entry.name.endsWith(".fb2", ignoreCase = true)) {
            null
        } else {
            val result = read(zip)
            var more = zip.nextEntry
            while (more != null && more.isDirectory) more = zip.nextEntry
            if (more == null) result else null
        }
    } catch (e: ZipException) {
        null
    } catch (e: IOException) {
        null
    }

    private class Capped(private val from: InputStream) : InputStream() {
        private var left = MAX_UNPACKED

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = from.read(buffer, offset, length)
            if (read > 0) {
                left -= read
                if (left < 0) throw IOException("the archive unpacks into more than a book is")
            }
            return read
        }
    }
}
