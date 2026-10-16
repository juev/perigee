package org.evsyukov.perigee.library

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.FakeOrbit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library's screen on a device: the empty library, a line of the list, a book's menu and its page,
 * the grid, the menu of the screen, a folder looked through by the engine. What the screen does is
 * checked on its model, by LibraryViewModelTest on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val activity get() = rule.activity
    private val library by lazy { Library.inMemory(activity) }
    private val source by lazy { TestFolder(File(activity.cacheDir, "screen-folder")) }
    private var opened: File? = null
    private var askedForFile = false
    private var wentToServer = false
    private var wentToLicenses = false

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

    private fun show() {
        val covers = Covers(File(activity.cacheDir, "screen-covers"))
        val model = LibraryViewModel(
            activity.application, library, LibraryPreferences.get(activity), covers, orbit = orbit, accounts = accounts,
            scans = InProcessScans(LibraryScan(activity, library, covers) { source }),
        )
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
    fun aLineSaysTheAuthorTheSeriesAndWhetherTheServerHasTheBook() {
        shelve(found("Second Book", author = "Anna Smith", series = "Long Saga", number = "2"), found("Local Book"))
        runBlocking { library.onServer("Second Book", serverBookId = 7, serverFileId = 70) }
        show()
        waitForText("Second Book")
        rule.onNodeWithText("Anna Smith").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.series_line, "Long Saga", "2")).assertIsDisplayed()
        rule.onNodeWithText("FB2 · ${string(R.string.book_on_server)}").assertIsDisplayed()
        rule.onNodeWithText("Local Book").assertIsDisplayed()
        rule.onNodeWithText("FB2").assertIsDisplayed()
    }

    @Test
    fun bookWithAPlaceNotSentYetSaysSo() {
        shelve(found("Read Book"))
        signIn()
        runBlocking {
            library.onServer("Read Book", serverBookId = 7, serverFileId = 70)
            library.savePosition("Read Book", "/a", 0f)
            library.savePosition("Read Book", "/b", 0.5f)
        }
        show()
        rule.waitUntil(TIMEOUT) {
            rule.onAllNodesWithText(string(R.string.book_on_server_unsent), substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun bookIsSentToTheServerFromItsMenu() {
        val id = keep("Sent Book")
        signIn()
        show()
        waitForText("FB2 · ${string(R.string.book_not_on_server)}")

        rule.onNodeWithContentDescription(string(R.string.book_actions, "Sent Book")).performClick()
        inMenu(R.string.action_send_to_server).performClick()

        waitForText("FB2 · ${string(R.string.book_on_server)}")
        assertEquals(server.value.uploads.single().bookId, runBlocking { library.book(id) }?.serverBookId)
        // What the server has is not offered to be sent.
        rule.onNodeWithContentDescription(string(R.string.book_actions, "Sent Book")).performClick()
        inMenu(R.string.action_collections).assertIsDisplayed()
        inMenu(R.string.action_send_to_server).assertDoesNotExist()
    }

    private fun onPage(matcher: SemanticsMatcher) = rule.onNode(matcher and hasAnyAncestor(hasTestTag("book-page")))

    private fun openPageOf(title: String) {
        rule.onNodeWithContentDescription(string(R.string.book_actions, title)).performClick()
        inMenu(R.string.action_about_book).performClick()
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithTag("book-page").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun reviewIsWrittenFromTheMenuAndShownOnTheBooksPage() {
        shelve(found("Reviewed"))
        show()
        waitForText("Reviewed")

        rule.onNodeWithContentDescription(string(R.string.book_actions, "Reviewed")).performClick()
        inMenu(R.string.action_review).performClick()
        rule.onNodeWithTag("review-text").performTextReplacement("Slow, then gripping.")
        rule.onNodeWithText(string(R.string.action_save)).performClick()
        rule.waitUntil(TIMEOUT) { runBlocking { library.book("Reviewed") }?.review == "Slow, then gripping." }

        openPageOf("Reviewed")
        onPage(hasText(string(R.string.review_title))).assertIsDisplayed()
        onPage(hasText("Slow, then gripping.")).assertIsDisplayed()
        onPage(hasContentDescription(string(R.string.action_back))).performClick()
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithTag("book-page").fetchSemanticsNodes().isEmpty() }

        // Emptied, it is taken off.
        rule.onNodeWithContentDescription(string(R.string.book_actions, "Reviewed")).performClick()
        inMenu(R.string.action_review_edit).performClick()
        rule.onNodeWithTag("review-text").performTextReplacement("")
        rule.onNodeWithText(string(R.string.action_save)).performClick()
        rule.waitUntil(TIMEOUT) { runBlocking { library.book("Reviewed") }?.review == null }
    }

    @Test
    fun bookIsDeletedFromTheMenuAfterAWarning() {
        val id = keep("Doomed Book")
        show()
        waitForText("Doomed Book")

        rule.onNodeWithContentDescription(string(R.string.book_actions, "Doomed Book")).performClick()
        inMenu(R.string.action_remove_book).performScrollTo().performClick()
        // The server does not have the book: the reader is told what goes with it.
        waitForText(string(R.string.remove_book_lost, "Doomed Book"))
        rule.onNodeWithText(string(R.string.action_delete)).performClick()

        rule.waitUntil(TIMEOUT) { runBlocking { library.book(id) } == null }
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithText("Doomed Book").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun bookHasAPageOfItsOwnAndIsReadFromIt() {
        keep("Kept Book")
        shelve(found("Paged", author = "Page Author", series = "Pages", number = "3").copy(description = "First of it.\nSecond of it."))
        show()
        waitForText("Paged")

        openPageOf("Paged")
        // Twice: as the title, and on the plate that stands for the cover the book has none of.
        assertEquals(2, rule.onAllNodes(hasText("Paged") and hasAnyAncestor(hasTestTag("book-page"))).fetchSemanticsNodes().size)
        onPage(hasText("Page Author")).assertIsDisplayed()
        onPage(hasText(string(R.string.series_line, "Pages", "3"))).assertIsDisplayed()
        onPage(hasText("First of it.")).assertIsDisplayed()
        onPage(hasText("Second of it.")).assertIsDisplayed()
        onPage(hasContentDescription(string(R.string.favorite_add, "Paged"))).performClick()
        rule.waitUntil(TIMEOUT) { runBlocking { library.book("Paged") }?.favorite == true }
        // The way back on the page returns to the list.
        onPage(hasContentDescription(string(R.string.action_back))).performClick()
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithTag("book-page").fetchSemanticsNodes().isEmpty() }

        openPageOf("Kept Book")
        onPage(hasText(string(R.string.book_no_description))).assertIsDisplayed()
        onPage(hasText(string(R.string.book_read))).performClick()
        rule.waitUntil(TIMEOUT) { opened != null }
        assertTrue(rule.onAllNodesWithTag("book-page").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun booksAreShownAsAGridOfCoversWithTheirMarksInTheMenu() {
        shelve(found("Gridded", author = "Grid Author"), found("Other"))
        show()
        waitForText("Gridded")
        rule.onNodeWithTag("books").assertExists()

        rule.onNodeWithContentDescription(string(R.string.action_view_grid)).performClick()
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithTag("books-grid").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Grid Author").assertExists()
        assertTrue(rule.onAllNodesWithTag("books").fetchSemanticsNodes().isEmpty())

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
    }

    @Test
    fun menuOfTheScreenLeadsToTheServerAndTheLicenses() {
        show()
        rule.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        rule.onNodeWithText(string(R.string.action_server)).performClick()
        assertTrue(wentToServer)

        rule.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        rule.onNodeWithText(string(R.string.action_licenses)).performClick()
        assertTrue(wentToLicenses)
    }

    @Test
    fun folderIsLookedThroughAndAPickedBookOpensFromACopy() {
        val file = TestBook.write(
            File(source.dir, "saga.fb2"), "Found Book", author = "Anna Smith", cover = TestFolder.cover(Color.RED),
        )
        runBlocking { library.addFolder(source.uriOf(source.dir), "Books") }
        show()

        // The engine read the file: the book is known by what it says.
        waitForText("Found Book")
        rule.onNodeWithText("Found Book").performClick()
        rule.waitUntil(TIMEOUT) { opened != null }

        val copy = opened!!
        assertTrue("the copy is in the app's storage", copy.path.startsWith(activity.filesDir.path))
        assertArrayEquals(file.readBytes(), copy.readBytes())
        assertEquals(copy.path, runBlocking { library.books().first().single().path })
    }

    private companion object {
        // An emulator on a shared CI machine can stall for seconds; only a failing wait lasts this long.
        const val TIMEOUT = 30_000L
    }
}
