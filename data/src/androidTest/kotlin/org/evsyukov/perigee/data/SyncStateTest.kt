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

/** What the library keeps for a server to be told, and of what a server told it. */
@RunWith(AndroidJUnit4::class)
class SyncStateTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var clock = 1000L
    private val library = Library.inMemory(context) { clock++ }
    private val accounts = Accounts.of(library) { clock++ }

    private fun opened(name: String): Book = runBlocking {
        val file = File(context.cacheDir, name).apply { writeText("the text of $name") }
        library.open(file, name, "")
    }

    private fun book(id: String): Book = runBlocking { checkNotNull(library.book(id)) }

    @Test
    fun whereABookOpensTheFirstTimeIsNotAMove() = runBlocking<Unit> {
        val id = opened("new.fb2").id
        library.savePosition(id, "/body/p[1]", 0f)
        assertFalse("another device may be further on, and must not be overruled by a book just opened", book(id).placeUnsent)

        library.savePosition(id, "/body/p[1]", 0f)
        assertFalse("the same place shown again, as after a rotation", book(id).placeUnsent)

        library.savePosition(id, "/body/p[9]", 0.2f)
        val moved = book(id)
        assertTrue(moved.placeUnsent)

        library.placeSent(id, moved.movedAt)
        assertFalse(book(id).placeUnsent)
        library.savePosition(id, "/body/p[9]", 0.2f)
        assertFalse(book(id).placeUnsent)
        library.savePosition(id, "/body/p[12]", 0.3f)
        assertTrue(book(id).placeUnsent)
    }

    @Test
    fun bookTheServerHasSaysWhileAnythingDoneInItIsNotSent() = runBlocking<Unit> {
        val id = opened("unsent.fb2").id
        library.savePosition(id, "/a", 0f)
        library.savePosition(id, "/b", 0.1f)
        assertFalse("a book the server has not has nothing to send", book(id).syncUnsent)

        library.onServer(id, 7, 70)
        assertTrue("a place, and the status opening the book set", book(id).syncUnsent)
        library.placeSent(id, book(id).movedAt)
        assertTrue("the status", book(id).syncUnsent)
        library.stateSent(id, book(id).stateChangedAt)
        assertFalse(book(id).syncUnsent)

        library.setRating(id, 4)
        assertTrue("a rating", book(id).syncUnsent)
        library.stateSent(id, book(id).stateChangedAt)
        assertFalse(book(id).syncUnsent)

        library.addBookmark(id, "/b", "a page", null)
        assertTrue("a bookmark", book(id).syncUnsent)
    }

    @Test
    fun placeSentWhileTheReaderMovedOnIsStillToBeSent() = runBlocking<Unit> {
        val id = opened("moving.fb2").id
        library.savePosition(id, "/a", 0f)
        library.savePosition(id, "/b", 0.1f)
        val sending = book(id).movedAt
        library.savePosition(id, "/c", 0.2f)
        library.placeSent(id, sending)
        assertTrue(book(id).placeUnsent)
    }

    @Test
    fun statusAndRatingChangedByTheReaderAreToBeSent() = runBlocking<Unit> {
        val opened = opened("state.fb2")
        assertEquals(ReadingStatus.Reading, opened.status)
        assertTrue("opening a book says it is being read", opened.stateUnsent)
        library.stateSent(opened.id, opened.stateChangedAt)
        assertFalse(book(opened.id).stateUnsent)

        library.open(File(opened.path), "", "")
        assertFalse("opening it again changes nothing", book(opened.id).stateUnsent)

        library.setRating(opened.id, 4)
        assertEquals(4, book(opened.id).rating)
        assertTrue(book(opened.id).stateUnsent)
        assertFalse("stars are not a word about the status", book(opened.id).statusUnsent)
        library.stateSent(opened.id, book(opened.id).stateChangedAt)

        library.setStatus(opened.id, ReadingStatus.Read)
        assertTrue(book(opened.id).stateUnsent)
        assertTrue(book(opened.id).statusUnsent)

        library.setRating(opened.id, 9)
        assertEquals("no more stars than there are", 5, book(opened.id).rating)
        library.setRating(opened.id, null)
        assertNull(book(opened.id).rating)
    }

    @Test
    fun serversStatusAndRatingGiveWayToWhatTheReaderChangedSince() = runBlocking<Unit> {
        val id = opened("taken.fb2").id
        library.stateSent(id, book(id).stateChangedAt)

        library.takeState(id, ReadingStatus.Read, 5)
        assertEquals(ReadingStatus.Read, book(id).status)
        assertEquals(5, book(id).rating)
        assertFalse("what came from the server is not sent back to it", book(id).stateUnsent)

        library.setRating(id, 2)
        library.takeState(id, ReadingStatus.ToRead, 4)
        assertEquals("the reader's own change is not undone before the server heard of it", 2, book(id).rating)
        assertEquals(ReadingStatus.Read, book(id).status)
    }

    @Test
    fun reviewIsKeptAsTheServerKeepsItAndGivesWayToNone() = runBlocking<Unit> {
        val id = opened("reviewed.fb2").id
        library.setReview(id, "  Worth it.\n")
        assertEquals("no white space at its ends, as the server keeps it", "Worth it.", book(id).review)
        assertTrue(book(id).reviewUnsent)

        library.takeReview(id, "From the browser.")
        assertEquals("the reader's own is not undone before the server heard of it", "Worth it.", book(id).review)
        library.reviewSent(id, book(id).reviewChangedAt)
        library.takeReview(id, "From the browser.")
        assertEquals("From the browser.", book(id).review)
        assertFalse("what came from the server is not sent back to it", book(id).reviewUnsent)

        library.setReview(id, "x".repeat(Library.REVIEW_MAX_LENGTH + 5))
        assertEquals("no longer than the server keeps", Library.REVIEW_MAX_LENGTH, book(id).review?.length)
        library.setReview(id, " \n ")
        assertNull("nothing but white space takes it off", book(id).review)
        assertTrue("and that is news for the server", book(id).reviewUnsent)
    }

    @Test
    fun placeFromElsewhereWaitsForTheReaderToDecide() = runBlocking<Unit> {
        val id = opened("offered.fb2").id
        assertNull(library.offerOf(id).first())

        library.offer(RemotePosition(id, "/body/p[40]", 0.6f, "web", at = 500))
        assertEquals(0.6f, library.offerOf(id).first()?.progress)
        assertEquals(500L, book(id).remoteSeenAt)

        // A later place from elsewhere takes the place of the one not decided about.
        library.offer(RemotePosition(id, null, 0.7f, "KOReader", at = 600))
        assertEquals("KOReader", library.pendingOffer(id)?.device)
        assertEquals(600L, book(id).remoteSeenAt)

        library.settleOffer(id)
        assertNull(library.offerOf(id).first())
        assertEquals("what was decided about is remembered, not to be offered again", 600L, book(id).remoteSeenAt)

        library.remoteSeen(id, 550)
        assertEquals("an older time does not take it back", 600L, book(id).remoteSeenAt)
    }

    @Test
    fun pagesReadAreKeptForBooksTheServerHas() = runBlocking<Unit> {
        val there = opened("there.fb2").id
        val local = opened("local.fb2").id
        library.onServer(there, 7, 70)
        library.addPageRead(there, page = 3, totalPages = 20, startedAt = 100, seconds = 40)
        library.addPageRead(local, page = 1, totalPages = 9, startedAt = 110, seconds = 30)
        library.addPageRead(there, page = 4, totalPages = 20, startedAt = 140, seconds = 35)

        val toSend = library.pageReadsToSend(10)
        assertEquals(listOf(3, 4), toSend.map { it.page })
        assertEquals(listOf(3), library.pageReadsToSend(1).map { it.page })

        library.pageReadsSent(toSend.take(1))
        assertEquals(listOf(4), library.pageReadsToSend(10).map { it.page })

        // The other book comes to be on the server later; what was read in it meanwhile is told then.
        library.onServer(local, 8, 80)
        assertEquals(listOf(1, 4), library.pageReadsToSend(10).map { it.page }.sorted())

        library.forgetPageReadsBefore(120)
        assertEquals(listOf(4), library.pageReadsToSend(10).map { it.page })
    }

    @Test
    fun anotherServerIsToldEverythingAnew() = runBlocking<Unit> {
        val tokens = Tokens("a", 1, "r", 2)
        accounts.signIn("https://one.example", "reader", "Reader", tokens, "v3.3.0")
        val id = opened("carried.fb2").id
        library.savePosition(id, "/a", 0f)
        library.savePosition(id, "/b", 0.4f)
        library.onServer(id, 7, 70)
        library.placeSent(id, book(id).movedAt)
        library.stateSent(id, book(id).stateChangedAt)
        library.setReview(id, "Worth it.")
        library.reviewSent(id, book(id).reviewChangedAt)
        library.offer(RemotePosition(id, "/z", 0.9f, "web", at = 700))

        accounts.signOut()

        val after = book(id)
        assertTrue("the next server has not heard where the reader is", after.placeUnsent)
        assertTrue(after.stateUnsent)
        assertTrue("nor the reader's review", after.reviewUnsent)
        assertEquals(0L, after.remoteSeenAt)
        assertNull("an offer of the server left behind is dropped", library.pendingOffer(id))
        assertEquals("the reader's place stays", "/b", after.xpointer)
    }
}
