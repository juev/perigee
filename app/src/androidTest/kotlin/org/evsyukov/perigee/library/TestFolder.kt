package org.evsyukov.perigee.library

import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/** A plain directory standing in for a folder picked through the system: the same files, opened the same way. */
internal class TestFolder(val dir: File) : BookSource {
    /** Set to make the folder unreachable, as with a card taken out. */
    var gone = false

    init {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    override suspend fun files(): List<SourceFile> {
        if (gone) throw java.io.IOException("the folder is gone")
        return dir.walkTopDown().filter { it.isFile }.map { SourceFile(uriOf(it), it.name, it.length(), it.lastModified()) }.toList()
    }

    fun uriOf(file: File): String = Uri.fromFile(file).toString()

    companion object {
        /** Packs files into a zip archive, under their own names. */
        fun zip(archive: File, vararg files: File): File {
            java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
                for (file in files) {
                    zip.putNextEntry(java.util.zip.ZipEntry(file.name))
                    zip.write(file.readBytes())
                    zip.closeEntry()
                }
            }
            return archive
        }

        fun cover(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }
}
