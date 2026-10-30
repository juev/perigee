package org.evsyukov.perigee.reader

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.ReaderPreferences
import org.evsyukov.perigee.data.ReaderPrefs
import org.evsyukov.perigee.data.RemotePosition
import org.evsyukov.perigee.server.Server
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * What the reader's model does with places in a book, checked on a device with the engine and
 * without the screen: positions are the engine's own, and no window, touch or keyboard is in the way.
 * The screen's touches and controls are checked by ReaderScreenTest.
 */
@RunWith(AndroidJUnit4::class)
class ReaderViewModelTest {
    @get:Rule
    val testName = TestName()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext as Application
    private val library get() = Library.get(context)
    private val stores = mutableListOf<ViewModelStore>()
    private val held = mutableListOf<HeldLibrary>()
    private lateinit var book: File

    @Before
    fun freshBookAndDefaultSettings() {
        runBlocking { ReaderPreferences.get(context).update { ReaderPrefs() } }
        // The library knows a book by its contents, and it is the app's own, kept from run to run:
        // a title of its own makes every test, and every run of it, a new book.
        book = TestBook.write(File(context.cacheDir, "model-test.fb2"), title = "${testName.methodName} ${System.nanoTime()}")
    }

    @After
    fun clearModels() {
        instrumentation.runOnMainSync { stores.forEach(ViewModelStore::clear) }
        held.forEach(HeldLibrary::letThrough)
        untilTrue("the writes held are made") { held.all { it.running.get() == 0 } }
        ReaderViewModel.clock = System::currentTimeMillis
    }

    /** The model is driven as the screen drives it: on the main thread. */
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /**
     * A model of its own, as a new start of the app, with the book open at the size of a phone's page.
     * With [asked], the server is not there, and the books it would be asked about are put into it.
     * With [writes], the place and the pages read are written there instead of straight to the library.
     */
    private fun opened(asked: MutableList<String>? = null, writes: ReadingStore? = null): ReaderViewModel {
        lateinit var model: ReaderViewModel
        onMain {
            val store = ViewModelStore().also(stores::add)
            val factory = if (asked == null && writes == null) {
                ViewModelProvider.AndroidViewModelFactory.getInstance(context)
            } else {
                viewModelFactory {
                    initializer {
                        ReaderViewModel(context, library, writes ?: ReadingStore.of(library)) { id ->
                            if (asked == null) Server.bookOpened(context, id) else synchronized(asked) { asked += id }
                        }
                    }
                }
            }
            model = ViewModelProvider(store, factory)[ReaderViewModel::class.java]
            model.open(book)
            model.setViewport(Viewport(WIDTH, HEIGHT, DENSITY, DENSITY_DPI))
        }
        model.until("the book is open") { it.page != null && !it.busy }
        return model
    }

    private fun ReaderViewModel.until(what: String, done: (ReaderState) -> Boolean): ReaderState {
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (true) {
            val now = state.value
            if (done(now)) return now
            if (System.currentTimeMillis() > deadline) throw AssertionError("$what; the page is ${now.page?.index}, the state is $now")
            Thread.sleep(10)
        }
    }

    private fun untilTrue(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (!done()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError(what)
            Thread.sleep(10)
        }
    }

    private fun ReaderViewModel.shown(): Int = state.value.page!!.index

    /** Turns forward one page at a time, each one shown before the next. */
    private fun ReaderViewModel.forward(times: Int) = repeat(times) {
        val before = shown()
        onMain { turn(1) }
        until("page ${before + 1} is shown") { it.page?.index == before + 1 && !it.busy }
    }

    private fun ReaderViewModel.offered(position: RemotePosition): RemotePosition {
        runBlocking { library.offer(position) }
        return until("the place is offered") { it.offer != null }.offer!!
    }

    @Test
    fun bookReopensWhereItWasLeft() {
        val first = opened()
        first.forward(3)
        val left = first.state.value.page!!.xpointer
        // The position is written to the library right after the page is shown.
        untilTrue("the place is kept") { runBlocking { library.lastOpened() }?.xpointer == left }

        // A new model knows nothing of the old one, like the app started again.
        val reopened = opened().state.value.page!!
        assertEquals(left, reopened.xpointer)
        assertEquals(3, reopened.index)
    }

    @Test
    fun backReturnsFromAJumpAfterTheAppIsStartedAgain() {
        val first = opened()
        first.forward(2)
        val before = first.state.value.page!!.xpointer
        val chapter = first.state.value.toc[2]
        onMain { first.goTo(chapter.xpointer) }
        first.until("the chapter is shown") { it.page?.index == chapter.page }
        val id = first.state.value.book!!.id
        untilTrue("the place jumped from is kept") { runBlocking { library.book(id) }?.jumpedFrom == listOf(before) }

        // A new model knows nothing of the old one, like the app started again.
        val reopened = opened()
        assertTrue("there is a place to return to", reopened.state.value.canGoBack)
        onMain { reopened.goBack() }
        reopened.until("the reader is back where they jumped from, with nothing more to return to") {
            it.page?.index == 2 && !it.canGoBack
        }
        untilTrue("the return is kept too") { runBlocking { library.book(id) }?.jumpedFrom == emptyList<String>() }
    }

