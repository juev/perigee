package org.evsyukov.perigee.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.RemotePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PositionChoiceTest {
    @Test
    fun stayingOnTheOpeningPageOnlyQueuesItForTheOfferTheReaderSaw() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val library = Library.inMemory(context) { 100_000L }
        val file = File(context.cacheDir, "choice.fb2").apply { writeText("<FictionBook>a book</FictionBook>") }
        val id = library.open(file, "Book", "").id
        val here = "/body/p[1]"
        library.savePosition(id, here, 0f)
        val first = RemotePosition(id, null, 0.4f, "web", 110)
        val next = first.copy(progress = 0.5f, at = 120)
        library.offer(first)
        library.offer(next)

        library.settleOffer(first, stay = true)
        assertEquals(next, library.pendingOffer(id))
        assertFalse(checkNotNull(library.book(id)).placeUnsent)

        library.settleOffer(next, stay = true)
        val choice = checkNotNull(library.book(id))
        assertNull(library.pendingOffer(id))
        assertTrue(choice.placeUnsent)
        assertEquals(here, choice.xpointer)
        assertEquals(next.at, choice.remoteSeenAt)
    }
}
