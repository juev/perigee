package org.evsyukov.perigee.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A database written by an earlier version of the app opens in this one with nothing lost. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, ReadingDatabase::class.java)

    @Test
    fun booksOfTheFirstVersionKeepPositionBookmarksAndQuotes() = runBlocking {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO books (id, path, title, author, xpointer, progress, openedAt) " +
                    "VALUES ('abc', '/data/books/a.fb2', 'Old Title', 'Old Author', '/body/p[4]', 0.25, 77)",
            )
            db.execSQL(
                "INSERT INTO bookmarks (bookId, xpointer, text, chapter, createdAt) " +
                    "VALUES ('abc', '/body/p[2]', 'words', 'One', 5)",
            )
            db.execSQL(
                "INSERT INTO quotes (bookId, start, `end`, text, note, color, chapter, createdAt, updatedAt) " +
                    "VALUES ('abc', '/a', '/b', 'quoted', 'note', 255, NULL, 6, 6)",
            )
        }
        helper.runMigrationsAndValidate(NAME, 2, true).close()
        helper.runMigrationsAndValidate(NAME, 3, true).close()
        helper.runMigrationsAndValidate(NAME, 4, true).close()
        helper.runMigrationsAndValidate(NAME, 5, true).close()
        helper.runMigrationsAndValidate(NAME, 6, true).close()
        helper.runMigrationsAndValidate(NAME, 7, true).use { db ->
            // A book of a folder, as the version before books were read for what they are about left it.
            db.execSQL("INSERT INTO folders (id, uri, name, addedAt) VALUES (3, 'content://tree/books', 'Books', 1)")
            db.execSQL(
                "INSERT INTO books (id, path, title, author, xpointer, progress, openedAt, uri, folderId, fileSize, fileModified, format) " +
                    "VALUES ('def', '', 'In A Folder', 'Its Author', NULL, 0, 0, 'content://tree/books/b.fb2', 3, 10, 20, 'fb2')",
            )
        }
        helper.runMigrationsAndValidate(NAME, 8, true).close()

        val database = Room.databaseBuilder(instrumentation.targetContext, ReadingDatabase::class.java, NAME).build()
        try {
            val library = Library(database) { 1000 }
            val book = library.books().first().single { it.id == "abc" }
            assertEquals("Old Title", book.title)
            assertEquals("/body/p[4]", book.xpointer)
            assertEquals(0.25f, book.progress)
            assertEquals(77L, book.openedAt)
            assertEquals("abc", library.lastOpened()?.id)
            assertNull(book.uri)
            assertFalse(book.favorite)
            assertEquals(ReadingStatus.None, book.status)
            assertNull(book.serverFileId)
            assertNull(book.serverLikeId)
            assertFalse("a place from before is not news to a server", book.placeUnsent)
            assertFalse(book.stateUnsent)
            assertNull(book.rating)
            assertEquals(setOf("abc", "def"), library.unmatched(all = false).toSet())
            // Marks made before they were stamped get a stamp by when they were made, once, and are still to be sent.
            val (bookmarks, quotes) = library.marksOf("abc")
            assertEquals(Stamps.of(5), bookmarks.single().stamp)
            assertEquals(Stamps.of(6), quotes.single().stamp)
            assertTrue(quotes.single().unsent)
            assertFalse(bookmarks.single().sent)
            assertEquals("words", library.bookmarks("abc").first().single().text)
            assertEquals("note", library.quotes("abc").first().single().note)
            // What a book is about was never read from the files of these: both are due a reading.
            assertNull(book.description)
            assertEquals(listOf("abc"), library.keptUndescribed().map { it.id })
            assertFalse(library.filesIn(3).single().described)
            library.found(3, FoundBook("def", "content://tree/books/b.fb2", 10, 20, "fb2", "In A Folder", listOf("Its Author"), "en", "", "", false))
            assertTrue("read, and found to say nothing", library.filesIn(3).single().described)
            assertEquals("", library.book("def")?.description)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