    @Test
    fun readerGoesToThePlaceTheyGotToElsewhereAndCanComeBack() {
        val model = opened()
        // A place further on, as another device would name it.
        model.forward(5)
        val there = model.state.value.page!!.xpointer
        onMain { model.goToProgress(0f) }
        model.until("the first page is shown") { it.page?.index == 0 }
        val id = model.state.value.book!!.id
        model.offered(RemotePosition(id, there, 0.3f, "web", at = 100))

        onMain { model.acceptOffer() }

        model.until("the place from elsewhere is shown, and not offered again") { it.page?.index == 5 && it.offer == null }
        assertNull("decided about, and not asked again", runBlocking { library.pendingOffer(id) })
        assertTrue("the place the reader went to is theirs to tell the server now", runBlocking { library.book(id) }!!.placeUnsent)

        onMain { model.goBack() }
        model.until("the reader is back where they were") { it.page?.index == 0 }
    }

    @Test
    fun placeKnownOnlyAsAShareOfTheBookIsGoneToByThatShare() {
        val model = opened()
        val pages = model.state.value.page!!.pageCount
        model.offered(RemotePosition(model.state.value.book!!.id, null, 0.5f, "", at = 100))

        onMain { model.acceptOffer() }

        val expected = Math.round((pages - 1) * 0.5f)
        model.until("the middle of the book is shown") { it.page?.index == expected }
    }

    @Test
    fun readerWhoStaysIsNotAskedAgain() {
        val model = opened()
        model.forward(1)
        val id = model.state.value.book!!.id
        model.offered(RemotePosition(id, null, 0.9f, "KOReader", at = 100))

        onMain { model.declineOffer() }

        model.until("the offer is gone") { it.offer == null }
        assertEquals(1, model.shown())
        assertNull(runBlocking { library.pendingOffer(id) })
        assertEquals("remembered as seen", 100L, runBlocking { library.book(id) }!!.remoteSeenAt)
    }

    @Test
    fun serverIsAskedAgainWhenTheReaderComesBackToTheBook() {
        val asked = mutableListOf<String>()
        val model = opened(asked)
        val id = model.state.value.book!!.id
        untilTrue("the server is asked about the book opened") { synchronized(asked) { asked == listOf(id) } }

        // The screen shows the book that was just opened: nothing new to ask about.
        onMain { model.resumed() }
        // The reader puts the app away, reads on elsewhere, and comes back.
        onMain { model.paused() }
        onMain { model.resumed() }

        untilTrue("the server is asked again") { synchronized(asked) { asked == listOf(id, id) } }
    }

    @Test
    fun bookmarkIsSetOnThePageShownAndTakenOff() {
        val model = opened()
        model.forward(1)

        onMain { model.toggleBookmark() }
        val marked = model.until("the page is marked") { it.pageBookmark != null && it.bookmarks.size == 1 }
        assertEquals("the mark is where the page begins", marked.page!!.xpointer, marked.pageBookmark!!.xpointer)
        assertTrue("it says what the page says", marked.pageBookmark!!.text.isNotBlank())

        onMain { model.turn(-1) }
        model.until("another page has no mark") { it.page?.index == 0 && it.pageBookmark == null }

        // The bookmark leads back to its page.
        onMain { model.goTo(marked.pageBookmark!!.xpointer) }
        model.until("the marked page is shown with its mark") { it.page?.index == 1 && it.pageBookmark != null }

        onMain { model.toggleBookmark() }
        model.until("the mark is taken off") { it.pageBookmark == null && it.bookmarks.isEmpty() }
    }

    @Test
    fun timeSpentOnPagesIsKeptForTheServer() {
        var now = 1_700_000_000_000L
        ReaderViewModel.clock = { now }
        val model = opened()
        val id = model.state.value.book!!.id
        val pages = model.state.value.page!!.pageCount
        runBlocking { library.onServer(id, 7, 70) }
        fun reads() = runBlocking { library.pageReadsToSend(Int.MAX_VALUE) }.filter { it.bookId == id }

        now += 42_000
        model.forward(1)
        untilTrue("the page is counted") { reads().size == 1 }
        val first = reads().single()
        assertEquals(listOf(1, pages, 42), listOf(first.page, first.totalPages, first.seconds))
        assertEquals(1_700_000_000L, first.startedAt)

        // A page glanced at on the way is not a page read.
        now += 2_000
        model.forward(1)
        // One left open over a cup of tea was not read all that time.
        now += 900_000
        model.forward(1)
        untilTrue("the page left open is counted") { reads().size == 2 }
        assertEquals(listOf(3, 120), reads().last().let { listOf(it.page, it.seconds) })

        // Putting the app away ends the page; coming back begins it anew.
        now += 30_000
        onMain { model.paused() }
        untilTrue("the page is counted when the app is put away") { reads().size == 3 }
        now += 600_000
        onMain { model.resumed() }
        now += 10_000
        model.forward(1)
        untilTrue("the page after the return is counted") { reads().size == 4 }
        assertEquals("the time away is not time read", listOf(4, 10), reads().last().let { listOf(it.page, it.seconds) })
    }

