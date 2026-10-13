package org.evsyukov.perigee.library

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.FoundBook
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LibraryPreferences
import org.evsyukov.perigee.data.LibraryPrefs
import org.evsyukov.perigee.data.ReadingStatus
import org.evsyukov.perigee.reader.TestBook
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

    private fun show() {
        val model = LibraryViewModel(
            activity.application, library, LibraryPreferences.get(activity),
            Covers(File(activity.cacheDir, "screen-covers")),
        ) { source }
        rule.setContent {
            MaterialTheme { LibraryScreen(onOpen = { opened = it }, onOpenFile = { askedForFile = true }, model = model) }
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
