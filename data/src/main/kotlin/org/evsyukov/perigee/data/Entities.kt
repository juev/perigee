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
) {
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
