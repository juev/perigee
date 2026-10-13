package org.evsyukov.perigee.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The reader's books and what they left in them: position, bookmarks, quotes.
 * Books come from folders the reader picked, or are opened one by one.
 */
class Library internal constructor(private val database: ReadingDatabase, private val now: () -> Long) {
    private val dao = database.dao()

    /**
     * Registers the file as a book, or finds the book it already is; either way
     * it becomes the last opened and counts as being read. A blank [title] or
     * [author] leaves what is known; a new book is then titled by its file name.
     */
    suspend fun open(file: File, title: String, author: String): Book {
        val id = withContext(Dispatchers.IO) { FileHash.partialMd5(file) }
        dao.insert(
            Book(
                id, file.path, title.ifBlank { file.nameWithoutExtension }, author, xpointer = null, progress = 0f,
                openedAt = now(), addedAt = now(), format = formatOf(file.name),
            ),
        )
        dao.touch(id, file.path, title, author, now())
        return checkNotNull(dao.book(id))
    }

    suspend fun lastOpened(): Book? = dao.lastOpened()

    suspend fun book(id: String): Book? = dao.book(id)

    /** Every book that can be opened, in no particular order. */
    fun books(): Flow<List<Book>> = dao.books()

    /** Remembers where the app keeps its own copy of the book's file. */
    suspend fun setLocalCopy(bookId: String, file: File) = dao.setPath(bookId, file.path)

    suspend fun setFavorite(bookId: String, favorite: Boolean) = dao.setFavorite(bookId, favorite)

    suspend fun setStatus(bookId: String, status: ReadingStatus) = dao.setStatus(bookId, status)

    fun folders(): Flow<List<Folder>> = dao.folders()

    /** Adding a folder twice keeps one. */
    suspend fun addFolder(uri: String, name: String): Folder {
        dao.insert(Folder(uri = uri, name = name, addedAt = now()))
        return checkNotNull(dao.folder(uri))
    }

    /**
     * Forgets a folder. Its books leave the library, except those the reader
     * opened: the app has its own copy of them. What the reader left in a book
     * is kept either way and comes back with the book.
     */
    suspend fun removeFolder(id: Long) = database.withTransaction {
        dao.leaveFolder(id)
        dao.deleteFolder(id)
    }

    /** The documents of a folder as they were when last looked at. */
    suspend fun filesIn(folderId: Long): List<KnownFile> = dao.filesIn(folderId)

    /** A book file was found in a folder, new or changed. */
    suspend fun found(folderId: Long, book: FoundBook) = database.withTransaction {
        dao.displace(book.uri, book.id)
        dao.insert(
            Book(book.id, path = "", book.title, author = "", xpointer = null, progress = 0f, openedAt = 0, addedAt = now()),
        )
        dao.place(
            book.id, book.uri, folderId, book.fileSize, book.fileModified, book.format, book.title,
            book.authors.joinToString("\n"), book.language, book.series, book.seriesNumber, book.hasCover,
        )
    }

    /** Documents that were in a folder are there no more. */
    suspend fun lost(folderId: Long, uris: Collection<String>) = database.withTransaction {
        for (uri in uris) dao.lose(folderId, uri)
    }

    fun collections(): Flow<List<BookCollection>> = dao.collections()

    /** Which books are in which collections. */
    fun collectionBooks(): Flow<List<CollectionBook>> = dao.collectionBooks()

    /** The collection of that name, made if there is none yet; null for a blank name. */
    suspend fun collection(name: String): BookCollection? {
        val trimmed = name.trim().ifEmpty { return null }
        dao.insert(BookCollection(name = trimmed, createdAt = now()))
        return dao.collection(trimmed)
    }

    /** A blank name, or one another collection has, changes nothing. */
    suspend fun renameCollection(id: Long, name: String) {
        val trimmed = name.trim().ifEmpty { return }
        dao.renameCollection(id, trimmed)
    }

    /** The books stay in the library. */
    suspend fun removeCollection(id: Long) = dao.deleteCollection(id)

    suspend fun addToCollection(collectionId: Long, bookId: String) = dao.insert(CollectionBook(collectionId, bookId))

    suspend fun removeFromCollection(collectionId: Long, bookId: String) = dao.deleteMember(collectionId, bookId)

    suspend fun savePosition(bookId: String, xpointer: String, progress: Float) =
        dao.savePosition(bookId, xpointer, progress)

    fun bookmarks(bookId: String): Flow<List<Bookmark>> = dao.bookmarks(bookId)

    /** Marking a page twice keeps one bookmark. */
    suspend fun addBookmark(bookId: String, xpointer: String, text: String, chapter: String?) {
        dao.insert(Bookmark(bookId = bookId, xpointer = xpointer, text = text, chapter = chapter, createdAt = now()))
    }

    suspend fun removeBookmark(id: Long) = dao.deleteBookmark(id)

    fun quotes(bookId: String): Flow<List<Quote>> = dao.quotes(bookId)

    suspend fun addQuote(
        bookId: String, start: String, end: String, text: String, color: Int, chapter: String?, note: String? = null,
    ): Long = dao.insert(
        Quote(
            bookId = bookId, start = start, end = end, text = text, note = note, color = color,
            chapter = chapter, createdAt = now(), updatedAt = now(),
        ),
    )

    suspend fun updateQuote(id: Long, note: String?, color: Int) =
        dao.updateQuote(id, note?.takeIf { it.isNotBlank() }, color, now())

    suspend fun removeQuote(id: Long) = dao.deleteQuote(id)

    companion object {
        @Volatile
        private var instance: Library? = null

        fun get(context: Context): Library = instance ?: synchronized(this) {
            instance ?: Library(
                Room.databaseBuilder(context.applicationContext, ReadingDatabase::class.java, "reading.db").build(),
                System::currentTimeMillis,
            ).also { instance = it }
        }

        /** File name endings the engine reads, longest first. */
        val FORMATS = listOf(
            "fb2.zip", "epub", "fb2", "fb3", "mobi", "prc", "pdb", "txt", "rtf", "doc", "docx", "odt", "html", "htm", "chm",
        )

        /** The format a file name promises, or "" when it is not a book the engine reads. */
        fun formatOf(fileName: String): String {
            val name = fileName.lowercase()
            return FORMATS.firstOrNull { name.endsWith(".$it") }.orEmpty()
        }

        /** A library that forgets everything when the process ends, for tests. */
        fun inMemory(context: Context, now: () -> Long = System::currentTimeMillis): Library = Library(
            Room.inMemoryDatabaseBuilder(context.applicationContext, ReadingDatabase::class.java).build(),
            now,
        )
    }
}
