package org.evsyukov.perigee.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the library keeps of bookmarks and quotes for a server to be told, and of what a server told it. */
@RunWith(AndroidJUnit4::class)
class MarksSyncTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var clock = 1_700_000_000_000L
    private val library = Library.inMemory(context) { clock.also { clock += 1000 } }
    private val accounts = Accounts.of(library) { clock }

    private val id: String = runBlocking {
        library.open(File(context.cacheDir, "marks.fb2").apply { writeText("a book with marks ${System.nanoTime()}") }, "Marks", "").id
    }

    private fun book(): Book = runBlocking { checkNotNull(library.book(id)) }

    private fun synced() = runBlocking { library.marksSynced(id, book().marksChangedAt) }

    @Test
    fun stampIsTheDevicesClockToTheSecondAndTheKeyIsWhatKoreaderMakesOfIt() {
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}").matches(Stamps.of(clock)))
        assertEquals(Stamps.of(clock), Stamps.of(clock + 999))
        // md5("2026-10-13 10:00:00|/body/p[1]"), as any MD5 tool gives it.
        assertEquals("6beb7bfe92ae5cbd4362e41d74ae9bff", Stamps.key("2026-10-13 10:00:00", "/body/p[1]"))
    }

    @Test
    fun everyChangeToMarksIsNewsForTheServerEvenARemoval() = runBlocking<Unit> {
        assertFalse(book().marksUnsent)

        val quote = library.addQuote(id, "/a", "/b", "quoted", 0xFFE066, "One")
        assertTrue(book().marksUnsent)
        val made = library.marksOf(id).second.single()
        assertTrue(made.unsent)
        assertEquals(Stamps.of(made.createdAt), made.stamp)
        library.quoteSent(made.id, made.updatedAt)
        synced()
        assertFalse(book().marksUnsent)
        assertFalse(library.marksOf(id).second.single().unsent)

        library.updateQuote(quote, "a note", 0x9BE38A)
        assertTrue(book().marksUnsent)
        assertTrue(library.marksOf(id).second.single().unsent)
        assertEquals("the stamp it is known by does not change with it", made.stamp, library.marksOf(id).second.single().stamp)
        synced()

        library.removeQuote(quote)
        assertTrue("a removal leaves no row to tell of it, only this", book().marksUnsent)
        synced()

        library.addBookmark(id, "/body/p[5]", "words", null)
        assertTrue(book().marksUnsent)
        val bookmark = library.marksOf(id).first.single()
        assertFalse(bookmark.sent)
        assertTrue(bookmark.stamp.isNotEmpty())
        library.bookmarkSent(bookmark.id)
        synced()
        library.removeBookmark(bookmark.id)
        assertTrue(book().marksUnsent)
    }

    @Test
    fun changeMadeWhileMarksWereBeingSentIsStillToBeSent() = runBlocking<Unit> {
        library.addQuote(id, "/a", "/b", "one", 1, null)
        val sending = book().marksChangedAt
        library.addQuote(id, "/c", "/d", "two", 1, null)
        library.marksSynced(id, sending)
        assertTrue(book().marksUnsent)
    }

    @Test
    fun marksFromTheServerAreNotSentBackAndNotTakenTwice() = runBlocking<Unit> {
        library.takeQuote(id, "2026-10-13 09:00:00", "/body/p[3]/text().0", "/body/p[3]/text().9", "from web", "noted", 0x38BDF8, "Two")
        library.takeQuote(id, "2026-10-13 09:00:00", "/body/p[3]/text().0", "/body/p[3]/text().9", "from web", "noted", 0x38BDF8, "Two")

        val taken = library.marksOf(id).second.single()
        assertEquals("2026-10-13 09:00:00", taken.stamp)
        assertFalse(taken.unsent)
        assertFalse("taking is not a change of the reader's", book().marksUnsent)
        assertEquals("noted", library.quotes(id).first().single().note)

        library.takeQuoteChange(taken.id, "from web", "", 0xF87171, "Two")
        val changed = library.marksOf(id).second.single()
        assertNull("an empty note is no note", changed.note)
        assertEquals(0xF87171, changed.color)
        assertFalse(changed.unsent)

        library.dropQuote(taken.id)
        assertTrue(library.marksOf(id).second.isEmpty())
        assertFalse(book().marksUnsent)
    }

    @Test
    fun bookmarkFromTheServerIsStampedHereAndOneOnThatPlaceIsKept() = runBlocking<Unit> {
        val taken = library.takeBookmark(id, "/body/p[7]", "A page marked in the browser")
        assertTrue(taken.sent)
        assertTrue(taken.stamp.isNotEmpty())
        assertFalse(book().marksUnsent)

        // The reader had marked that very place themselves.
        library.addBookmark(id, "/body/p[9]", "mine", null)
        val mine = library.marksOf(id).first.first { it.xpointer == "/body/p[9]" }
        val again = library.takeBookmark(id, "/body/p[9]", "the server's words for it")
        assertEquals(mine.id, again.id)
        assertEquals("mine", again.text)
        assertEquals(mine.stamp, again.stamp)
        assertTrue("the server has it, so it is not sent as new", library.marksOf(id).first.first { it.id == mine.id }.sent)
        assertEquals(2, library.marksOf(id).first.size)

        library.dropBookmark(taken.id)
        assertEquals(1, library.marksOf(id).first.size)
    }

    @Test
    fun anotherServerIsToldOfEveryMarkAnew() = runBlocking<Unit> {
        accounts.signIn("https://one.example", "reader", "Reader", Tokens("a", 1, "r", 2), "v3.3.0")
        library.addQuote(id, "/a", "/b", "quoted", 1, null)
        library.addBookmark(id, "/body/p[5]", "words", null)
        val (bookmarks, quotes) = library.marksOf(id)
        library.quoteSent(quotes.single().id, quotes.single().updatedAt)
        library.bookmarkSent(bookmarks.single().id)
        synced()

        accounts.signOut()

        val (after, quoted) = library.marksOf(id)
        assertTrue(quoted.single().unsent)
        assertFalse(after.single().sent)
        assertTrue(book().marksUnsent)
        assertEquals("known by the same stamp as before", quotes.single().stamp, quoted.single().stamp)
    }
}
