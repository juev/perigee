package org.evsyukov.perigee.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where the reader has got to with a book, as they say it themselves. */
enum class ReadingStatus { None, ToRead, Reading, Read }

/** A book of the library: found in one of the reader's folders, or opened by hand. */
@Entity(tableName = "books")
data class Book(
    /** Fingerprint of the file contents, see [FileHash]; the same book in another folder is the same row. */
    @PrimaryKey val id: String,
    /** The copy in the app's storage that the engine reads; empty until the book is first opened. */
    val path: String,
    val title: String,
    /** Authors one to a line; see [authors]. */
    val author: String,
    /** Where the reader stopped; null until the first page turn. */
    val xpointer: String?,
    /** How far that is into the book, 0..1. */
    val progress: Float,
    /** Zero for a book never opened. */
    val openedAt: Long,
    /** The document in a folder of the library; null when the book is in none of them now. */
    @ColumnInfo(defaultValue = "NULL") val uri: String? = null,
    @ColumnInfo(defaultValue = "NULL") val folderId: Long? = null,
    /** Size and time of the document when it was last read, to tell a changed file without reading it. */
    @ColumnInfo(defaultValue = "0") val fileSize: Long = 0,
    @ColumnInfo(defaultValue = "0") val fileModified: Long = 0,
    /** Lower-case extension of the file, as "epub" or "fb2.zip". */
    @ColumnInfo(defaultValue = "") val format: String = "",
    @ColumnInfo(defaultValue = "") val language: String = "",
    @ColumnInfo(defaultValue = "") val series: String = "",
    /** Text, as books number themselves "2" as well as "2.5". */
    @ColumnInfo(defaultValue = "") val seriesNumber: String = "",
    /** Whether a cover picture is kept for the book, see [Covers]. */
    @ColumnInfo(defaultValue = "0") val hasCover: Boolean = false,
    @ColumnInfo(defaultValue = "0") val addedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    @ColumnInfo(defaultValue = "None") val status: ReadingStatus = ReadingStatus.None,
    /** The book and its file on the server the reader signed in to; null when the server has no such file. */
    @ColumnInfo(defaultValue = "NULL") val serverBookId: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val serverFileId: Long? = null,
    /** When the server was last asked about this file; zero for never. */
    @ColumnInfo(defaultValue = "0") val serverCheckedAt: Long = 0,
    /** When the reader last moved to another place in the book; zero while they have not. */
    @ColumnInfo(defaultValue = "0") val movedAt: Long = 0,
    /** [movedAt] of the place the server last took; the place is still to be sent while [movedAt] is later. */
    @ColumnInfo(defaultValue = "0") val pushedAt: Long = 0,
    /** One to five stars; null for a book not rated. */
    @ColumnInfo(defaultValue = "NULL") val rating: Int? = null,
    /** When the reader last changed [status] or [rating], and which of those changes the server last took. */
    @ColumnInfo(defaultValue = "0") val stateChangedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val statePushedAt: Long = 0,
    /** When the reader last changed [status] itself: a rating given must not carry an old status along. */
    @ColumnInfo(defaultValue = "0") val statusChangedAt: Long = 0,
    /** The server's time, in seconds, of the latest place from elsewhere the reader was told of or has decided on. */
    @ColumnInfo(defaultValue = "0") val remoteSeenAt: Long = 0,
) {
    /** Whether the reader's place is still to be sent to the server. */
    val placeUnsent: Boolean get() = movedAt > pushedAt

    /** Whether the reader's status or rating is still to be sent to the server. */
    val stateUnsent: Boolean get() = stateChangedAt > statePushedAt

    /** Whether it is the status that the reader changed since the server last heard. */
    val statusUnsent: Boolean get() = statusChangedAt > statePushedAt

    val authors: List<String> get() = author.split('\n').filter { it.isNotBlank() }

    /** Authors on one line, for showing. */
    val authorLine: String get() = authors.joinToString(", ")
}

/** A folder the reader keeps books in, picked through the system and scanned for them. */
@Entity(tableName = "folders", indices = [Index("uri", unique = true)])
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Tree URI the system granted. */
    val uri: String,
    val name: String,
    val addedAt: Long,
)

