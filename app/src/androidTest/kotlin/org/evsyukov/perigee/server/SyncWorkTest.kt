package org.evsyukov.perigee.server

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.sync.FakeOrbit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A round of syncing as the app asks for it: through the system's WorkManager, with the app's
 * own library and account, the worker made by WorkManager itself. What a round does is checked
 * by SyncRoundTest; this is about the round being run at all, and run again when it could not
 * reach the server.
 */
@RunWith(AndroidJUnit4::class)
class SyncWorkTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val library get() = Library.get(context)
    private val server = FakeOrbit()
    private val work get() = WorkManager.getInstance(context)

    @Before
    fun signIn() = runBlocking<Unit> {
        Server.get(context).signIn(server.url, "reader", "right-password")
    }

    @After
    fun signOut() = runBlocking {
        work.cancelUniqueWork(SyncWork.NAME).result.get()
        Server.get(context).signOut()
        server.close()
    }

    /** A book of the app's library that the server has, read on from where it was first opened. */
    private suspend fun readSomething(): String {
        val file = File(context.cacheDir, "sync-work.fb2").apply { writeText("a book ${System.nanoTime()}") }
        val book = library.open(file, "Sync Work", "")
        server.files[book.id] = 7L to 70L
        library.onServer(book.id, 7, 70)
        library.savePosition(book.id, "/a", 0f)
        Thread.sleep(10)
        library.savePosition(book.id, "/b", 0.5f)
        return book.id
    }

    private fun round(): WorkInfo? = work.getWorkInfosForUniqueWork(SyncWork.NAME).get().singleOrNull()

    private fun until(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (!done()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("$what; the round is ${round()}, the server was asked ${server.seen.map { it.path }}")
            Thread.sleep(100)
        }
    }

    @Test
    fun placeReadIsSentByARoundRunInTheBackground() = runBlocking<Unit> {
        val id = readSomething()

        SyncWork.soon(context)

        until("the round is done") { round()?.state == WorkInfo.State.SUCCEEDED }
        assertEquals("/b", server.places.getValue(id).getString("progress"))
    }

    @Test
    fun roundThatCouldNotReachTheServerIsRunAgain() = runBlocking<Unit> {
        readSomething()
        server.down = true

        SyncWork.soon(context)

        until("the round waits to be run again") { round()?.let { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 } == true }
    }

    private companion object {
        /** The round begins some seconds after it is asked for. */
        const val TIMEOUT = 60_000L
    }
}
