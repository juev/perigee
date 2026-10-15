package org.evsyukov.perigee.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Cover pictures of the library's books, kept small: they are only shown in lists. */
class Covers(private val dir: File) {

    /** Where the cover of a book is kept, whether it has one or not. */
    fun file(bookId: String): File = File(dir, "$bookId.jpg")

    /**
     * A book now known by another fingerprint takes its cover along. A cover
     * already kept under the new fingerprint stays, and the old one is removed.
     */
    fun move(fromId: String, toId: String) {
        val from = file(fromId)
        if (!from.isFile) return
        if (file(toId).isFile || !from.renameTo(file(toId))) from.delete()
    }

    /**
     * Keeps a reduced copy of a cover as the book carries it. Returns false
     * when the bytes are not a picture the system can read.
     */
    fun save(bookId: String, image: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        // Halving while decoding keeps a poster-sized cover from being held in memory whole.
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= HEIGHT) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        val height = minOf(decoded.height, HEIGHT)
        val width = (decoded.width.toLong() * height / decoded.height).toInt().coerceAtLeast(1)
        val small = if (height == decoded.height) decoded else Bitmap.createScaledBitmap(decoded, width, height, true)
        dir.mkdirs()
        file(bookId).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        return true
    }

    companion object {
        /** Tall enough for a grid of covers on a dense screen. */
        const val HEIGHT = 480
        private const val QUALITY = 85

        @Volatile
        private var instance: Covers? = null

        fun get(context: Context): Covers = instance ?: synchronized(this) {
            instance ?: Covers(File(context.applicationContext.filesDir, "covers")).also { instance = it }
        }
    }
}
