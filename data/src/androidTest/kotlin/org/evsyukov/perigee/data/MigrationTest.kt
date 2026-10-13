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

        val database = Room.databaseBuilder(instrumentation.targetContext, ReadingDatabase::class.java, NAME).build()
        try {
            val library = Library(database) { 1000 }
            val book = library.books().first().single()
            assertEquals("Old Title", book.title)
            assertEquals("/body/p[4]", book.xpointer)
            assertEquals(0.25f, book.progress)
            assertEquals(77L, book.openedAt)
            assertEquals("abc", library.lastOpened()?.id)
            assertNull(book.uri)
            assertFalse(book.favorite)
            assertEquals(ReadingStatus.None, book.status)
            assertEquals("words", library.bookmarks("abc").first().single().text)
            assertEquals("note", library.quotes("abc").first().single().note)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
