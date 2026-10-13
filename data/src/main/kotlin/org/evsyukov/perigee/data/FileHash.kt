package org.evsyukov.perigee.data

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

object FileHash {
    private const val SAMPLE = 1024

    /**
     * Fingerprint of a book file: MD5 over 1 KB samples taken at offsets 0,
     * 1 KB, 4 KB, 16 KB and so on, each four times further, up to 1 GB. This is
     * the scheme KOReader identifies books by, so that a server which knows
     * KOReader can match the file. It reads at most 12 KB whatever the file size.
     */
    fun partialMd5(file: File): String {
        val md5 = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(SAMPLE)
        RandomAccessFile(file, "r").use { input ->
            for (i in -1..10) {
                val offset = if (i < 0) 0L else SAMPLE.toLong() shl (2 * i)
                if (offset >= input.length()) break
                input.seek(offset)
                val read = input.read(buffer)
                if (read <= 0) break
                md5.update(buffer, 0, read)
            }
        }
        return md5.digest().joinToString("") { "%02x".format(it) }
    }
}
