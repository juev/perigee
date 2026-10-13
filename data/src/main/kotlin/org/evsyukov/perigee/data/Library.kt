package org.evsyukov.perigee.data

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The reader's books and what they left in them: position, bookmarks, quotes.
 * Books come from folders the reader picked, or are opened one by one.
 */
class Library internal constructor(internal val database: ReadingDatabase, private val now: () -> Long) {
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

    suspend fun setStatus(bookId: String, status: ReadingStatus) = dao.setStatus(bookId, status, now())

    /** One to five stars; null takes the rating off. */
    suspend fun setRating(bookId: String, rating: Int?) = dao.setRating(bookId, rating?.coerceIn(1, 5), now())

    /**
     * A file in the app's storage, as one downloaded from a catalog, joins the
     * library without being opened. A book that was there already keeps what
     * the reader left in it and is read from this file from now on.
     */
    suspend fun keep(file: File, book: LocalBook): Book = database.withTransaction {
        dao.insert(Book(book.id, file.path, book.title, author = "", xpointer = null, progress = 0f, openedAt = 0, addedAt = now()))
        dao.keep(
            book.id, file.path, book.format, book.title, book.authors.joinToString("\n"), book.language, book.series,
            book.seriesNumber, book.hasCover, book.description,
        )
        checkNotNull(dao.book(book.id))
    }

    /** The server has this file, as [serverFileId] of its book [serverBookId]. */
    suspend fun onServer(bookId: String, serverBookId: Long, serverFileId: Long) = database.withTransaction {
        dao.setServerFile(bookId, serverBookId, serverFileId, now())
        dao.clearServerLike(bookId)
    }

    /** Books of the library the server is not known to have, as they are. */
    suspend fun booksNotOnServer(): List<Book> = dao.booksNotOnServer()

    /**
     * The server has a book that goes by the same title and authors, [named]
     * so there, while its file is another; null for [serverBookId] when it has
     * none. Says nothing of a book whose own file the server has.
     */
    suspend fun likeOnServer(bookId: String, serverBookId: Long?, named: String = "") =
        dao.setServerLike(bookId, serverBookId, if (serverBookId == null) "" else named)

    /** The server is to be asked about the book again, as after its file was sent there. */
    suspend fun askServerAgain(bookId: String) = dao.askServerAgain(bookId)

    /** The server was asked about these files and has none of them. */
    suspend fun notOnServer(bookIds: Collection<String>) = database.withTransaction {
        for (id in bookIds) dao.setServerFile(id, null, null, now())
    }

    /**
     * Books of the library the server is not known to have. With [all] every
     * such book, to ask about them again; without, only those never asked about.
     */
    suspend fun unmatched(all: Boolean): List<String> = dao.notOnServer(if (all) Long.MAX_VALUE else 1)

    /** Books kept in the app's own storage, in no folder, whose file is of this format. */
    suspend fun keptOfFormat(format: String): List<Book> = dao.keptOfFormat(format)

    /**
     * Books kept in the app's own storage, in no folder, whose file was never
     * read for what the book says of itself: one opened by hand before such
     * books were read so, or recorded before books were read for what they are about.
     */
    suspend fun keptUndescribed(): List<Book> = dao.keptUndescribed()

