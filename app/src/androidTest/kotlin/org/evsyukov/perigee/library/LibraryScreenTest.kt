package org.evsyukov.perigee.library

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.FoundBook
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LibraryPreferences
import org.evsyukov.perigee.data.LibraryPrefs
import org.evsyukov.perigee.data.LocalBook
import org.evsyukov.perigee.data.ReadingStatus
import org.evsyukov.perigee.reader.TestBook
import org.evsyukov.perigee.sync.Answer
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.FakeOrbit
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The library as the reader uses it: requirement R4. */
@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val activity get() = rule.activity
    private val library by lazy { Library.inMemory(activity) }
    private val source by lazy { TestFolder(File(activity.cacheDir, "screen-folder")) }
    private var opened: File? = null
    private var askedForFile = false

    @Before
    fun defaultShelfAndSort() {
        runBlocking { LibraryPreferences.get(activity).update { LibraryPrefs() } }
    }

    private fun string(id: Int, vararg args: Any) = activity.getString(id, *args)

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

    private fun found(
        title: String, author: String = "", series: String = "", number: String = "",
    ) = FoundBook(
        id = title, uri = "file:///nowhere/$title.fb2", fileSize = 1, fileModified = 1, format = "fb2", title = title,
        authors = listOfNotNull(author.ifEmpty { null }), language = "en", series = series, seriesNumber = number,
        hasCover = false,
    )

    private var wentToServer = false

    private var wentToLicenses = false
    private val scansDone = java.util.concurrent.atomic.AtomicInteger()
    private val changes = java.util.concurrent.atomic.AtomicInteger()

    /** A stand-in for the server, started only by the tests that sign in to it. */
    private val server = lazy { FakeOrbit() }
    private val accounts by lazy { Accounts.of(library) }
    private val orbit by lazy { BookOrbit(accounts, library, "0.0.1") }

    @After
    fun stopServer() {
        if (server.isInitialized()) server.value.close()
    }

    /** Signs in to the stand-in server and asks it about the books there are, as a start of the app would have. */
    private fun signIn() = runBlocking<Unit> {
        orbit.signIn(server.value.url, "reader", "right-password")
        orbit.matchLibrary(all = true)
    }

    /** A book with a file of its own in the app, as one that was downloaded. */
    private fun keep(title: String, format: String = "fb2"): String = runBlocking {
        val file = TestBook.write(File(activity.cacheDir, "$title.$format"), title)
        library.keep(file, LocalBook(FileHash.partialMd5(file), format, title, listOf("Test Author"), "en", "", "", false)).id
    }

    /** A menu is a window of its own: the Back key closes it, the activity's own way back does not reach it. */
    private fun closeMenu() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private fun sendFromMenu(title: String) {
        rule.onNodeWithContentDescription(string(R.string.book_actions, title)).performClick()
        inMenu(R.string.action_send_to_server).performClick()
    }

    private fun show() {
        val model = LibraryViewModel(
            activity.application, library, LibraryPreferences.get(activity),
            Covers(File(activity.cacheDir, "screen-covers")), afterScan = { scansDone.incrementAndGet() },
            afterChange = { changes.incrementAndGet() },
            orbit = orbit, accounts = accounts,
        ) { source }
        rule.setContent {
            MaterialTheme {
                LibraryScreen(
                    onOpen = { opened = it }, onOpenFile = { askedForFile = true }, onCatalogs = {},
                    onServer = { wentToServer = true }, onLicenses = { wentToLicenses = true }, model = model,
                )
            }
        }
    }

    private fun waitForText(text: String) =
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForNoText(text: String) =
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    private fun shelf(name: Int) {
        rule.onNodeWithTag("shelves").performScrollToNode(hasText(string(name)))
        rule.onNodeWithText(string(name)).performClick()
    }

    private fun back() = rule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }

    private fun inMenu(text: Int) = rule.onNode(hasText(string(text)) and hasAnyAncestor(isPopup()))

    @Test
    fun emptyLibraryOffersAFolderOrAFile() {
        show()
        waitForText(string(R.string.library_empty))
        rule.onNodeWithText(string(R.string.action_add_folder)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.open_book)).performClick()
        assertTrue(askedForFile)
    }

    @Test
    fun booksAreListedWithAuthorAndSeries() {
        shelve(found("Second Book", author = "Anna Smith", series = "Long Saga", number = "2"), found("Plain Book"))
        show()
        waitForText("Second Book")
        rule.onNodeWithText("Anna Smith").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.series_line, "Long Saga", "2")).assertIsDisplayed()
        rule.onNodeWithText("Plain Book").assertIsDisplayed()
    }

    @Test
    fun bookTheServerHasIsMarked() {
        shelve(found("Synced Book"), found("Local Book"))
        runBlocking { library.onServer("Synced Book", serverBookId = 7, serverFileId = 70) }
        show()
        waitForText("Synced Book")
        rule.onNodeWithText("FB2 · ${string(R.string.book_on_server)}").assertIsDisplayed()
        rule.onNodeWithText("FB2").assertIsDisplayed()
    }

    @Test
    fun bookIsSentToTheServerFromItsMenu() {
        val id = keep("Sent Book")
        signIn()
        show()
        waitForText("FB2 · ${string(R.string.book_not_on_server)}")

        sendFromMenu("Sent Book")

        waitForText("FB2 · ${string(R.string.book_on_server)}")
        val sent = server.value.uploads.single()
        assertEquals("Sent Book.fb2", sent.name)
        assertEquals("the one library there is, without a question", 1L, sent.libraryId)
        assertEquals(sent.bookId, runBlocking { library.book(id) }?.serverBookId)
        rule.waitUntil(TIMEOUT) { changes.get() == 1 }
        // What the server has is not offered to be sent.
        rule.onNodeWithContentDescription(string(R.string.book_actions, "Sent Book")).performClick()
        inMenu(R.string.action_collections).assertIsDisplayed()
        inMenu(R.string.action_send_to_server).assertDoesNotExist()
    }

    @Test
    fun bookInAnArchiveIsSentAsTheBookInside() {
        val plain = TestBook.write(File(activity.cacheDir, "Archived Book.fb2"), "Archived Book")
        val inside = plain.readBytes()
        TestFolder.zip(File(source.dir, "archived.fb2.zip"), plain)
        runBlocking { library.addFolder(source.uriOf(source.dir), "Books") }
        show()
        waitForText("Archived Book")
        signIn()
        waitForText("FB2 · ${string(R.string.book_not_on_server)}")

        sendFromMenu("Archived Book")

        waitForText("FB2 · ${string(R.string.book_on_server)}")
        val sent = server.value.uploads.single()
        assertEquals("the server takes no archives: it is sent the book", "Archived Book.fb2", sent.name)
        assertArrayEquals(inside, sent.bytes)
    }

    @Test
    fun sendingIsOfferedOnlyForWhatTheServerTakesFromTheReader() {
        keep("Fb2 Book")
        keep("Text Book", format = "txt")
        show()
        waitForText("Fb2 Book")
        // Signed in nowhere: nothing is said of a server, and nothing is offered.
        rule.onNodeWithContentDescription(string(R.string.book_actions, "Fb2 Book")).performClick()
        inMenu(R.string.action_collections).assertIsDisplayed()
        inMenu(R.string.action_send_to_server).assertDoesNotExist()
        closeMenu()
        assertTrue(rule.onAllNodesWithText(string(R.string.book_not_on_server), substring = true).fetchSemanticsNodes().isEmpty())

        signIn()
        waitForText("TXT · ${string(R.string.book_not_on_server)}")
        rule.onNodeWithContentDescription(string(R.string.book_actions, "Text Book")).performClick()
        inMenu(R.string.action_collections).assertIsDisplayed()
        inMenu(R.string.action_send_to_server).assertDoesNotExist()
        closeMenu()
        rule.onNodeWithContentDescription(string(R.string.book_actions, "Fb2 Book")).performClick()
        inMenu(R.string.action_send_to_server).assertIsDisplayed()
    }

    @Test
    fun readerWhoMayNotAddBooksIsNotOfferedTo() {
        keep("Fb2 Book")
        server.value.uploadAllowed = false
        signIn()
        show()
        waitForText("FB2 · ${string(R.string.book_not_on_server)}")

        rule.onNodeWithContentDescription(string(R.string.book_actions, "Fb2 Book")).performClick()
        inMenu(R.string.action_collections).assertIsDisplayed()
        inMenu(R.string.action_send_to_server).assertDoesNotExist()
    }

    @Test
    fun bookLikeOneOnTheServerIsAskedAboutBeforeItIsSent() {
        keep("Another Edition")
        server.value.books = JSONArray(
            """[{"id":7,"title":"another  EDITION","authors":["Author Test"],"seriesName":null,"seriesIndex":null,"hasCover":false,
                "files":[{"id":70,"format":"epub","role":"primary","sizeBytes":100}]}]""",
        )
        signIn()
        show()
        waitForText("FB2 · ${string(R.string.book_like_on_server)}")

        sendFromMenu("Another Edition")
        waitForText(string(R.string.send_alike_text, "another  EDITION — Author Test"))
        rule.onNodeWithText(string(R.string.action_cancel)).performClick()
        waitForNoText(string(R.string.send_alike_title))
        assertTrue("the reader thought better of it", server.value.uploads.isEmpty())

        sendFromMenu("Another Edition")
        waitForText(string(R.string.send_alike_title))
        rule.onNodeWithText(string(R.string.send_anyway)).performClick()

        waitForText("FB2 · ${string(R.string.book_on_server)}")
        assertEquals(1, server.value.uploads.size)
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
        show()
        waitForText("First Book")

        sendFromMenu("First Book")
        waitForText(string(R.string.send_where_title))
        rule.onNodeWithText("Fiction").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.send_place_folder, "Shared", "new")).assertIsDisplayed()
        assertTrue("a library that does not take the format is not offered", rule.onAllNodesWithText("Comics").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText(string(R.string.send_place_folder, "Shared", "old")).performClick()

        rule.waitUntil(TIMEOUT) { server.value.uploads.size == 1 }
        assertEquals(2L, server.value.uploads[0].libraryId)
        assertEquals(21L, server.value.uploads[0].folderId)

        sendFromMenu("Second Book")
        rule.waitUntil(TIMEOUT) { server.value.uploads.size == 2 }
        assertEquals("the same place, without a question", 21L, server.value.uploads[1].folderId)
        assertTrue(rule.onAllNodesWithText(string(R.string.send_where_title)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun bookTheServerDoesNotTakeSaysWhyAndCanBeSentAgain() {
        val id = keep("Refused Book")
        signIn()
        server.value.uploadAnswer = Answer(
            409, """{"statusCode":409,"message":"A file named \"Refused Book.fb2\" already exists","errorCode":"UPLOAD_DESTINATION_CONFLICT"}""",
        )
        show()
        waitForText("Refused Book")

        sendFromMenu("Refused Book")

        waitForText(string(R.string.send_failed_name_taken))
        assertEquals(null, runBlocking { library.book(id) }?.serverFileId)

        server.value.uploadAnswer = null
        sendFromMenu("Refused Book")
        waitForText("FB2 · ${string(R.string.book_on_server)}")
        waitForNoText(string(R.string.send_failed_name_taken))
    }

    @Test
    fun serverThatDoesNotAnswerIsSaidAtTheBook() {
        keep("Unlucky Book")
        signIn()
        show()
        waitForText("FB2 · ${string(R.string.book_not_on_server)}")
        // Asked what it takes while it answered; gone by the time the book is sent.
        rule.onNodeWithContentDescription(string(R.string.book_actions, "Unlucky Book")).performClick()
        inMenu(R.string.action_send_to_server).assertIsDisplayed()
        server.value.close()
        inMenu(R.string.action_send_to_server).performClick()

        waitForText(string(R.string.send_failed_unreachable))
    }

    @Test
    fun bookIsGivenStarsAndTheyAreTakenOff() {
        shelve(found("Rated Book"))
        show()
        waitForText("Rated Book")

        rule.onNodeWithContentDescription(string(R.string.book_actions, "Rated Book")).performClick()
        rule.onNodeWithContentDescription(activity.resources.getQuantityString(R.plurals.rate_stars, 4, 4)).performClick()
        rule.waitUntil(TIMEOUT) { runBlocking { library.book("Rated Book") }?.rating == 4 }
        waitForText("FB2 · ★★★★")
        rule.waitUntil(TIMEOUT) { changes.get() == 1 }
        assertTrue("to be told to the server", runBlocking { library.book("Rated Book") }!!.stateUnsent)

        // The same number of stars again takes them off.
        rule.onNodeWithContentDescription(activity.resources.getQuantityString(R.plurals.rate_stars, 4, 4)).performClick()
        rule.waitUntil(TIMEOUT) { runBlocking { library.book("Rated Book") }?.rating == null }
        rule.waitUntil(TIMEOUT) { changes.get() == 2 }
    }

    @Test
    fun serverIsReachedFromTheMenuAndAskedAboutBooksAfterALook() {
        show()
        rule.waitUntil(TIMEOUT) { scansDone.get() >= 1 }
        rule.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        rule.onNodeWithText(string(R.string.action_rescan)).performClick()
        rule.waitUntil(TIMEOUT) { scansDone.get() >= 2 }

        rule.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        rule.onNodeWithText(string(R.string.action_server)).performClick()
        assertTrue(wentToServer)
    }

    @Test
    fun booksAreShownAsAGridOfCoversOrAListAndTheChoiceIsKept() {
        shelve(found("Gridded", author = "Grid Author"), found("Other"))
        show()
        waitForText("Gridded")
        rule.onNodeWithTag("books").assertExists()

        rule.onNodeWithContentDescription(string(R.string.action_view_grid)).performClick()
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithTag("books-grid").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Grid Author").assertExists()
        assertTrue(rule.onAllNodesWithTag("books").fetchSemanticsNodes().isEmpty())
        rule.waitUntil(TIMEOUT) { runBlocking { LibraryPreferences.get(activity).prefs.first() }.grid }

        // A book of the grid has no heart beside it: the mark is in its menu, with the rest.
        rule.onNodeWithContentDescription(string(R.string.book_actions, "Gridded")).performClick()
        inMenu(R.string.shelf_favorites).performClick()
        rule.waitUntil(TIMEOUT) { runBlocking { library.book("Gridded") }?.favorite == true }
        rule.onNodeWithContentDescription(string(R.string.book_actions, "Gridded")).performClick()
        inMenu(R.string.shelf_read).performClick()
        rule.waitUntil(TIMEOUT) { runBlocking { library.book("Gridded") }?.status == ReadingStatus.Read }

        rule.onNodeWithContentDescription(string(R.string.action_view_list)).performClick()
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithTag("books").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(string(R.string.favorite_remove, "Gridded")).assertExists()
        rule.waitUntil(TIMEOUT) { !runBlocking { LibraryPreferences.get(activity).prefs.first() }.grid }
    }

    @Test
    fun licensesAreReachedFromTheMenu() {
        show()
        rule.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        rule.onNodeWithText(string(R.string.action_licenses)).performClick()
        assertTrue(wentToLicenses)
    }

    @Test
    fun favouritesAndStatusPutBooksOnShelves() {
        shelve(found("Loved"), found("Planned"), found("Plain"))
        show()
        waitForText("Loved")

        rule.onNodeWithContentDescription(string(R.string.favorite_add, "Loved")).performClick()
        rule.onNodeWithContentDescription(string(R.string.book_actions, "Planned")).performClick()
        inMenu(R.string.shelf_to_read).performClick()

        shelf(R.string.shelf_favorites)
        waitForNoText("Planned")
        rule.onNodeWithText("Loved").assertIsDisplayed()

        shelf(R.string.shelf_to_read)
        waitForNoText("Loved")
        rule.onNodeWithText("Planned").assertIsDisplayed()

        shelf(R.string.shelf_read)
        waitForText(string(R.string.shelf_empty))

        // Choosing the same status again takes it off.
        shelf(R.string.shelf_to_read)
        waitForText("Planned")
        rule.onNodeWithContentDescription(string(R.string.book_actions, "Planned")).performClick()
        inMenu(R.string.shelf_to_read).performClick()
        waitForNoText("Planned")
        assertEquals(ReadingStatus.None, runBlocking { library.book("Planned")!!.status })
    }

    @Test
    fun searchNarrowsTheShelf() {
        shelve(found("Dune", author = "Frank Herbert"), found("Emma", author = "Jane Austen"))
        show()
        waitForText("Dune")

        rule.onNodeWithContentDescription(string(R.string.action_search)).performClick()
        rule.onNodeWithTag("library-search").performTextInput("austen")
        waitForNoText("Dune")
        rule.onNodeWithText("Emma").assertIsDisplayed()

        // Back leaves the search and brings every book back.
        back()
        waitForText("Dune")
    }

    @Test
    fun authorsShelfLeadsToTheBooksOfAnAuthor() {
        shelve(found("Dune", author = "Frank Herbert"), found("Emma", author = "Jane Austen"), found("Persuasion", author = "Jane Austen"))
        show()
        waitForText("Dune")

        shelf(R.string.shelf_authors)
        waitForText("Jane Austen")
        rule.onNodeWithText(activity.resources.getQuantityString(R.plurals.books_count, 2, 2)).assertIsDisplayed()
        rule.onNodeWithText("Jane Austen").performClick()
        waitForText("Persuasion")
        rule.onNodeWithText("Emma").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Dune").fetchSemanticsNodes().isEmpty())

        back()
        waitForText("Frank Herbert")
    }

    @Test
    fun bookIsPutIntoANewCollection() {
        shelve(found("Dune"), found("Emma"))
        show()
        waitForText("Dune")

        rule.onNodeWithContentDescription(string(R.string.book_actions, "Dune")).performClick()
        inMenu(R.string.action_collections).performClick()
        rule.onNodeWithTag("collection-name").performTextInput("Travel")
        rule.onNodeWithContentDescription(string(R.string.action_create)).performClick()
        waitForText("Travel")
        rule.onNodeWithText(string(R.string.action_done)).performClick()

        shelf(R.string.shelf_collections)
        waitForText(activity.resources.getQuantityString(R.plurals.books_count, 1, 1))
        rule.onNodeWithText("Travel").performClick()
        waitForText("Dune")
        assertTrue(rule.onAllNodesWithText("Emma").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun collectionIsRenamedAndRemoved() {
        shelve(found("Dune"))
        runBlocking { library.addToCollection(library.collection("Travel")!!.id, "Dune") }
        show()
        waitForText("Dune")
        shelf(R.string.shelf_collections)
        waitForText("Travel")

        rule.onNodeWithContentDescription(string(R.string.collection_actions, "Travel")).performClick()
        inMenu(R.string.action_rename).performClick()
        rule.onNodeWithTag("collection-name").performTextInput(" far")
        rule.onNodeWithText(string(R.string.action_save)).performClick()
        waitForText("Travel far")

        rule.onNodeWithContentDescription(string(R.string.collection_actions, "Travel far")).performClick()
        inMenu(R.string.action_delete).performClick()
        waitForNoText("Travel far")
        assertEquals("the book stays in the library", 1, runBlocking { library.books().first().size })
    }

    @Test
    fun folderIsLookedThroughAndAPickedBookOpensFromACopy() {
        val file = TestBook.write(
            File(source.dir, "saga.fb2"), "Found Book", author = "Anna Smith", cover = TestFolder.cover(Color.RED),
        )
        runBlocking { library.addFolder(source.uriOf(source.dir), "Books") }
        show()

        waitForText("Found Book")
        rule.onNodeWithText("Found Book").performClick()
        rule.waitUntil(TIMEOUT) { opened != null }

        val copy = opened!!
        assertTrue("the copy is in the app's storage", copy.path.startsWith(activity.filesDir.path))
        assertArrayEquals(file.readBytes(), copy.readBytes())
        assertEquals(copy.path, runBlocking { library.books().first().single().path })
    }

    @Test
    fun foldersRemovedOneAfterAnotherAreAllRemoved() {
        TestBook.write(File(source.dir, "a.fb2"), "Found Book")
        val folders = runBlocking {
            listOf(library.addFolder(source.uriOf(source.dir), "First"), library.addFolder("file:///nowhere", "Second"))
        }
        val model = LibraryViewModel(
            activity.application, library, LibraryPreferences.get(activity),
            Covers(File(activity.cacheDir, "screen-covers")),
        ) { source }

        // Each request stops the look before it; none may be dropped on the way.
        folders.forEach(model::removeFolder)
        model.rescan()

        rule.waitUntil(TIMEOUT) { runBlocking { library.folders().first().isEmpty() } }
        rule.waitUntil(TIMEOUT) { model.state.value.let { it.loaded && !it.scanning && it.empty } }
    }

    @Test
    fun bookWhoseFileIsGoneSaysSo() {
        shelve(found("Ghost"))
        show()
        waitForText("Ghost")
        rule.onNodeWithText("Ghost").performClick()
        waitForText(string(R.string.book_unavailable))
        assertEquals(null, opened)
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
