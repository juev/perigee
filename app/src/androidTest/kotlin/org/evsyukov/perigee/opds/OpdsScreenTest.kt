package org.evsyukov.perigee.opds

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.Catalogs
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.FoundBook
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LibraryPreferences
import org.evsyukov.perigee.data.LibraryPrefs
import org.evsyukov.perigee.library.BookSink
import org.evsyukov.perigee.library.SourceFile
import org.evsyukov.perigee.reader.TestBook
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Catalogs as the reader uses them: requirement R5. */
@RunWith(AndroidJUnit4::class)
class OpdsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val activity get() = rule.activity
    private val library by lazy { Library.inMemory(activity) }
    private val catalogs by lazy { Catalogs.inMemory(activity) }
    private val catalog by lazy {
        val book = TestBook.write(File(activity.cacheDir, "catalog-book.fb2"), "Title In The File", author = "File Author")
        TestCatalog("reader", "secret-1", book.readBytes())
    }
    private var opened: File? = null
    private var closed = false

    @After
    fun stopCatalog() = catalog.close()

    private fun string(id: Int, vararg args: Any) = activity.getString(id, *args)

    private val preferences by lazy { LibraryPreferences.get(activity) }
    private val downloads by lazy { File(activity.cacheDir, "opds-downloads-folder").apply { deleteRecursively() } }
    private val downloadsUri get() = Uri.fromFile(downloads).toString()
    private var folderWritable = true

    /** A plain directory standing in for the folder the reader picked for downloads. */
    private val sink = BookSink { name, from ->
        if (!folderWritable) return@BookSink null
        downloads.mkdirs()
        val file = File(downloads, name).also { from.copyTo(it) }
        SourceFile(Uri.fromFile(file).toString(), name, file.length(), file.lastModified())
    }

    /** As after the reader was asked where downloads go; [folder] is what they chose, null for the app itself. */
    private fun downloadsGo(folder: String?) = runBlocking {
        preferences.update { LibraryPrefs(downloadsFolder = folder, downloadsAsked = true) }
    }

    @Before
    fun downloadsStayInTheApp() = downloadsGo(null)

    private fun show() {
        val model = OpdsViewModel(
            activity.application, catalogs, library, Covers(File(activity.cacheDir, "opds-covers")), preferences,
        ) { sink }
        rule.setContent { MaterialTheme { OpdsScreen(onOpen = { opened = it }, onClose = { closed = true }, model = model) } }
    }

    private fun waitForText(text: String) =
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun back() = rule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }

    private fun fillIn(address: String, user: String = "", password: String = "") {
        rule.onNodeWithTag("catalog-address").performTextReplacement(address)
        rule.onNodeWithTag("catalog-user").performTextReplacement(user)
        rule.onNodeWithTag("catalog-password").performTextReplacement(password)
        rule.onNodeWithText(string(R.string.catalog_check_and_add)).performClick()
    }

    /** Adds the test catalog through the form and opens it. */
    private fun openCatalog() {
        show()
        rule.onNodeWithText(string(R.string.catalog_add)).performClick()
        fillIn("${catalog.url}/opds", "reader", "secret-1")
        waitForText("Home Books")
        rule.onNodeWithText("Home Books").performClick()
        waitForText("All Books")
    }

    @Test
    fun addingACatalogSaysWhatIsWrongUntilItIsRight() {
        show()
        rule.onNodeWithText(string(R.string.catalogs_empty)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.catalog_add)).performClick()

        fillIn("${catalog.url}/opds", "reader", "wrong")
        waitForText(string(R.string.catalog_unauthorized))

        fillIn("${catalog.url}/page", "reader", "secret-1")
        waitForText(string(R.string.catalog_not_a_catalog))

        fillIn("http://127.0.0.1:1/opds")
        waitForText(string(R.string.catalog_unreachable))

        fillIn("ftp://books.example/opds")
        waitForText(string(R.string.catalog_bad_address))
        assertTrue("nothing was added on the way", runBlocking { catalogs.all().first().isEmpty() })

        fillIn("${catalog.url}/opds", "reader", "secret-1")
        waitForText("Home Books")
        rule.onNodeWithText("${catalog.url}/opds").assertIsDisplayed()
        assertTrue("the form closed itself", rule.onAllNodesWithText(string(R.string.catalog_check_and_add)).fetchSemanticsNodes().isEmpty())

        val stored = runBlocking { catalogs.all().first().single() }
        assertEquals("the catalog is titled as it titles itself", "Home Books", stored.title)
        assertFalse("secret-1" in stored.secret)
    }

    @Test
    fun catalogIsRemovedFromTheList() {
        runBlocking { catalogs.add("${catalog.url}/opds", "Home Books", "reader", "secret-1") }
        show()
        waitForText("Home Books")
        rule.onNodeWithContentDescription(string(R.string.catalog_remove, "Home Books")).performClick()
        waitForText(string(R.string.catalogs_empty))
    }

    @Test
    fun bookIsDownloadedIntoTheLibraryAndOpened() {
        openCatalog()
        rule.onNodeWithText("Everything there is").assertIsDisplayed()
        rule.onNodeWithText("All Books").performClick()
        waitForText("Listed Title")
        // The page's second part arrives by itself and brings another book like this one.
        rule.onAllNodesWithText("Catalog Author").onFirst().assertIsDisplayed()

        // The PDF the catalog also offers is not something the app reads, and is not offered.
        assertTrue(rule.onAllNodesWithText(string(R.string.catalog_download, "PDF")).fetchSemanticsNodes().isEmpty())
        rule.onAllNodesWithText(string(R.string.catalog_download, "FB2")).onFirst().performClick()
        waitForText(string(R.string.catalog_downloaded))

        val book = runBlocking { library.books().first().single() }
        assertEquals("the book is known by what its file says", "Title In The File", book.title)
        assertEquals(listOf("File Author"), book.authors)
        assertEquals("fb2", book.format)
        assertEquals("not opened yet", 0L, book.openedAt)
        assertTrue(File(book.path).isFile)
        assertTrue("kept in the app's storage", book.path.startsWith(activity.filesDir.path))

        // Both books of the test catalog are one file, so both lines now offer it.
        rule.onAllNodesWithText(string(R.string.catalog_read)).onFirst().performClick()
        rule.waitUntil(TIMEOUT) { opened != null }
        assertEquals(book.path, opened?.path)
    }

    private fun toTheBooks() {
        openCatalog()
        rule.onNodeWithText("All Books").performClick()
        waitForText("Listed Title")
    }

    private fun downloadFirst() = rule.onAllNodesWithText(string(R.string.catalog_download, "FB2")).onFirst().performClick()

    @Test
    fun firstDownloadAsksWhereBooksShouldGo() {
        runBlocking { preferences.update { LibraryPrefs() } }
        toTheBooks()
        downloadFirst()
        waitForText(string(R.string.downloads_ask_title))
        assertTrue("nothing is downloaded before the answer", runBlocking { library.books().first().isEmpty() })

        rule.onNodeWithText(string(R.string.downloads_keep_in_app)).performClick()
        waitForText(string(R.string.catalog_downloaded))
        val book = runBlocking { library.books().first().single() }
        assertTrue(book.path.startsWith(activity.filesDir.path))
        assertTrue("the question is not asked twice", runBlocking { preferences.prefs.first().downloadsAsked })
    }

    @Test
    fun downloadGoesIntoTheFolderTheReaderChose() {
        downloadsGo(downloadsUri)
        toTheBooks()
        downloadFirst()
        waitForText(string(R.string.catalog_downloaded))

        assertEquals("an ordinary file under the book's own name", listOf("Listed Title.fb2"), downloads.list().orEmpty().toList())
        val book = runBlocking { library.books().first().single() }
        assertEquals("Title In The File", book.title)
        assertEquals(Uri.fromFile(File(downloads, "Listed Title.fb2")).toString(), book.uri)
        assertEquals("no copy in the app until the book is opened", "", book.path)
        val folder = runBlocking { library.folders().first().single() }
        assertEquals("the folder is one the library looks through", downloadsUri, folder.uri)
        assertEquals(listOf(book.uri), runBlocking { library.filesIn(folder.id).map { it.uri } })

        rule.onAllNodesWithText(string(R.string.catalog_read)).onFirst().performClick()
        rule.waitUntil(TIMEOUT) { opened != null }
        assertArrayEquals(File(downloads, "Listed Title.fb2").readBytes(), opened!!.readBytes())
    }

    @Test
    fun bookThatLiesInAFolderIsNotPutThereTwice() {
        downloadsGo(downloadsUri)
        openCatalog()
        // The same file under another entry of the catalog.
        rule.onNodeWithContentDescription(string(R.string.action_search)).performClick()
        rule.onNodeWithTag("catalog-search").performTextInput("again")
        rule.onNodeWithTag("catalog-search").performImeAction()
        waitForText("Found for again")
        runBlocking {
            val folder = library.addFolder(downloadsUri, "Downloads")
            val file = TestBook.write(File(activity.cacheDir, "catalog-book.fb2"), "Title In The File", author = "File Author")
            library.found(
                folder.id,
                FoundBook(
                    FileHash.partialMd5(file), "file:///elsewhere/mine.fb2", 1, 1, "fb2", "Title In The File",
                    listOf("File Author"), "en", "", "", hasCover = false,
                ),
            )
        }
        downloadFirst()
        waitForText(string(R.string.catalog_downloaded))
        assertTrue("nothing was written: ${downloads.list().orEmpty().toList()}", downloads.list().isNullOrEmpty())
        assertEquals("file:///elsewhere/mine.fb2", runBlocking { library.books().first().single().uri })
    }

    @Test
    fun bookIsKeptInTheAppWhenTheFolderCannotBeWrittenTo() {
        downloadsGo(downloadsUri)
        folderWritable = false
        toTheBooks()
        downloadFirst()
        waitForText(string(R.string.catalog_downloaded))
        val book = runBlocking { library.books().first().single() }
        assertTrue(File(book.path).isFile)
        assertEquals(null, book.uri)
    }

    @Test
    fun endOfAPageBringsWhatFollows() {
        openCatalog()
        rule.onNodeWithText("All Books").performClick()
        waitForText("Listed Title")
        rule.waitUntil(TIMEOUT) { "/opds/all?page=2" in catalog.asked }
        rule.onNodeWithTag("catalog-entries").performScrollToNode(hasText("Second Page Title"))
        rule.onNodeWithText("Second Page Title").assertIsDisplayed()
        rule.waitForIdle()
        assertEquals("fetched once", 1, catalog.asked.count { it == "/opds/all?page=2" })
    }

    @Test
    fun searchShowsWhatTheCatalogFound() {
        openCatalog()
        rule.onNodeWithContentDescription(string(R.string.action_search)).performClick()
        rule.onNodeWithTag("catalog-search").performTextInput("war and peace")
        rule.onNodeWithTag("catalog-search").performImeAction()
        waitForText("Found for war+and+peace")

        back()
        waitForText("All Books")
    }

    @Test
    fun brokenSectionSaysSoAndBackLeadsOut() {
        openCatalog()
        rule.onNodeWithText("Broken Shelf").performClick()
        waitForText(string(R.string.catalog_unreachable))
        rule.onNodeWithText("All Books").assertIsDisplayed()

        back()
        waitForText("${catalog.url}/opds")
        assertFalse(closed)
        back()
        assertTrue("from the list of catalogs back leaves the screen", closed)
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
