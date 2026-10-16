package org.evsyukov.perigee.library

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A look through the library as the app asks for it: through the system's WorkManager, with
 * the app's own library, the worker made by WorkManager itself. What a look does is checked by
 * LibraryScanTest and LibraryScannerTest; this is about the look being run at all.
 */
@RunWith(AndroidJUnit4::class)
class ScanWorkTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val work get() = WorkManager.getInstance(context)

    @After
    fun stop() {
        work.cancelUniqueWork(ScanWork.NAME).result.get()
    }

    private fun look(): WorkInfo? = work.getWorkInfosForUniqueWork(ScanWork.NAME).get().lastOrNull()

    @Test
    fun lookRunsInTheBackgroundAndSaysWhenItIsOver() = runBlocking<Unit> {
        val stray = File(File(context.filesDir, "books").apply { mkdirs() }, "scan-work-stray.fb2").apply {
            writeText("left behind")
            setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)
        }
        val scans = ScanWork(context)

        scans.start(again = true)

        val deadline = System.currentTimeMillis() + TIMEOUT
        while (look()?.state != WorkInfo.State.SUCCEEDED) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("the look is not over; it is ${look()}")
            Thread.sleep(100)
        }
        assertFalse("the look removed the copy no book is read from", stray.exists())
        assertNull("no look is under way", withTimeout(TIMEOUT) { scans.found.first() })
    }

    private companion object {
        const val TIMEOUT = 30_000L
    }
}
