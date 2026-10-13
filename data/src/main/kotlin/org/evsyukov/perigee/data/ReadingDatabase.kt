package org.evsyukov.perigee.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ReadingDao {
    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun book(id: String): Book?

    @Query("SELECT * FROM books ORDER BY openedAt DESC LIMIT 1")
    suspend fun lastOpened(): Book?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: Book)

    @Query("UPDATE books SET path = :path, title = :title, author = :author, openedAt = :openedAt WHERE id = :id")
    suspend fun touch(id: String, path: String, title: String, author: String, openedAt: Long)

    @Query("UPDATE books SET xpointer = :xpointer, progress = :progress WHERE id = :id")
    suspend fun savePosition(id: String, xpointer: String, progress: Float)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun bookmarks(bookId: String): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: Bookmark): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("SELECT * FROM quotes WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun quotes(bookId: String): Flow<List<Quote>>

    @Insert
    suspend fun insert(quote: Quote): Long

    @Query("UPDATE quotes SET note = :note, color = :color, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateQuote(id: Long, note: String?, color: Int, updatedAt: Long)

    @Query("DELETE FROM quotes WHERE id = :id")
    suspend fun deleteQuote(id: Long)
}

@Database(entities = [Book::class, Bookmark::class, Quote::class], version = 1)
internal abstract class ReadingDatabase : RoomDatabase() {
    abstract fun dao(): ReadingDao
}
