package org.evsyukov.perigee.library

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.FoundBook
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.engine.BookDescription
import org.evsyukov.perigee.reader.TestBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A look through the whole library, as the background work runs it. */
@RunWith(RobolectricTestRunner::class)
class LibraryScanTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val library = Library.inMemory(context)
    private val covers = Covers(File(context.cacheDir, "scan-covers"))
    private val first = TestFolder(File(context.cacheDir, "scan-first"))
    private val second = TestFolder(File(context.cacheDir, "scan-second"))

    private val describe: Describe = { _, _, name ->
        BookDescription(name.substringBefore('.'), listOf("Test Author"), "en", "", "", "", null)
    }

    private fun titles() = runBlocking { library.books().first().map { it.title }.sorted() }

    @Test
    fun countOfBooksFoundGoesOnAcrossFolders() = runBlocking {
        TestBook.write(File(first.dir, "one.fb2"), "One")
        TestBook.write(File(first.dir, "two.fb2"), "Two")
        TestBook.write(File(second.dir, "three.fb2"), "Three")
        val one = library.addFolder(first.uriOf(first.dir), "First")
        library.addFolder(second.uriOf(second.dir), "Second")
        var afterScan = 0
        val scan = LibraryScan(context, library, covers, describe, afterScan = { afterScan++ }) {
            if (it.id == one.id) first else second
        }
        val counts = ArrayList<Int>()

        scan.run { counts += it }

        assertEquals(listOf(1, 2, 3), counts)
        assertEquals("titled by their file names", listOf("one", "three", "two"), titles())
        assertEquals("the server is asked once, when the look is over", 1, afterScan)

        counts.clear()
        scan.run { counts += it }
        assertTrue("files read already are not counted again: $counts", counts.isEmpty())
    }

    @Test
    fun folderTakenAwayDuringALookGetsNoBooksBack() = runBlocking {
        val folder = library.addFolder(first.uriOf(first.dir), "First")
        library.removeFolder(folder.id)

        // What a look that began before the folder was removed would still do.
        library.found(
            folder.id,
            FoundBook("late", "file:///nowhere/late.fb2", 1, 1, "fb2", "Late", emptyList(), "en", "", "", false),
        )

        assertEquals(emptyList<String>(), titles())
    }
}