    /**
     * The library as storage slow to write: while held, writes wait. They are made one at a time in
     * the order they were asked for, as the library's own writes are; [running] counts those under way.
     */
    private inner class HeldLibrary : ReadingStore {
        private val open = MutableStateFlow(true)
        private val inTurn = Mutex()
        val running = AtomicInteger()

        fun hold() {
            open.value = false
        }

        fun letThrough() {
            open.value = true
        }

        override suspend fun savePosition(bookId: String, xpointer: String, progress: Float) =
            write { library.savePosition(bookId, xpointer, progress) }

        override suspend fun addPageRead(bookId: String, page: Int, totalPages: Int, startedAt: Long, seconds: Int) =
            write { library.addPageRead(bookId, page, totalPages, startedAt, seconds) }

        private suspend fun write(write: suspend () -> Unit) {
            running.incrementAndGet()
            try {
                inTurn.withLock {
                    open.first { it }
                    write()
                }
            } finally {
                running.decrementAndGet()
            }
        }
    }

    /**
     * The pages of book [id] read and not sent yet, as page and seconds, in the order they were begun. All
     * of them: the library keeps pages of other books from run to run. Not in the order written: the page
     * the app is put away on may be written before the page left, whose place is written first.
     */
    private fun readsOf(id: String) = runBlocking { library.pageReadsToSend(Int.MAX_VALUE) }
        .filter { it.bookId == id }.sortedBy { it.startedAt }.map { listOf(it.page, it.seconds) }

    @Test
    fun appPutAwayWhileThePageBeforeIsWrittenDownEndsThePageShown() {
        var now = 1_700_000_000_000L
        ReaderViewModel.clock = { now }
        val writes = HeldLibrary().also(held::add)
        val model = opened(writes = writes)
        val id = model.state.value.book!!.id
        runBlocking { library.onServer(id, 7, 70) }

        now += 42_000
        model.forward(1)
        untilTrue("the first page is counted") { readsOf(id) == listOf(listOf(1, 42)) }

        // The next page is shown, and the app is put away before its place and the page before are written down.
        writes.hold()
        now += 20_000
        model.forward(1)
        now += 30_000
        onMain { model.paused() }
        writes.letThrough()
        val counted = listOf(listOf(1, 42), listOf(2, 20), listOf(3, 30))
        untilTrue("the page left and the page put away are counted") { readsOf(id) == counted }

        now += 600_000
        onMain { model.resumed() }
        now += 10_000
        model.forward(1)
        untilTrue("the page after the return is counted") { readsOf(id).size == 4 }
        assertEquals("the time away is not time read", counted + listOf(listOf(3, 10)), readsOf(id))
    }

    @Test
    fun pagesTurnWhileThePlacesAreStillBeingWrittenDown() {
        val writes = HeldLibrary().also(held::add)
        val model = opened(writes = writes)
        val id = model.state.value.book!!.id

        writes.hold()
        model.forward(3)
        val last = model.state.value.page!!
        writes.letThrough()

        assertEquals(3, model.shown())
        untilTrue("the place of the page shown last is kept") { runBlocking { library.book(id) }!!.xpointer == last.xpointer }
    }

    @Test
    fun whatWasReadIsWrittenDownThoughTheModelIsGoneMeanwhile() {
        var now = 1_700_000_000_000L
        ReaderViewModel.clock = { now }
        val writes = HeldLibrary().also(held::add)
        val model = opened(writes = writes)
        val id = model.state.value.book!!.id
        runBlocking { library.onServer(id, 7, 70) }

        writes.hold()
        now += 42_000
        model.forward(1)
        now += 30_000
        onMain { model.paused() }
        // The screen goes away, and its model with it, before anything is written.
        onMain { stores.forEach(ViewModelStore::clear) }
        writes.letThrough()

        untilTrue("both pages read are counted") { readsOf(id) == listOf(listOf(1, 42), listOf(2, 30)) }
        val turnedTo = model.state.value.page!!.xpointer
        untilTrue("the place turned to is kept") { runBlocking { library.book(id) }!!.xpointer == turnedTo }
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val WIDTH = 1080
        const val HEIGHT = 2280
        const val DENSITY = 2.625f
        const val DENSITY_DPI = 420
    }
}
