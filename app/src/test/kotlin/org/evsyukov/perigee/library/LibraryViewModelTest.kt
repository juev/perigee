package org.evsyukov.perigee.library

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.FakeAndroidKeyStore
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.FoundBook
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LibraryPreferences
import org.evsyukov.perigee.data.LocalBook
import org.evsyukov.perigee.data.ReadingStatus
import org.evsyukov.perigee.data.Shelf
import org.evsyukov.perigee.engine.BookDescription
import org.evsyukov.perigee.reader.TestBook
import org.evsyukov.perigee.sync.Answer
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.FakeOrbit
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What the library screen does, checked on its model: requirement R4.
 * The screen itself is checked on a device by LibraryScreenTest.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val library = Library.inMemory(context)
    private val source = TestFolder(File(context.cacheDir, "screen-folder"))
    private val scansDone = AtomicInteger()
    private val changes = AtomicInteger()

    /** A stand-in for the server, started only by the tests that sign in to it. */
    private val server = lazy { FakeOrbit() }
    private val accounts = Accounts.of(library)
    private val storeScope = CoroutineScope(Dispatchers.IO + Job())
    private val preferences = LibraryPreferences.inFile(File(context.filesDir, "library.preferences_pb"), storeScope)
    private val orbit = BookOrbit(accounts, library, "0.0.1")

    /** Set to hold the reading of every file after the first, as of a large folder. */
    private var hold: CountDownLatch? = null
    private val described = AtomicInteger()

    /** A file as the engine would describe it, titled by its name; the engine itself does not run on the JVM. */
    private val describe: Describe = { _, _, name ->
        if (described.incrementAndGet() > 1) hold?.await()
        BookDescription(name.substringBefore('.'), listOf("Test Author"), "en", "", "", "", null)
    }

    private val covers = Covers(File(context.cacheDir, "screen-covers"))

    private val model by lazy {
        LibraryViewModel(
            context, library, preferences, covers,
            afterChange = { changes.incrementAndGet() }, orbit = orbit, accounts = accounts,
            scans = InProcessScans(LibraryScan(context, library, covers, describe, afterScan = { scansDone.incrementAndGet() }) { source }),
        )
    }

    @Before
    fun keystore() = FakeAndroidKeyStore.install()

    @After
    fun stopServer() {
        if (server.isInitialized()) server.value.close()
        storeScope.cancel()
    }

    /** Lets the model's work on the main thread run until [done] holds of its state. */
    private fun until(what: String, done: (LibraryState) -> Boolean): LibraryState {
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val state = model.state.value
            if (done(state)) return state
            if (System.currentTimeMillis() > deadline) throw AssertionError("$what; the state is $state")
            Thread.sleep(5)
        }
    }

    /** Waits for something that is not the model's state, letting the model work meanwhile. */
    private fun untilTrue(what: String, done: () -> Boolean) = until(what) { done() }

    private val LibraryState.titles: List<String> get() = (listing as? Listing.Books)?.books?.map { it.title }.orEmpty()

    /** The book as the model shows it now. */
    private fun shown(title: String): Book =
        until("$title is shown") { state -> title in state.titles }.let { state ->
            (state.listing as Listing.Books).books.single { it.title == title }
        }

    private fun stored(id: String) = runBlocking { library.book(id) }

    /**
     * Books as a scan would have left them; the files are not needed to list
     * them. The folder is out of reach, or the look through it that every
     * start makes would find the books gone.
     */
    private fun shelve(vararg books: FoundBook) = runBlocking {
        source.gone = true
        val folder = library.addFolder(source.uriOf(source.dir), "Books")
        for (book in books) library.found(folder.id, book)
    }

    private fun found(title: String, author: String = "") = FoundBook(
        id = title, uri = "file:///nowhere/$title.fb2", fileSize = 1, fileModified = 1, format = "fb2", title = title,
        authors = listOfNotNull(author.ifEmpty { null }), language = "en", series = "", seriesNumber = "", hasCover = false,
    )

    /** A book with a file of its own in the app, as one that was downloaded. */
    private fun keep(title: String, format: String = "fb2"): String = runBlocking {
        val file = TestBook.write(File(context.cacheDir, "$title.$format"), title)
        library.keep(file, LocalBook(FileHash.partialMd5(file), format, title, listOf("Test Author"), "en", "", "", false)).id
    }

    /** Signs in to the stand-in server and asks it about the books there are, as a start of the app would have. */
    private fun signIn() = runBlocking<Unit> {
        orbit.signIn(server.value.url, "reader", "right-password")
        orbit.matchLibrary(all = true)
    }

    /** Signed in, and the server has said what it takes. */
    private fun serverAsked() = until("the server is asked what it takes") { it.signedIn && it.upload != null }

    @Test
    fun bookIsSentToTheServer() {
        val id = keep("Sent Book")
        signIn()
        serverAsked()
        assertTrue("a book the server has not is offered to be sent", model.state.value.canSend(shown("Sent Book")))

        model.send(shown("Sent Book"))

        untilTrue("the book is on the server") { stored(id)?.serverFileId != null }
        val sent = server.value.uploads.single()
        assertEquals("Sent Book.fb2", sent.name)
        assertEquals("the one library there is, without a question", 1L, sent.libraryId)
        assertEquals(sent.bookId, stored(id)?.serverBookId)
        untilTrue("the change is told") { changes.get() == 1 }
        // The list shows the book on the server a moment after the library has it there.
        val after = until("the book is shown on the server") { state ->
            id !in state.sending && (state.listing as? Listing.Books)?.books?.singleOrNull { it.id == id }?.serverFileId != null
        }
        val book = (after.listing as Listing.Books).books.single { it.id == id }
        assertFalse("what the server has is not offered to be sent", after.canSend(book))
    }

    @Test
    fun bookInAnArchiveIsSentAsTheBookInside() {
        val plain = TestBook.write(File(context.cacheDir, "Archived Book.fb2"), "Archived Book")
        val inside = plain.readBytes()
        TestFolder.zip(File(source.dir, "archived.fb2.zip"), plain)
        runBlocking { library.addFolder(source.uriOf(source.dir), "Books") }
        val book = shown("archived")
        signIn()
        serverAsked()

        model.send(shown("archived"))

        untilTrue("the book is on the server") { stored(book.id)?.serverFileId != null }
        val sent = server.value.uploads.single()
        assertEquals("the server takes no archives: it is sent the book", "archived.fb2", sent.name)
        assertArrayEquals(inside, sent.bytes)
    }

    @Test
    fun sendingIsOfferedOnlyForWhatTheServerTakesFromTheReader() {
        keep("Fb2 Book")
        keep("Text Book", format = "txt")
        // Signed in nowhere: nothing is said of a server, and nothing is offered.
        val alone = until("the books are shown") { "Fb2 Book" in it.titles && it.loaded }
        assertFalse(alone.signedIn)
        assertFalse(alone.canSend(shown("Fb2 Book")))

        signIn()
        val state = serverAsked()
        assertFalse("the server takes no text files", state.canSend(shown("Text Book")))
        assertTrue(state.canSend(shown("Fb2 Book")))
    }

    @Test
    fun readerWhoMayNotAddBooksIsNotOfferedTo() {
        keep("Fb2 Book")
        server.value.uploadAllowed = false
        signIn()
        assertFalse(serverAsked().canSend(shown("Fb2 Book")))
    }

    @Test
    fun bookLikeOneOnTheServerIsAskedAboutBeforeItIsSent() {
        keep("Another Edition")
        server.value.books = JSONArray(
            """[{"id":7,"title":"another  EDITION","authors":["Author Test"],"seriesName":null,"seriesIndex":null,"hasCover":false,
                "files":[{"id":70,"format":"epub","role":"primary","sizeBytes":100}]}]""",
        )
        signIn()
        serverAsked()
        val book = shown("Another Edition")
        assertTrue("the book is known to be like one on the server", book.serverLikeId != null)

        model.send(book)
        assertEquals(SendQuestion.Alike(book), until("the reader is asked") { it.question != null }.question)
        model.dontSend()
        until("the question is put away") { it.question == null }
        assertTrue("the reader thought better of it", server.value.uploads.isEmpty())

        model.send(book)
        until("the reader is asked") { it.question is SendQuestion.Alike }
        model.sendAnyway(book)
        untilTrue("the book is sent") { server.value.uploads.size == 1 }
    }

    @Test
    fun whereToSendIsAskedOnceAndRemembered() {
        keep("First Book")
        keep("Second Book")
        server.value.libraries = JSONArray(
            """[{"id":1,"name":"Fiction","allowedFormats":[],"organizationMode":"book_per_file","folders":[{"id":10,"name":"books"}]},
                {"id":2,"name":"Shared","allowedFormats":[],"organizationMode":"book_per_folder",
                 "folders":[{"id":20,"name":"new"},{"id":21,"name":"old"}]},
                {"id":3,"name":"Comics","allowedFormats":["cbz"],"organizationMode":"book_per_file","folders":[{"id":30,"name":"comics"}]}]""",
        )
        signIn()
        serverAsked()

        model.send(shown("First Book"))
        val where = until("the reader is asked where to") { it.question is SendQuestion.Where }.question as SendQuestion.Where
        assertEquals(
            "a library that does not take the format is not offered; one folder is not named",
            listOf("Fiction" to "", "Shared" to "new", "Shared" to "old"),
            where.places.map { it.library to it.folder },
        )
        model.sendTo(where.book, where.places.single { it.folder == "old" }.place)

        untilTrue("the book is sent") { server.value.uploads.size == 1 }
        assertEquals(2L, server.value.uploads[0].libraryId)
        assertEquals(21L, server.value.uploads[0].folderId)

        model.send(shown("Second Book"))
        untilTrue("the second book is sent") { server.value.uploads.size == 2 }
        assertEquals("the same place, without a question", 21L, server.value.uploads[1].folderId)
        assertNull(model.state.value.question)
    }

    @Test
    fun bookTheServerDoesNotTakeSaysWhyAndCanBeSentAgain() {
        val id = keep("Refused Book")
        signIn()
        server.value.uploadAnswer = Answer(
            409, """{"statusCode":409,"message":"A file named \"Refused Book.fb2\" already exists","errorCode":"UPLOAD_DESTINATION_CONFLICT"}""",
        )
        serverAsked()

        model.send(shown("Refused Book"))
        val refused = until("the book is refused") { it.sending[id] is Sending.Failed }
        assertEquals(Sending.Failed(SendFailure.NameTaken), refused.sending[id])
        assertNull(stored(id)?.serverFileId)

        server.value.uploadAnswer = null
        model.send(shown("Refused Book"))
        untilTrue("the book is on the server") { stored(id)?.serverFileId != null }
        until("the refusal is forgotten") { id !in it.sending }
    }

    @Test
    fun serverThatDoesNotAnswerIsSaidAtTheBook() {
        val id = keep("Unlucky Book")
        signIn()
        // Asked what it takes while it answered; gone by the time the book is sent.
        assertTrue(serverAsked().canSend(shown("Unlucky Book")))
        server.value.close()

        model.send(shown("Unlucky Book"))
        val state = until("the failure is told") { it.sending[id] is Sending.Failed }
        assertEquals(Sending.Failed(SendFailure.Unreachable), state.sending[id])
    }

    @Test
    fun bookIsGivenStarsAndTheyAreTakenOff() {
        shelve(found("Rated Book"))
        model.setRating(shown("Rated Book"), 4)
        untilTrue("the rating is kept") { stored("Rated Book")?.rating == 4 }
        untilTrue("the change is told") { changes.get() == 1 }
        assertTrue("to be told to the server", stored("Rated Book")!!.stateUnsent)

        // The same number of stars again takes them off.
        until("the rating is shown") { state -> (state.listing as? Listing.Books)?.books?.singleOrNull()?.rating == 4 }
        model.setRating(shown("Rated Book"), 4)
        untilTrue("the rating is taken off") { stored("Rated Book")?.rating == null }
        untilTrue("the change is told") { changes.get() == 2 }
    }

    @Test
    fun foldersAreLookedThroughAtTheStartAndWhenAskedAndTheServerIsAskedAfter() {
        until("the first look is over") { it.loaded && !it.scanning }
        untilTrue("the server is asked about the books found") { scansDone.get() >= 1 }
        model.rescan()
        untilTrue("the server is asked again") { scansDone.get() >= 2 }
    }

    @Test
    fun lookUnderWayTellsHowManyBooksItFound() {
        hold = CountDownLatch(1)
        TestBook.write(File(source.dir, "a.fb2"), "First Found")
        TestBook.write(File(source.dir, "b.fb2"), "Second Found")
        runBlocking { library.addFolder(source.uriOf(source.dir), "Books") }

        // A book found is shown before the look is over.
        until("one book is found and shown while the next one is being read") { it.scanning && it.found == 1 && it.titles.size == 1 }

        hold?.countDown()
        val over = until("the look is over") { !it.scanning && it.titles.size == 2 }
        assertEquals(0, over.found)
    }

    @Test
    fun gridOrListIsKept() {
        until("the library is read") { it.loaded }
        model.setGrid(true)
        until("the grid is shown") { it.grid }
        assertTrue(runBlocking { preferences.prefs.first() }.grid)
        model.setGrid(false)
        until("the list is shown") { !it.grid }
        assertFalse(runBlocking { preferences.prefs.first() }.grid)
    }

    @Test
    fun favouritesAndStatusPutBooksOnShelves() {
        shelve(found("Loved"), found("Planned"), found("Plain"))
        model.setFavorite(shown("Loved"), true)
        model.setStatus(shown("Planned"), ReadingStatus.ToRead)

        model.setShelf(Shelf.Favorites)
        assertEquals(listOf("Loved"), until("the favourites are shown") { it.shelf == Shelf.Favorites && it.titles == listOf("Loved") }.titles)

        model.setShelf(Shelf.ToRead)
        until("the books to read are shown") { it.shelf == Shelf.ToRead && it.titles == listOf("Planned") }

        model.setShelf(Shelf.Read)
        until("nothing is read yet") { it.shelf == Shelf.Read && it.titles.isEmpty() }

        // Choosing the same status again takes it off.
        model.setShelf(Shelf.ToRead)
        model.setStatus(shown("Planned"), ReadingStatus.ToRead)
        until("the shelf is empty") { it.shelf == Shelf.ToRead && it.titles.isEmpty() }
        assertEquals(ReadingStatus.None, stored("Planned")!!.status)
    }

    @Test
    fun searchNarrowsTheShelfAndAnEmptyOneBringsEveryBookBack() {
        shelve(found("Dune", author = "Frank Herbert"), found("Emma", author = "Jane Austen"))
        shown("Dune")

        model.setQuery("austen")
        until("the search narrows the shelf") { it.titles == listOf("Emma") }

        model.setQuery("")
        until("every book is back") { it.titles.toSet() == setOf("Dune", "Emma") }
    }

    @Test
    fun emptyShelfSaysWhatPutsBooksOnIt() {
        val resources = context.resources
        assertEquals(resources.getString(R.string.shelf_empty_favorites), shelfEmptyHint(resources, Shelf.Favorites, inGroup = false))
        assertEquals(
            resources.getString(R.string.shelf_empty_status, resources.getString(R.string.shelf_read)),
            shelfEmptyHint(resources, Shelf.Read, inGroup = false),
        )
        assertEquals(resources.getString(R.string.shelf_empty_series), shelfEmptyHint(resources, Shelf.Series, inGroup = false))
        assertNull("inside a group the shelf has nothing to say", shelfEmptyHint(resources, Shelf.Favorites, inGroup = true))
        assertNull(shelfEmptyHint(resources, Shelf.All, inGroup = false))
    }

    @Test
    fun authorsShelfLeadsToTheBooksOfAnAuthor() {
        shelve(found("Dune", author = "Frank Herbert"), found("Emma", author = "Jane Austen"), found("Persuasion", author = "Jane Austen"))
        shown("Dune")

        model.setShelf(Shelf.Authors)
        val groups = until("the authors are shown") { it.listing is Listing.Groups }.listing as Listing.Groups
        val austen = groups.groups.single { it.name == "Jane Austen" }
        assertEquals(2, austen.count)

        model.openGroup(austen)
        until("her books are shown") { it.titles.toSet() == setOf("Emma", "Persuasion") }

        model.openGroup(null)
        until("the authors are back") { (it.listing as? Listing.Groups)?.groups?.any { g -> g.name == "Frank Herbert" } == true }
    }

    @Test
    fun bookIsPutIntoANewCollection() {
        shelve(found("Dune"), found("Emma"))
        model.createCollection("Travel", shown("Dune"))

        model.setShelf(Shelf.Collections)
        // The collection is made first, and the book put into it after.
        val groups = until("the collection is shown with the book") {
            (it.listing as? Listing.Groups)?.groups?.singleOrNull()?.count == 1
        }.listing as Listing.Groups
        val travel = groups.groups.single()
        assertEquals("Travel", travel.name)

        model.openGroup(travel)
        until("its books are shown") { it.titles == listOf("Dune") }
    }

    @Test
    fun collectionIsRenamedAndRemoved() {
        shelve(found("Dune"))
        val travel = runBlocking { library.collection("Travel")!!.also { library.addToCollection(it.id, "Dune") } }
        model.setShelf(Shelf.Collections)
        until("the collection is shown") { it.collections.any { c -> c.name == "Travel" } }

        model.renameCollection(travel.id, "Travel far")
        until("the new name is shown") { it.collections.map { c -> c.name } == listOf("Travel far") }

        model.removeCollection(travel.id)
        until("the collection is gone") { it.loaded && it.collections.isEmpty() }
        assertEquals("the book stays in the library", 1, runBlocking { library.books().first().size })
    }

    @Test
    fun foldersRemovedOneAfterAnotherAreAllRemoved() {
        TestBook.write(File(source.dir, "a.fb2"), "Found Book")
        val folders = runBlocking {
            listOf(library.addFolder(source.uriOf(source.dir), "First"), library.addFolder("file:///nowhere", "Second"))
        }

        // Each request stops the look before it; none may be dropped on the way.
        folders.forEach(model::removeFolder)
        model.rescan()

        untilTrue("the folders are gone") { runBlocking { library.folders().first().isEmpty() } }
        until("the library is empty") { it.loaded && !it.scanning && it.empty }
    }

    @Test
    fun bookWhoseFileIsGoneHasNoFileToOpen() {
        shelve(found("Ghost"))
        assertNull(runBlocking { model.fileOf(shown("Ghost")) })
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
