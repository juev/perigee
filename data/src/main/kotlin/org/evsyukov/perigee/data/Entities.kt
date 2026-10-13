package org.evsyukov.perigee.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A book the reader has opened. */
@Entity(tableName = "books")
data class Book(
    /** Fingerprint of the file contents, see [FileHash]; the same book in another folder is the same row. */
    @PrimaryKey val id: String,
    val path: String,
    val title: String,
    val author: String,
    /** Where the reader stopped; null until the first page turn. */
    val xpointer: String?,
    /** How far that is into the book, 0..1. */
    val progress: Float,
    val openedAt: Long,
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
