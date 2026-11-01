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

    /** A book of the library the server has as its book 7, with nothing waiting to be sent. */
    private fun synced(name: String): String = runBlocking {
        val id = opened(name).id
        library.onServer(id, 7, 70)
        library.stateSent(id, book(id).stateChangedAt)
        id
    }

    @Test
    fun changesConfirmedByTheServerAreNotUndoneByOlderOnesFromHere() = runBlocking<Unit> {
        val id = synced("confirmed.fb2")

        // Set here and not sent yet; then set again on the server's page, which the server confirmed.
        library.setStatus(id, ReadingStatus.Read)
        assertTrue(library.statusFromServer(book(id), ReadingStatus.ToRead))
        assertEquals(ReadingStatus.ToRead, book(id).status)
        assertFalse("the older status from here is not sent over the confirmed one", book(id).statusUnsent)

        // A status the app has no word for, as "on hold", leaves the reader's own here and is not sent over either.
        library.setStatus(id, ReadingStatus.Reading)
        assertTrue(library.statusFromServer(book(id), null))
        assertEquals(ReadingStatus.Reading, book(id).status)
        assertFalse(book(id).statusUnsent)

        // A rating confirmed by the server leaves nothing to send, unless a status changed here waits.
        library.setRating(id, 2)
        assertTrue(library.ratingFromServer(book(id), 4))
        assertEquals(4, book(id).rating)
        assertFalse(book(id).stateUnsent)
        library.setStatus(id, ReadingStatus.Read)
        assertTrue(library.ratingFromServer(book(id), 5))
        assertEquals(5, book(id).rating)
        assertTrue("the status changed here still goes", book(id).statusUnsent)

        // And a status confirmed leaves a rating changed here to go.
        library.stateSent(id, book(id).stateChangedAt)
        library.setRating(id, 1)
        assertTrue(library.statusFromServer(book(id), ReadingStatus.ToRead))
        assertTrue("the rating changed here still goes", book(id).stateUnsent)
        assertEquals(1, book(id).rating)

        library.setReview(id, "Written here.")
        assertTrue(book(id).reviewUnsent)
        assertTrue(library.reviewFromServer(book(id), "  Written on the server's page.  "))
        assertEquals("Written on the server's page.", book(id).review)
        assertFalse("the review from here is not sent over it", book(id).reviewUnsent)
        assertTrue(library.reviewFromServer(book(id), null))
        assertNull(book(id).review)

        assertEquals(listOf(id), library.copiesOf(7).map { it.id })
        assertTrue("a book the server has not is no copy of any of its books", library.copiesOf(8).isEmpty())
    }

    @Test
    fun statusChangedHereWhileTheServerWasAskedStaysAndGoes() = runBlocking<Unit> {
        val id = synced("status-meanwhile.fb2")
        library.setRating(id, 3)
        val seen = book(id)
        // The reader changes the status here while the server is setting another one.
        library.setStatus(id, ReadingStatus.Read)

        assertFalse("the later status is the reader's", library.statusFromServer(seen, ReadingStatus.ToRead))
        assertEquals(ReadingStatus.Read, book(id).status)
        assertTrue("and still goes to the server", book(id).statusUnsent)
        assertEquals("the stars changed here are untouched", 3, book(id).rating)
        assertTrue(book(id).stateUnsent)

        assertFalse(library.statusFromServer(seen, null))
        assertTrue(book(id).statusUnsent)
    }

    @Test
    fun ratingChangedHereWhileTheServerWasAskedStaysAndGoes() = runBlocking<Unit> {
        val id = synced("rating-meanwhile.fb2")
        val seen = book(id)
        library.setRating(id, 2)

        assertFalse(library.ratingFromServer(seen, 5))
        assertEquals("the later stars are the reader's", 2, book(id).rating)
        assertTrue("and still go to the server", book(id).stateUnsent)

        // Only the status changed here meanwhile: the confirmed stars are taken, and the status still goes.
        val again = synced("rating-and-status.fb2")
        val before = book(again)
        library.setStatus(again, ReadingStatus.Read)
        assertTrue(library.ratingFromServer(before, 5))
        assertEquals(5, book(again).rating)
        assertTrue(book(again).statusUnsent)
        assertEquals(ReadingStatus.Read, book(again).status)
    }

    @Test
    fun reviewWrittenHereWhileTheServerWasAskedStaysAndGoes() = runBlocking<Unit> {
        val id = synced("review-meanwhile.fb2")
        library.setReview(id, "Older, from here.")
        library.reviewSent(id, book(id).reviewChangedAt)
        val seen = book(id)
        library.setReview(id, "Later, from here.")

        assertFalse(library.reviewFromServer(seen, "From the server's page."))
        assertEquals("Later, from here.", book(id).review)
        assertTrue("it is not taken for sent", book(id).reviewUnsent)
    }

    @Test
    fun starsChangedHereAndBackWhileTheServerWasAskedAreStillTheReaders() = runBlocking<Unit> {
        val id = synced("stars-back.fb2")
        library.setRating(id, 2)
        library.stateSent(id, book(id).stateChangedAt)
        val seen = book(id)
        // Changed and changed back here meanwhile, and the status too: the same stars, given later.
        library.setRating(id, 4)
        library.setRating(id, 2)
        library.setStatus(id, ReadingStatus.Read)

        assertFalse(library.ratingFromServer(seen, 5))
        assertEquals(2, book(id).rating)
        assertTrue("the reader's stars still go", book(id).ratingUnsent)
        assertTrue(book(id).statusUnsent)
    }

    @Test
    fun twoChangesInOneMillisecondAreTwo() = runBlocking<Unit> {
        val still = Library.inMemory(context) { 5_000 }
        val file = File(context.cacheDir, "one-millisecond.fb2").apply { writeText("the text of one millisecond") }
        val id = still.open(file, "one-millisecond.fb2", "").id
        still.setRating(id, 3)
        val first = checkNotNull(still.book(id))
        still.setRating(id, 3)
        val second = checkNotNull(still.book(id))
        assertTrue("${second.ratingChangedAt} after ${first.ratingChangedAt}", second.ratingChangedAt > first.ratingChangedAt)
        assertTrue(second.stateChangedAt >= second.ratingChangedAt)
        still.setStatus(id, ReadingStatus.Read)
        val third = checkNotNull(still.book(id))
        assertTrue(third.statusChangedAt > second.stateChangedAt)
        assertEquals("the state's time is its last change's", third.statusChangedAt, third.stateChangedAt)
        still.setReview(id, "One.")
        val reviewed = checkNotNull(still.book(id)).reviewChangedAt
        still.setReview(id, "Two.")
        assertTrue(checkNotNull(still.book(id)).reviewChangedAt > reviewed)
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
