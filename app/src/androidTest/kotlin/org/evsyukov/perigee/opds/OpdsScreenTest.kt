package org.evsyukov.perigee.opds

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.Catalogs
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LibraryPreferences
import org.evsyukov.perigee.data.LibraryPrefs
import org.evsyukov.perigee.library.Arrivals
import org.evsyukov.perigee.library.BookSink
import org.evsyukov.perigee.reader.TestBook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The catalogs' screen on a device: the form and the question before plain HTTP, a book downloaded
 * and opened from a catalog. What the screen does is checked on its model, by OpdsViewModelTest on the JVM.
 */
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

    @After
    fun stopCatalog() = catalog.close()

    private fun string(id: Int, vararg args: Any) = activity.getString(id, *args)

    /** Downloaded books stay in the app, as after the reader said so. */
    @Before
    fun downloadsStayInTheApp() = runBlocking<Unit> {
        LibraryPreferences.get(activity).update { LibraryPrefs(downloadsFolder = null, downloadsAsked = true) }
    }

    private fun show() {
        val model = OpdsViewModel(
            activity.application, catalogs, library,
            Arrivals(activity, library, Covers(File(activity.cacheDir, "opds-covers")), LibraryPreferences.get(activity)) {
                BookSink { _, _ -> null }
            },
        )
        rule.setContent { MaterialTheme { OpdsScreen(onOpen = { opened = it }, onClose = {}, model = model) } }
    }

    private fun waitForText(text: String) =
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun fillIn(address: String, user: String = "", password: String = "") {
        rule.onNodeWithTag("catalog-address").performTextReplacement(address)
        rule.onNodeWithTag("catalog-user").performTextReplacement(user)
        rule.onNodeWithTag("catalog-password").performTextReplacement(password)
        rule.onNodeWithText(string(R.string.catalog_check_and_add)).performClick()
    }

    @Test
    fun passwordGoesOverPlainHttpOnlyAfterTheReaderAgrees() {
        show()
        rule.onNodeWithText(string(R.string.catalogs_empty)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.catalog_add)).performClick()
        fillIn("${catalog.url}/opds", "reader", "secret-1")

        rule.onNodeWithText(string(R.string.plain_http_title)).assertIsDisplayed()
        rule.onNodeWithTag("plain-http-cancel").performClick()
        rule.waitForIdle()
        assertTrue("nothing was sent: ${catalog.asked}", catalog.asked.isEmpty())
        assertTrue(runBlocking { catalogs.all().first().isEmpty() })

        rule.onNodeWithText(string(R.string.catalog_check_and_add)).performClick()
        rule.onNodeWithTag("plain-http-send").performClick()
        waitForText("Home Books")
        rule.onNodeWithText("${catalog.url}/opds").assertIsDisplayed()
        assertTrue("the form closed itself", rule.onAllNodesWithText(string(R.string.catalog_check_and_add)).fetchSemanticsNodes().isEmpty())
        assertEquals(1, runBlocking { catalogs.all().first().size })
    }

    @Test
    fun catalogWithoutAPasswordIsAddedOverPlainHttpUnasked() {
        show()
        rule.onNodeWithText(string(R.string.catalog_add)).performClick()
        fillIn("http://127.0.0.1:1/opds")
        waitForText(string(R.string.catalog_unreachable))
        assertTrue(rule.onAllNodesWithText(string(R.string.plain_http_title)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun bookIsDownloadedIntoTheLibraryAndOpened() {
        runBlocking { catalogs.add("${catalog.url}/opds", "Home Books", "reader", "secret-1") }
        show()
        waitForText("Home Books")
        rule.onNodeWithText("Home Books").performClick()
        waitForText("All Books")
        rule.onNodeWithText("Everything there is").assertIsDisplayed()
        rule.onNodeWithText("All Books").performClick()
        waitForText("Listed Title")
        rule.onAllNodesWithText("Catalog Author").onFirst().assertIsDisplayed()

        // The PDF the catalog also offers is not something the app reads, and is not offered.
        assertTrue(rule.onAllNodesWithText(string(R.string.catalog_download, "PDF")).fetchSemanticsNodes().isEmpty())
        rule.onAllNodesWithText(string(R.string.catalog_download, "FB2")).onFirst().performClick()
        waitForText(string(R.string.catalog_downloaded))

        // The engine read the file: the book is known by what it says, not by what the catalog listed.
        val book = runBlocking { library.books().first().single() }
        assertEquals("Title In The File", book.title)

        // Both books of the test catalog are one file, so both lines now offer it.
        rule.onAllNodesWithText(string(R.string.catalog_read)).onFirst().performClick()
        rule.waitUntil(TIMEOUT) { opened != null }
        assertEquals(book.path, opened?.path)
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