    /**
     * A book turns out to be known by another fingerprint, as one in an archive
     * that is now known by the file inside: what the reader left in it — the
     * place, status, stars, bookmarks, quotes, collections, pages read — moves
     * to the book of that fingerprint, and the old one is gone. A book that is
     * there under the new fingerprint and was read keeps its own place.
     * Nothing of a server is carried over: it is asked about the book anew.
     *
     * @return where the app kept its copy of the old book's file, for the caller to remove; empty when it kept none
     */
    suspend fun reidentify(oldId: String, newId: String, format: String): String = database.withTransaction {
        val old = dao.book(oldId) ?: return@withTransaction ""
        if (oldId == newId) return@withTransaction ""
        val there = dao.book(newId)
        val carried = old.copy(
            id = newId, format = format, path = there?.path.orEmpty(), uri = there?.uri ?: old.uri, folderId = there?.folderId ?: old.folderId,
            serverBookId = null, serverFileId = null, serverCheckedAt = 0, pushedAt = 0, statePushedAt = 0, remoteSeenAt = 0,
            marksSyncedAt = 0, marksChangedAt = now(), serverLikeId = null, serverLikeName = "",
        )
        when {
            there == null -> {
                // The document is the new book's from here on; the old row must not hold it too.
                dao.update(old.copy(uri = null, folderId = null))
                dao.insert(carried)
            }
            there.openedAt == 0L -> dao.update(carried.copy(serverBookId = there.serverBookId, serverFileId = there.serverFileId, serverCheckedAt = there.serverCheckedAt))
            else -> Unit
        }
        dao.moveBookmarks(oldId, newId)
        dao.moveQuotes(oldId, newId)
        dao.moveMemberships(oldId, newId)
        dao.movePageReads(oldId, newId)
        dao.marksChanged(newId, now())
        dao.deleteBook(oldId)
        old.path
    }

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
            book.authors.joinToString("\n"), book.language, book.series, book.seriesNumber, book.hasCover, book.description,
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

    /**
     * Remembers where the reader is. A place other than the one before is a
     * move, to be told to the server; where a book opens the first time is not.
     */
    suspend fun savePosition(bookId: String, xpointer: String, progress: Float) =
        dao.savePosition(bookId, xpointer, progress, now())

    /** The books the server has. */
    suspend fun booksOnServer(): List<Book> = dao.onServer()

    /** The server took the place the reader moved to at [movedAt]. */
    suspend fun placeSent(bookId: String, movedAt: Long) = dao.placePushed(bookId, movedAt)

    /** The server took the status and rating as the reader changed them at [changedAt]. */
    suspend fun stateSent(bookId: String, changedAt: Long) = dao.statePushed(bookId, changedAt)

    /**
     * Takes the status and rating the server has for a book. What the reader
     * changed here and the server has not heard of yet stays as it is.
     */
    suspend fun takeState(bookId: String, status: ReadingStatus, rating: Int?) = dao.takeState(bookId, status, rating)

    /** The reader got further in a book somewhere else; they are to be asked whether to go there. */
    suspend fun offer(position: RemotePosition) = database.withTransaction {
        dao.insert(position)
        dao.remoteSeen(position.bookId, position.at)
    }

    /** A place from elsewhere, as of the server's time [at], that is where the reader is here already. */
    suspend fun remoteSeen(bookId: String, at: Long) = dao.remoteSeen(bookId, at)

    /** The place from elsewhere the reader has not decided about; null when there is none. */
    fun offerOf(bookId: String): Flow<RemotePosition?> = dao.remotePosition(bookId)

    suspend fun pendingOffer(bookId: String): RemotePosition? = dao.remotePositionNow(bookId)

    /** The reader decided, one way or the other; the same place is not offered again. */
    suspend fun settleOffer(bookId: String) = dao.deleteRemotePosition(bookId)

    /** The reader spent [seconds] on a page, from [startedAt] in seconds since 1970. */
    suspend fun addPageRead(bookId: String, page: Int, totalPages: Int, startedAt: Long, seconds: Int) =
        dao.insert(PageRead(bookId = bookId, page = page, totalPages = totalPages, startedAt = startedAt, seconds = seconds))

    /** Pages read in books the server has, oldest first, that it was not told of yet. */
    suspend fun pageReadsToSend(limit: Int): List<PageRead> = dao.pageReadsToSend(limit)

    suspend fun pageReadsSent(reads: Collection<PageRead>) = dao.deletePageReads(reads.map { it.id })

    /** Pages read in books the server never came to have are not kept for ever. */
    suspend fun forgetPageReadsBefore(startedAt: Long) = dao.deletePageReadsBefore(startedAt)

    fun bookmarks(bookId: String): Flow<List<Bookmark>> = dao.bookmarks(bookId)

    /** Marking a page twice keeps one bookmark. */
    suspend fun addBookmark(bookId: String, xpointer: String, text: String, chapter: String?) = database.withTransaction {
        val at = now()
        dao.insert(Bookmark(bookId = bookId, xpointer = xpointer, text = text, chapter = chapter, createdAt = at, stamp = Stamps.of(at)))
        dao.marksChanged(bookId, at)
    }

