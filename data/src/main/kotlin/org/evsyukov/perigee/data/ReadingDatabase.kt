package org.evsyukov.perigee.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
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

    /** What the book did not say when it was opened stays as it was known. A status that opening changes counts as changed by the reader. */
    @Query(
        "UPDATE books SET path = :path, openedAt = :openedAt, " +
            "title = CASE WHEN :title != '' THEN :title ELSE title END, " +
            "author = CASE WHEN :author != '' THEN :author ELSE author END, " +
            "stateChangedAt = CASE WHEN status IN ('None', 'ToRead') THEN :openedAt ELSE stateChangedAt END, " +
            "statusChangedAt = CASE WHEN status IN ('None', 'ToRead') THEN :openedAt ELSE statusChangedAt END, " +
            "status = CASE WHEN status IN ('None', 'ToRead') THEN 'Reading' ELSE status END " +
            "WHERE id = :id",
    )
    suspend fun touch(id: String, path: String, title: String, author: String, openedAt: Long)

    @Query("UPDATE books SET path = :path WHERE id = :id")
    suspend fun setPath(id: String, path: String)

    @Update
    suspend fun update(book: Book)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: String)

    @Query("SELECT * FROM books WHERE format = :format AND uri IS NULL AND path != ''")
    suspend fun keptOfFormat(format: String): List<Book>

    @Query("SELECT * FROM books WHERE uri IS NULL AND path != '' AND description IS NULL")
    suspend fun keptUndescribed(): List<Book>

    @Query("SELECT path FROM books WHERE path != ''")
    suspend fun copies(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM page_reads WHERE bookId = :bookId)")
    suspend fun hasPageReads(bookId: String): Boolean

    @Query("UPDATE OR IGNORE bookmarks SET bookId = :to WHERE bookId = :from")
    suspend fun moveBookmarks(from: String, to: String)

    @Query("UPDATE quotes SET bookId = :to WHERE bookId = :from")
    suspend fun moveQuotes(from: String, to: String)

    @Query("UPDATE OR IGNORE collection_books SET bookId = :to WHERE bookId = :from")
    suspend fun moveMemberships(from: String, to: String)

    @Query("UPDATE page_reads SET bookId = :to WHERE bookId = :from")
    suspend fun movePageReads(from: String, to: String)

    @Query("UPDATE books SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE books SET status = :status, stateChangedAt = :at, statusChangedAt = :at WHERE id = :id")
    suspend fun setStatus(id: String, status: ReadingStatus, at: Long)

    @Query("UPDATE books SET rating = :rating, stateChangedAt = :at WHERE id = :id")
    suspend fun setRating(id: String, rating: Int?, at: Long)

    /** What the server says, unless the reader has said otherwise since and the server has not heard. */
    @Query("UPDATE books SET status = :status, rating = :rating WHERE id = :id AND stateChangedAt <= statePushedAt")
    suspend fun takeState(id: String, status: ReadingStatus, rating: Int?)

    @Query("UPDATE books SET statePushedAt = :changedAt WHERE id = :id")
    suspend fun statePushed(id: String, changedAt: Long)

    @Query("UPDATE books SET review = :review, reviewChangedAt = :at WHERE id = :id")
    suspend fun setReview(id: String, review: String?, at: Long)

    @Query("UPDATE books SET reviewPushedAt = :changedAt WHERE id = :id")
    suspend fun reviewPushed(id: String, changedAt: Long)

    /** The server's review, unless the reader changed theirs since the server last took it. */
    @Query("UPDATE books SET review = :review WHERE id = :id AND reviewChangedAt <= reviewPushedAt")
    suspend fun takeReview(id: String, review: String?)

    @Query("UPDATE books SET pushedAt = :movedAt WHERE id = :id")
    suspend fun placePushed(id: String, movedAt: Long)

    @Query("UPDATE books SET remoteSeenAt = :at WHERE id = :id AND remoteSeenAt < :at")
    suspend fun remoteSeen(id: String, at: Long)

    @Query("SELECT * FROM books WHERE serverFileId IS NOT NULL")
    suspend fun onServer(): List<Book>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(position: RemotePosition)

    @Query("SELECT * FROM remote_positions WHERE bookId = :bookId")
    fun remotePosition(bookId: String): Flow<RemotePosition?>

    @Query("SELECT * FROM remote_positions WHERE bookId = :bookId")
    suspend fun remotePositionNow(bookId: String): RemotePosition?

    @Query("DELETE FROM remote_positions WHERE bookId = :bookId")
    suspend fun deleteRemotePosition(bookId: String)

    @Query("DELETE FROM remote_positions")
    suspend fun deleteRemotePositions()

    @Insert
    suspend fun insert(read: PageRead)

    @Query("SELECT p.* FROM page_reads p JOIN books b ON b.id = p.bookId WHERE b.serverFileId IS NOT NULL ORDER BY p.id LIMIT :limit")
    suspend fun pageReadsToSend(limit: Int): List<PageRead>

    @Query("DELETE FROM page_reads WHERE id IN (:ids)")
    suspend fun deletePageReads(ids: List<Long>)

    @Query("DELETE FROM page_reads WHERE startedAt < :before")
    suspend fun deletePageReadsBefore(before: Long)

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    fun folders(): Flow<List<Folder>>

    @Query("SELECT * FROM folders WHERE uri = :uri")
    suspend fun folder(uri: String): Folder?

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun folderById(id: Long): Folder?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: Folder): Long

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteFolder(id: Long)

    @Query("SELECT id, uri, fileSize, fileModified, format, description IS NOT NULL AS described FROM books WHERE folderId = :folderId AND uri IS NOT NULL")
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
            "seriesNumber = :seriesNumber, hasCover = :hasCover, description = :description WHERE id = :id",
    )
    suspend fun place(
        id: String, uri: String, folderId: Long, fileSize: Long, fileModified: Long, format: String, title: String,
        author: String, language: String, series: String, seriesNumber: String, hasCover: Boolean, description: String,
    )

    /** A file in the app's storage is this book, whatever was known of it before. */
    @Query(
        "UPDATE books SET path = :path, format = :format, title = :title, author = :author, language = :language, " +
            "series = :series, seriesNumber = :seriesNumber, hasCover = :hasCover, description = :description WHERE id = :id",
    )
    suspend fun keep(
        id: String, path: String, format: String, title: String, author: String, language: String, series: String,
        seriesNumber: String, hasCover: Boolean, description: String,
    )

    @Query("SELECT * FROM catalogs ORDER BY title COLLATE NOCASE")
    fun catalogs(): Flow<List<Catalog>>

    @Query("SELECT * FROM catalogs WHERE url = :url")
    suspend fun catalog(url: String): Catalog?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(catalog: Catalog): Long

    @Query("UPDATE catalogs SET title = :title, user = :user, secret = :secret WHERE url = :url")
    suspend fun updateCatalog(url: String, title: String, user: String, secret: String)

    @Query("DELETE FROM catalogs WHERE id = :id")
    suspend fun deleteCatalog(id: Long)

    @Query("SELECT * FROM accounts WHERE id = ${Account.SINGLE}")
    fun accountFlow(): Flow<Account?>

    @Query("SELECT * FROM accounts WHERE id = ${Account.SINGLE}")
    suspend fun account(): Account?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: Account)

    @Query(
        "UPDATE accounts SET accessSecret = :access, accessExpiresAt = :accessExpiresAt, " +
            "refreshSecret = :refresh, refreshExpiresAt = :refreshExpiresAt WHERE id = ${Account.SINGLE}",
    )
    suspend fun updateTokens(access: String, accessExpiresAt: Long, refresh: String, refreshExpiresAt: Long)

    @Query("UPDATE accounts SET deviceUser = :user, deviceSecret = :secret WHERE id = ${Account.SINGLE}")
    suspend fun updateDevice(user: String, secret: String)

    @Query("UPDATE accounts SET serverVersion = :version WHERE id = ${Account.SINGLE}")
    suspend fun updateServerVersion(version: String)

    @Query("DELETE FROM accounts")
    suspend fun deleteAccount()

    @Query("UPDATE accounts SET syncedAt = :at, unreachableSince = 0 WHERE id = ${Account.SINGLE}")
    suspend fun roundDone(at: Long)

    @Query("UPDATE accounts SET unreachableSince = :at WHERE id = ${Account.SINGLE} AND unreachableSince = 0")
    suspend fun roundUnreachable(at: Long)

    /** Nothing was sent to the next server yet, and nothing was heard from it. */
    @Query(
        "UPDATE books SET serverBookId = NULL, serverFileId = NULL, serverCheckedAt = 0, pushedAt = 0, statePushedAt = 0, reviewPushedAt = 0, " +
            "remoteSeenAt = 0, marksSyncedAt = 0, serverLikeId = NULL, serverLikeName = ''",
    )
    suspend fun forgetServer()

    /** The next server has every quote and bookmark still to hear of, and stars with them. */
    @Query("UPDATE quotes SET sentAt = 0, serverId = NULL, starUnsent = starred")
    suspend fun unsendQuotes()

    @Query("UPDATE bookmarks SET sent = 0, serverId = NULL")
    suspend fun unsendBookmarks()

    @Query("UPDATE books SET marksChangedAt = :at WHERE id = :id")
    suspend fun marksChanged(id: String, at: Long)

    @Query("UPDATE books SET marksChangedAt = :at WHERE id = (SELECT bookId FROM quotes WHERE id = :quoteId)")
    suspend fun marksChangedByQuote(quoteId: Long, at: Long)

    @Query("UPDATE books SET marksChangedAt = :at WHERE id = (SELECT bookId FROM bookmarks WHERE id = :bookmarkId)")
    suspend fun marksChangedByBookmark(bookmarkId: Long, at: Long)

    @Query("UPDATE books SET marksSyncedAt = :changedAt WHERE id = :id")
    suspend fun marksSynced(id: String, changedAt: Long)

    @Query("SELECT * FROM quotes WHERE bookId = :bookId ORDER BY id")
    suspend fun quotesNow(bookId: String): List<Quote>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY id")
    suspend fun bookmarksNow(bookId: String): List<Bookmark>

    /** Marks made by a version that did not stamp them are stamped by when they were made. */
    @Query("UPDATE quotes SET stamp = strftime('%Y-%m-%d %H:%M:%S', createdAt / 1000, 'unixepoch', 'localtime') WHERE stamp = ''")
    suspend fun stampQuotes()

    @Query("UPDATE bookmarks SET stamp = strftime('%Y-%m-%d %H:%M:%S', createdAt / 1000, 'unixepoch', 'localtime') WHERE stamp = ''")
    suspend fun stampBookmarks()

    @Query("UPDATE quotes SET sentAt = :updatedAt WHERE id = :id")
    suspend fun quoteSent(id: Long, updatedAt: Long)

    @Query("UPDATE bookmarks SET sent = 1 WHERE id = :id")
    suspend fun bookmarkSent(id: Long)

    /** A change that came from the server: nothing of it is to be sent back. */
    @Query("UPDATE quotes SET text = :text, note = :note, color = :color, chapter = :chapter, updatedAt = :at, sentAt = :at WHERE id = :id")
    suspend fun takeQuote(id: Long, text: String, note: String?, color: Int, chapter: String?, at: Long)

    @Query("UPDATE quotes SET serverId = :serverId WHERE id = :id")
    suspend fun linkQuote(id: Long, serverId: Long)

    @Query("UPDATE bookmarks SET serverId = :serverId WHERE id = :id")
    suspend fun linkBookmark(id: Long, serverId: Long)

    @Query("UPDATE quotes SET starred = :starred, starUnsent = 1 WHERE id = :id")
    suspend fun starQuote(id: Long, starred: Boolean)

    /** The server has the star as it is; one changed again meanwhile is still to be sent. */
    @Query("UPDATE quotes SET starUnsent = 0 WHERE id = :id AND starred = :starred")
    suspend fun starSent(id: Long, starred: Boolean)

    /** A star given or taken off on the server; one changed here and not sent yet stands. */
    @Query("UPDATE quotes SET starred = :starred WHERE id = :id AND starUnsent = 0")
    suspend fun takeStar(id: Long, starred: Boolean)

    @Query("UPDATE bookmarks SET text = :title WHERE id = :id")
    suspend fun titleBookmark(id: Long, title: String)

    @Query("UPDATE books SET serverBookId = :serverBookId, serverFileId = :serverFileId, serverCheckedAt = :at WHERE id = :id")
    suspend fun setServerFile(id: String, serverBookId: Long?, serverFileId: Long?, at: Long)

    /** A file the server has is that book, whatever looked like it before. */
    @Query("UPDATE books SET serverLikeId = :serverBookId, serverLikeName = :name WHERE id = :id AND serverFileId IS NULL")
    suspend fun setServerLike(id: String, serverBookId: Long?, name: String)

    @Query("UPDATE books SET serverLikeId = NULL, serverLikeName = '' WHERE id = :id")
    suspend fun clearServerLike(id: String)

    @Query("UPDATE books SET serverCheckedAt = 0 WHERE id = :id")
    suspend fun askServerAgain(id: String)

    @Query("SELECT * FROM books WHERE serverFileId IS NULL AND (uri IS NOT NULL OR path != '')")
    suspend fun booksNotOnServer(): List<Book>

    @Query("UPDATE accounts SET uploadLibraryId = :libraryId, uploadFolderId = :folderId WHERE id = ${Account.SINGLE}")
    suspend fun updateUploadPlace(libraryId: Long?, folderId: Long?)

    /** Books the server was never asked about, or not since [before]. */
    @Query("SELECT id FROM books WHERE serverFileId IS NULL AND serverCheckedAt < :before AND (uri IS NOT NULL OR path != '')")
    suspend fun notOnServer(before: Long): List<String>

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

    /** The place a book opens at for the first time, and the same place shown again, are not moves. */
    @Query(
        "UPDATE books SET movedAt = CASE WHEN xpointer IS NULL OR xpointer = :xpointer THEN movedAt ELSE :at END, " +
            "xpointer = :xpointer, progress = :progress WHERE id = :id",
    )
    suspend fun savePosition(id: String, xpointer: String, progress: Float, at: Long)

    @Query("UPDATE books SET jumps = :jumps WHERE id = :id")
    suspend fun saveJumps(id: String, jumps: String)

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

/**
 * EPUBs were read before the engine stopped taking a list a book is on, such
 * as a hundred best novels, for its series. The library reads a file only
 * once, so every EPUB is marked as not read yet, and the next look at the
 * library reads it again, series and all.
 */
internal class EpubsReadAgain : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE books SET description = NULL WHERE format = 'epub'")
    }
}

@Database(
    entities = [
        Book::class, Bookmark::class, Quote::class, Folder::class, BookCollection::class, CollectionBook::class,
        Catalog::class, Account::class, RemotePosition::class, PageRead::class,
    ],
    version = 13,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9, spec = EpubsReadAgain::class),
        AutoMigration(from = 9, to = 10), AutoMigration(from = 10, to = 11), AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
    ],
)
internal abstract class ReadingDatabase : RoomDatabase() {
    abstract fun dao(): ReadingDao

    companion object {
        @Volatile
        private var instance: ReadingDatabase? = null

        /** The one database of the app. */
        fun get(context: Context): ReadingDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ReadingDatabase::class.java, "reading.db").build()
                .also { instance = it }
        }

        /** A database that forgets everything when the process ends, for tests. */
        fun inMemory(context: Context): ReadingDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, ReadingDatabase::class.java).build()
    }
}
