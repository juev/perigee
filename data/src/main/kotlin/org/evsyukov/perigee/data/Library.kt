package org.evsyukov.perigee.data

import android.content.Context
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Books the reader has opened and what they left in them: position, bookmarks, quotes. */
class Library internal constructor(private val dao: ReadingDao, private val now: () -> Long) {

    /** Registers the file as a book, or finds the book it already is; either way it becomes the last opened. */
    suspend fun open(file: File, title: String, author: String): Book {
        val id = withContext(Dispatchers.IO) { FileHash.partialMd5(file) }
        dao.insert(Book(id, file.path, title, author, xpointer = null, progress = 0f, openedAt = now()))
        dao.touch(id, file.path, title, author, now())
        return checkNotNull(dao.book(id))
    }

    suspend fun lastOpened(): Book? = dao.lastOpened()

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
                Room.databaseBuilder(context.applicationContext, ReadingDatabase::class.java, "reading.db").build().dao(),
                System::currentTimeMillis,
            ).also { instance = it }
        }

        /** A library that forgets everything when the process ends, for tests. */
        fun inMemory(context: Context, now: () -> Long = System::currentTimeMillis): Library = Library(
            Room.inMemoryDatabaseBuilder(context.applicationContext, ReadingDatabase::class.java).build().dao(),
            now,
        )
    }
}
