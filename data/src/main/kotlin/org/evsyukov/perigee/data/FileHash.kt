package org.evsyukov.perigee.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

object FileHash {
    private const val SAMPLE = 1024

    /**
     * Fingerprint of a book file: MD5 over 1 KB samples taken at offsets 0,
     * 1 KB, 4 KB, 16 KB and so on, each four times further, up to 1 GB. This is
     * the scheme KOReader identifies books by, so that a server which knows
     * KOReader can match the file. It reads at most 12 KB whatever the file size.
     */
    fun partialMd5(file: File): String = RandomAccessFile(file, "r").use { partialMd5(it.channel) }

    /** The same over an open file, such as a document that has no path. */
    fun partialMd5(file: FileChannel): String {
        val md5 = MessageDigest.getInstance("MD5")
        val buffer = ByteBuffer.allocate(SAMPLE)
        val size = file.size()
        for (i in -1..10) {
            val offset = if (i < 0) 0L else SAMPLE.toLong() shl (2 * i)
            if (offset >= size) break
            buffer.clear()
            // One read may bring less than asked for; a sample is whatever the file has there.
            var at = offset
            while (buffer.hasRemaining()) {
                val read = file.read(buffer, at)
                if (read <= 0) break
                at += read
            }
            if (buffer.position() == 0) break
            md5.update(buffer.array(), 0, buffer.position())
        }
        return md5.digest().joinToString("") { "%02x".format(it) }
    }
}
