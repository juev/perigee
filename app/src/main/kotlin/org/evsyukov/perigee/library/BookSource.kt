package org.evsyukov.perigee.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A file somewhere under a folder of the library. [uri] opens it through the system. */
data class SourceFile(val uri: String, val name: String, val size: Long, val modified: Long)

/** A place books are looked for. */
fun interface BookSource {
    /**
     * Every file under the folder, books or not.
     * @throws IOException when the folder cannot be listed, as when the reader took the permission back
     */
    suspend fun files(): List<SourceFile>
}

/** A folder the reader picked through the system, with all the folders inside it. */
class TreeSource(private val resolver: ContentResolver, private val tree: Uri) : BookSource {

    override suspend fun files(): List<SourceFile> {
        val files = ArrayList<SourceFile>()
        val root = DocumentsContract.getTreeDocumentId(tree)
        val folders = ArrayDeque(listOf(root))
        while (folders.isNotEmpty()) {
            val folder = folders.removeFirst()
            val listed = list(folder) { id, name, mime, size, modified ->
                if (mime == Document.MIME_TYPE_DIR) {
                    folders += id
                } else {
                    files += SourceFile(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, size, modified)
                }
            }
            // A folder inside that cannot be read is passed by; the picked one must be.
            if (!listed && folder == root) throw IOException("cannot list $tree")
        }
        return files
    }

    private fun list(folder: String, each: (id: String, name: String, mime: String, size: Long, modified: Long) -> Unit): Boolean {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder)
        val cursor = try {
            resolver.query(children, COLUMNS, null, null, null)
        } catch (e: RuntimeException) {
            // Providers answer a folder that is gone or closed to us each in their own way.
            null
        } ?: return false
        cursor.use {
            while (it.moveToNext()) {
                each(it.getString(0) ?: continue, it.getString(1).orEmpty(), it.getString(2).orEmpty(), it.getLong(3), it.getLong(4))
            }
        }
        return true
    }

    private companion object {
        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** Removes a document from a folder; false when it is there still. */
typealias DeleteDocument = suspend (uri: String) -> Boolean

/**
 * Removes a document through the system. A document that is not there any
 * more counts as removed.
 *
 * @throws SecurityException when the app may only read the folder
 */
internal fun deleteDocument(resolver: ContentResolver): DeleteDocument = { uri ->
    withContext(Dispatchers.IO) {
        val document = Uri.parse(uri)
        try {
            DocumentsContract.deleteDocument(resolver, document)
        } catch (e: FileNotFoundException) {
            // Said of a document that is gone, and by some providers of one they did not remove.
            !exists(resolver, document)
        }
    }
}

private fun exists(resolver: ContentResolver, document: Uri): Boolean = try {
    resolver.query(document, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
} catch (e: FileNotFoundException) {
    false
} catch (e: IllegalArgumentException) {
    false
}