/** A list of books the reader made up. */
@Entity(tableName = "collections", indices = [Index("name", unique = true)])
data class BookCollection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "collection_books",
    primaryKeys = ["collectionId", "bookId"],
    foreignKeys = [
        ForeignKey(BookCollection::class, ["id"], ["collectionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Book::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("bookId")],
)
data class CollectionBook(val collectionId: Long, val bookId: String)

/** A document of a folder as the library last saw it. */
data class KnownFile(val id: String, val uri: String, val fileSize: Long, val fileModified: Long)

/** A book file found in a folder, with what it says about itself. */
data class FoundBook(
    val id: String,
    val uri: String,
    val fileSize: Long,
    val fileModified: Long,
    val format: String,
    val title: String,
    val authors: List<String>,
    val language: String,
    val series: String,
    val seriesNumber: String,
    val hasCover: Boolean,
)

/** A page the reader marked. */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(Book::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId", "xpointer", unique = true)],
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    /** Top of the marked page. */
    val xpointer: String,
    /** The first words of the page, to recognise it in a list. */
    val text: String,
    val chapter: String?,
    val createdAt: Long,
)

/** A passage the reader highlighted, with an optional note. */
@Entity(
    tableName = "quotes",
    foreignKeys = [ForeignKey(Book::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class Quote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val start: String,
    val end: String,
    val text: String,
    val note: String?,
    /** 0xRRGGBB. */
    val color: Int,
    val chapter: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** What a book file says about itself, for a file that is already in the app's storage. */
data class LocalBook(
    val id: String,
    val format: String,
    val title: String,
    val authors: List<String>,
    val language: String,
    val series: String,
    val seriesNumber: String,
    val hasCover: Boolean,
)

/** An OPDS catalog the reader added. */
@Entity(tableName = "catalogs", indices = [Index("url", unique = true)])
data class Catalog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The address of the catalog's first page. */
    val url: String,
    val title: String,
    /** Empty for a catalog open to everyone. */
    val user: String,
    /** The password, sealed with a key that never leaves the device; see [Secrets]. */
    val secret: String,
    val addedAt: Long,
)

/** The server the reader signed in to. There is one at most. */
@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey val id: Long = SINGLE,
    /** The address the server is reached at, without the path of its API. */
    val url: String,
    val user: String,
    /** What the server calls the reader. */
    val name: String,
    /** The tokens of the session, sealed; see [Secrets]. */
    val accessSecret: String,
    val accessExpiresAt: Long,
    val refreshSecret: String,
    val refreshExpiresAt: Long,
    /** What this installation calls itself to the server; made up when the reader signs in. */
    val deviceId: String,
    /** The user name of the channel reading devices sync through; empty until it is set up. */
    val deviceUser: String,
    /** The key of that channel, sealed. */
    val deviceSecret: String,
    /** The version the server named when it was last asked. */
    val serverVersion: String,
    val addedAt: Long,
) {
    companion object {
        const val SINGLE = 1L
    }
}

/** A session with the server: a short-lived token to ask with, and a longer-lived one to get the next pair. */
data class Tokens(val access: String, val accessExpiresAt: Long, val refresh: String, val refreshExpiresAt: Long)

/**
 * A place in a book the reader got to somewhere else, later than here, and
 * has not decided about yet: go there, or stay.
 */
@Entity(
    tableName = "remote_positions",
    foreignKeys = [ForeignKey(Book::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class RemotePosition(
    @PrimaryKey val bookId: String,
    /** Null when only how far into the book is known, as from a reader that does not count in xpointers. */
    val xpointer: String?,
    /** 0..1. */
    val progress: Float,
    /** What the other device or reader calls itself. */
    val device: String,
    /** The server's time of it, in seconds. */
    val at: Long,
)

/** A page the reader spent time on, for the server's statistics; kept until the server has it. */
@Entity(
    tableName = "page_reads",
    foreignKeys = [ForeignKey(Book::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class PageRead(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    /** Counted from one, as the reader sees it. */
    val page: Int,
    val totalPages: Int,
    /** Seconds since 1970. */
    val startedAt: Long,
    val seconds: Int,
)
