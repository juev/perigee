package org.evsyukov.perigee.data

import androidx.room.AutoMigration
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

    @Query("SELECT * FROM books WHERE openedAt > 0 ORDER BY openedAt DESC LIMIT 1")
    suspend fun lastOpened(): Book?

    /** Books that can be opened: in a folder of the library or copied into the app's storage. */
    @Query("SELECT * FROM books WHERE uri IS NOT NULL OR path != ''")
    fun books(): Flow<List<Book>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: Book)

    /** What the book did not say when it was opened stays as it was known. */
    @Query(
        "UPDATE books SET path = :path, openedAt = :openedAt, " +
            "title = CASE WHEN :title != '' THEN :title ELSE title END, " +
            "author = CASE WHEN :author != '' THEN :author ELSE author END, " +
            "status = CASE WHEN status IN ('None', 'ToRead') THEN 'Reading' ELSE status END " +
            "WHERE id = :id",
    )
    suspend fun touch(id: String, path: String, title: String, author: String, openedAt: Long)

    @Query("UPDATE books SET path = :path WHERE id = :id")
    suspend fun setPath(id: String, path: String)

    @Query("UPDATE books SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE books SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: ReadingStatus)

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    fun folders(): Flow<List<Folder>>

    @Query("SELECT * FROM folders WHERE uri = :uri")
    suspend fun folder(uri: String): Folder?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: Folder): Long

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolder(id: Long)

    @Query("SELECT id, uri, fileSize, fileModified FROM books WHERE folderId = :folderId AND uri IS NOT NULL")
    suspend fun filesIn(folderId: Long): List<KnownFile>

    @Query("UPDATE books SET uri = NULL, folderId = NULL WHERE folderId = :folderId")
    suspend fun leaveFolder(folderId: Long)

    @Query("UPDATE books SET uri = NULL, folderId = NULL WHERE folderId = :folderId AND uri = :uri")
    suspend fun lose(folderId: Long, uri: String)

    /** A document holds one book: whatever was there before is there no more. */
    @Query("UPDATE books SET uri = NULL, folderId = NULL WHERE uri = :uri AND id != :id")
    suspend fun displace(uri: String, id: String)

    @Query(
        "UPDATE books SET uri = :uri, folderId = :folderId, fileSize = :fileSize, fileModified = :fileModified, " +
            "format = :format, title = :title, author = :author, language = :language, series = :series, " +
            "seriesNumber = :seriesNumber, hasCover = :hasCover WHERE id = :id",
    )
    suspend fun place(
        id: String, uri: String, folderId: Long, fileSize: Long, fileModified: Long, format: String, title: String,
        author: String, language: String, series: String, seriesNumber: String, hasCover: Boolean,
    )

    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE")
    fun collections(): Flow<List<BookCollection>>

    @Query("SELECT * FROM collections WHERE name = :name")
    suspend fun collection(name: String): BookCollection?

    @Query("SELECT * FROM collection_books")
    fun collectionBooks(): Flow<List<CollectionBook>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(collection: BookCollection): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(member: CollectionBook)

    @Query("UPDATE OR IGNORE collections SET name = :name WHERE id = :id")
    suspend fun renameCollection(id: Long, name: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: Long)

    @Query("DELETE FROM collection_books WHERE collectionId = :collectionId AND bookId = :bookId")
    suspend fun deleteMember(collectionId: Long, bookId: String)

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

@Database(
    entities = [Book::class, Bookmark::class, Quote::class, Folder::class, BookCollection::class, CollectionBook::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
internal abstract class ReadingDatabase : RoomDatabase() {
    abstract fun dao(): ReadingDao
}
