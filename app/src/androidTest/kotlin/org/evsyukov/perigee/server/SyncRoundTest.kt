package org.evsyukov.perigee.server

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.FakeOrbit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the background does with a round of syncing: whether it is done with, or to be tried again. */
@RunWith(AndroidJUnit4::class)
class SyncRoundTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var clock = 1_700_000_000_000L
    private val library = Library.inMemory(context) { clock }
    private val accounts = Accounts.of(library) { clock }
    private val orbit = BookOrbit(accounts, library, "0.0.1", now = { clock })
    private val server = FakeOrbit()

    @After
    fun stop() = server.close()

    private suspend fun readSomething(): String {
        val file = File(context.cacheDir, "round.fb2").apply { writeText("a book ${System.nanoTime()}") }
        val book = library.open(file, "Round", "")
        server.files[book.id] = 7L to 70L
        library.onServer(book.id, 7, 70)
        library.savePosition(book.id, "/a", 0f)
        clock += 60_000
        library.savePosition(book.id, "/b", 0.5f)
        return book.id
    }

    @Test
    fun readerSignedInNowhereHasNothingToSync() = runBlocking<Unit> {
        assertEquals(SyncOutcome.Done, syncRound(orbit, accounts))
        assertTrue(server.seen.isEmpty())
    }

    @Test
    fun roundThatWentThroughIsDone() = runBlocking<Unit> {
        orbit.signIn(server.url, "reader", "right-password")
        val id = readSomething()
        assertEquals(SyncOutcome.Done, syncRound(orbit, accounts))
        assertEquals("/b", server.places.getValue(id).getString("progress"))
    }

    @Test
    fun serverThatCannotBeReachedIsTriedAgainLater() = runBlocking<Unit> {
        orbit.signIn(server.url, "reader", "right-password")
        val id = readSomething()
        server.down = true
        assertEquals(SyncOutcome.Later, syncRound(orbit, accounts))
        server.down = false
        assertEquals(SyncOutcome.Done, syncRound(orbit, accounts))
        assertEquals("/b", server.places.getValue(id).getString("progress"))
    }

    @Test
    fun whatOnlyTheReaderCanPutRightIsNotTriedOverAndOver() = runBlocking<Unit> {
        orbit.signIn(server.url, "reader", "right-password")
        readSomething()

        server.deviceKey = FakeOrbit.md5("changed in the browser")
        assertEquals("the password of the devices' channel", SyncOutcome.Waiting, syncRound(orbit, accounts))

        server.version = "v4.0.0"
        orbit.checkServer()
        assertEquals("a server the app was not made for", SyncOutcome.Waiting, syncRound(orbit, accounts))

        server.version = "v3.3.0"
        orbit.checkServer()
        server.access.clear()
        server.refresh.clear()
        // The channel works again, so that it is the ended session that stops the round.
        accounts.saveDevice(accounts.current()!!.deviceUser, server.deviceKey!!)
        assertEquals("a session that ended", SyncOutcome.Waiting, syncRound(orbit, accounts))
    }
}