    suspend fun removeBookmark(id: Long) = database.withTransaction {
        dao.marksChangedByBookmark(id, now())
        dao.deleteBookmark(id)
    }

    fun quotes(bookId: String): Flow<List<Quote>> = dao.quotes(bookId)

    suspend fun addQuote(
        bookId: String, start: String, end: String, text: String, color: Int, chapter: String?, note: String? = null,
    ): Long = database.withTransaction {
        val at = now()
        dao.marksChanged(bookId, at)
        dao.insert(
            Quote(
                bookId = bookId, start = start, end = end, text = text, note = note, color = color,
                chapter = chapter, createdAt = at, updatedAt = at, stamp = Stamps.of(at),
            ),
        )
    }

    suspend fun updateQuote(id: Long, note: String?, color: Int) = database.withTransaction {
        val at = now()
        dao.marksChangedByQuote(id, at)
        dao.updateQuote(id, note?.takeIf { it.isNotBlank() }, color, at)
    }

    suspend fun removeQuote(id: Long) = database.withTransaction {
        dao.marksChangedByQuote(id, now())
        dao.deleteQuote(id)
    }

    /** The bookmarks and quotes of a book as they are now, each with the stamp the server knows it by. */
    suspend fun marksOf(bookId: String): Pair<List<Bookmark>, List<Quote>> = database.withTransaction {
        dao.stampBookmarks()
        dao.stampQuotes()
        dao.bookmarksNow(bookId) to dao.quotesNow(bookId)
    }

    /** The server has the quote as it was at [updatedAt]. */
    suspend fun quoteSent(id: Long, updatedAt: Long) = dao.quoteSent(id, updatedAt)

    suspend fun bookmarkSent(id: Long) = dao.bookmarkSent(id)

    /** The server heard of every change to the book's marks made up to [changedAt]. */
    suspend fun marksSynced(bookId: String, changedAt: Long) = dao.marksSynced(bookId, changedAt)

    /**
     * A quote made somewhere else, as the server tells of it. One the library
     * has already, by its stamp and place, is not taken twice.
     */
    suspend fun takeQuote(
        bookId: String, stamp: String, start: String, end: String, text: String, note: String?, color: Int, chapter: String?,
    ) = database.withTransaction {
        if (dao.quotesNow(bookId).any { it.stamp == stamp && it.start == start }) return@withTransaction
        val at = now()
        dao.insert(
            Quote(
                bookId = bookId, start = start, end = end, text = text, note = note?.takeIf { it.isNotBlank() }, color = color,
                chapter = chapter, createdAt = at, updatedAt = at, stamp = stamp, sentAt = at,
            ),
        )
    }

    /** A quote changed somewhere else. What the reader changed here since is overruled: the later word wins. */
    suspend fun takeQuoteChange(id: Long, text: String, note: String?, color: Int, chapter: String?) =
        dao.takeQuote(id, text, note?.takeIf { it.isNotBlank() }, color, chapter, now())

    /** A quote or bookmark removed somewhere else is removed here; nothing of it is told back. */
    suspend fun dropQuote(id: Long) = dao.deleteQuote(id)

    suspend fun dropBookmark(id: Long) = dao.deleteBookmark(id)

    /**
     * A bookmark made somewhere else. Returns it as the library has it: the
     * one that was on that place already, or the new one, stamped now.
     */
    suspend fun takeBookmark(bookId: String, xpointer: String, text: String): Bookmark = database.withTransaction {
        val at = now()
        dao.insert(Bookmark(bookId = bookId, xpointer = xpointer, text = text, chapter = null, createdAt = at, stamp = Stamps.of(at), sent = true))
        dao.stampBookmarks()
        val taken = dao.bookmarksNow(bookId).first { it.xpointer == xpointer }
        dao.bookmarkSent(taken.id)
        taken.copy(sent = true)
    }

    companion object {
        @Volatile
        private var instance: Library? = null

        fun get(context: Context): Library = instance ?: synchronized(this) {
            instance ?: Library(ReadingDatabase.get(context), System::currentTimeMillis).also { instance = it }
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
        fun inMemory(context: Context, now: () -> Long = System::currentTimeMillis): Library =
            Library(ReadingDatabase.inMemory(context), now)
    }
}
