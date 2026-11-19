package org.evsyukov.perigee.server

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.FakeAndroidKeyStore
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.FakeOrbit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadingOutboxTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val clock = 1_700_000_000_000L
    private val library = Library.inMemory(context) { clock }
    private val accounts = Accounts.of(library) { clock }
    private val orbit = BookOrbit(accounts, library, "0.0.1", now = { clock })
    private val server = FakeOrbit()

    @Before
    fun signIn() = runBlocking<Unit> {
        FakeAndroidKeyStore.install()
        orbit.signIn(server.url, "reader", "right-password")
    }

    @After
    fun stop() = server.close()

    @Test
    fun httpSuccessWithoutAnAcknowledgementDoesNotLosePageEvents() = runBlocking<Unit> {
        val file = File(context.cacheDir, "outbox.fb2").apply { writeText("A book the server no longer knows") }
        val book = library.open(file, "Book", "")
        library.onServer(book.id, 7, 70)
        // Don't let the unrelated annotation exchange discover the missing file first.
        library.setStatus(book.id, org.evsyukov.perigee.data.ReadingStatus.ToRead)
        val nowSeconds = clock / 1000
        val times = listOf(nowSeconds - 4L * 86_400, nowSeconds - 86_400L)
        times.forEachIndexed { index, at -> library.addPageRead(book.id, index + 1, 10, at, 30) }
        assertEquals("a matched book has both events pending before sync", times, library.pageReadsToSend(10).map { it.startedAt })

        val first = orbit.sync()
        assertEquals("requests: ${server.seen.map { it.path }}", 1, server.asked("/koreader/plugin/page-stats").size)
        assertTrue(server.pagesRead.isEmpty())
        assertEquals("the unmatched hash was not acknowledged", times, library.pageReadsToSend(10).map { it.startedAt })
        assertEquals(0, first.pagesSent)

        server.files[book.id] = 7L to 70L
        assertEquals(2, orbit.sync().pagesSent)
        assertEquals(times, server.pagesRead.map { it.getLong("startTime") })
        assertTrue(library.pageReadsToSend(10).isEmpty())
    }

    @Test
    fun anOldOfflineReadReachesTheServerWhenTheBookIsMatchedLater() = runBlocking<Unit> {
        val file = File(context.cacheDir, "old-read.fb2").apply { writeText("A book read while offline") }
        val book = library.open(file, "Old read", "")
        val at = clock / 1000 - 100L * 86_400
        library.addPageRead(book.id, 4, 10, at, 30)
        assertEquals(0, orbit.sync().pagesSent)

        server.files[book.id] = 7L to 70L
        library.onServer(book.id, 7, 70)
        assertEquals("pending activity must not expire", 1, orbit.sync().pagesSent)
        assertEquals(at, server.pagesRead.single().getLong("startTime"))
        assertEquals(0, orbit.sync().pagesSent)
    }
}
