package org.evsyukov.perigee.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A place downloaded books are put into as ordinary files. */
fun interface BookSink {
    /**
     * Puts a copy of [from] there under [name], or under a name close to it
     * when that one is taken. Null when the place cannot be written to.
     */
    suspend fun put(name: String, from: File): SourceFile?
}

/** A folder the reader picked through the system and let the app write to. */
class TreeSink(private val resolver: ContentResolver, private val tree: Uri) : BookSink {

    override suspend fun put(name: String, from: File): SourceFile? = withContext(Dispatchers.IO) {
        try {
            val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            // A type that says nothing: with a telling one some providers add an ending of their own to the name.
            val document = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", name)
                ?: return@withContext null
            try {
                val output = resolver.openOutputStream(document) ?: throw IOException("cannot write $document")
                output.use { to -> from.inputStream().use { it.copyTo(to) } }
            } catch (e: IOException) {
                // Half a book must not stay behind to be found as a book.
                runCatching { DocumentsContract.deleteDocument(resolver, document) }
                return@withContext null
            }
            describe(document) ?: SourceFile(document.toString(), name, from.length(), 0)
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            // Providers refuse a folder that is gone or closed to us each in their own way.
            null
        }
    }

    /** The file as the folder lists it, so that the next look through the folder finds it unchanged. */
    private fun describe(document: Uri): SourceFile? =
        resolver.query(document, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)
            ?.use { if (it.moveToFirst()) SourceFile(document.toString(), it.getString(0).orEmpty(), it.getLong(1), it.getLong(2)) else null }
}

/** What the system calls a picked folder; the last part of its address when it will not say. */
internal fun folderName(context: Context, tree: Uri): String {
    val id = DocumentsContract.getTreeDocumentId(tree)
    val named = runCatching {
        context.contentResolver.query(
            DocumentsContract.buildDocumentUriUsingTree(tree, id),
            arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
    return named?.takeIf { it.isNotBlank() } ?: id.substringAfterLast(':').substringAfterLast('/').ifEmpty { id }
}
