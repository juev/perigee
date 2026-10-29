package org.evsyukov.perigee.server

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LibraryPreferences
import org.evsyukov.perigee.data.LibraryPrefs
import org.evsyukov.perigee.library.Arrivals
import org.evsyukov.perigee.library.BookSink
import org.evsyukov.perigee.reader.TestBook
import org.evsyukov.perigee.sync.Answer
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.FakeOrbit
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bookOrbit server's screen on a device: the form, the question before plain HTTP, a book
 * downloaded and opened from the list. What the screen does is checked on its model, by
 * ServerViewModelTest on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class ServerScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val activity get() = rule.activity
    private val library by lazy { Library.inMemory(activity) }
    private val accounts by lazy { Accounts.of(library) }
    private val orbit by lazy { BookOrbit(accounts, library, "0.0.1") }
    private val server = FakeOrbit()
    private val bookFile by lazy { TestBook.write(File(activity.cacheDir, "server-book.fb2"), "Title In The File", author = "File Author") }
    private var opened: File? = null
    private lateinit var model: ServerViewModel

    @After
    fun stopServer() = server.close()

    /** Downloaded books stay in the app, as after the reader said so. */
    @Before
    fun downloadsStayInTheApp() = runBlocking<Unit> {
        LibraryPreferences.get(activity).update { LibraryPrefs(downloadsFolder = null, downloadsAsked = true) }
    }

    private fun string(id: Int, vararg args: Any) = activity.getString(id, *args)

    private fun show() {
        val arrivals = Arrivals(
            activity, library, Covers(File(activity.cacheDir, "server-covers")), LibraryPreferences.get(activity),
        ) { BookSink { _, _ -> null } }
        model = ServerViewModel(activity.application, orbit, accounts, library, arrivals)
        rule.setContent { MaterialTheme { ServerScreen(onOpen = { opened = it }, onClose = {}, model = model) } }
    }

    /** Waits for [done]; on a timeout, says what the server was asked and where the model stood. */
    private fun waitFor(what: String, done: () -> Boolean) = try {
        rule.waitUntil(TIMEOUT, done)
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("$what; the server was asked ${server.seen.map { "${it.method} ${it.path}" }}, the state is ${model.state.value}", e)
    }

    private fun waitForText(text: String) =
        waitFor("'$text' is shown") { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun waitUntilGone(text: String) =
        waitFor("'$text' is gone") { rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    private fun fillIn(address: String, user: String, password: String) {
        // The form comes once the account has been looked up.
        waitFor("the form is shown") { rule.onAllNodesWithTag("server-address").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("server-address").performTextReplacement(address)
        rule.onNodeWithTag("server-user").performTextReplacement(user)
        rule.onNodeWithTag("server-password").performTextReplacement(password)
        rule.onNodeWithTag("server-sign-in").performClick()
    }

    /** Signs in through the form; the stand-in server is reached over plain HTTP, which the reader has to agree to. */
    private fun signIn() {
        fillIn(server.url, "reader", "right-password")
        rule.onNodeWithTag("plain-http-send").performClick()
    }

    private fun serverHas(vararg books: String) {
        server.books = JSONArray(books.joinToString(",", "[", "]"))
    }

    private fun book(id: Long, title: String, fileId: Long, format: String = "fb2") =
        """{"id":$id,"title":"$title","authors":["Server Author"],"seriesName":null,"seriesIndex":null,"hasCover":false,
            "files":[{"id":$fileId,"format":"$format","role":"primary","sizeBytes":100}]}"""

    @Test
    fun ssoOffersTheServersProvidersToChooseFrom() {
        server.oidcProviders = JSONArray(
            """[{"slug":"authelia","displayName":"Authelia","enabled":true,"clientId":"bookorbit","scopes":"openid"},
                {"slug":"google","displayName":"Google","enabled":true,"clientId":"bookorbit","scopes":"openid"}]""",
        )
        show()
        waitFor("the form is shown") { rule.onAllNodesWithTag("server-address").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("server-address").performTextReplacement(server.url)
        rule.onNodeWithTag("server-sign-in-provider").performClick()

        waitForText(string(R.string.server_choose_provider))
        rule.onNodeWithText("Authelia").assertIsDisplayed()
        rule.onNodeWithText("Google").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_cancel)).performClick()
        waitUntilGone("Authelia")
        assertTrue("nothing was started at a provider", server.seen.none { it.path.endsWith("/state") })
    }

    @Test
    fun signingInShowsTheServersBooks() {
        serverHas(book(7, "Listed Title", 70))
        show()
        // The form comes once the account has been looked up.
        waitForText(string(R.string.server_about))
        rule.onNodeWithText(string(R.string.server_about)).assertIsDisplayed()

        signIn()
        waitForText("Listed Title")
        rule.onNodeWithText("Server Author").assertIsDisplayed()
        assertTrue("reading is ready to be synced, and nothing is asked", rule.onAllNodesWithTag("server-channel").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun screenSaysHowSyncingWentAndWhatIsNotSent() {
        serverHas(book(7, "Listed Title", 70))
        show()
        signIn()
        waitForText("Listed Title")
        val id = bookFile.let { runBlocking { library.open(it, "Read Here", "").id } }
        server.files[id] = 7L to 70L
        runBlocking {
            library.onServer(id, 7, 70)
            library.savePosition(id, "/a", 0f)
            library.savePosition(id, "/b", 0.5f)
        }
        waitForText(activity.resources.getQuantityString(R.plurals.server_unsent, 1, 1))

        server.down = true
        assertEquals(SyncOutcome.Later, runBlocking { syncRound(orbit, accounts) })
        waitForStart(R.string.server_sync_unreachable)

        server.down = false
        assertEquals(SyncOutcome.Done, runBlocking { syncRound(orbit, accounts) })
        waitUntilGone(activity.resources.getQuantityString(R.plurals.server_unsent, 1, 1))
        waitForStart(R.string.server_synced_at)
    }

    /** Waits for a line that says [id], whatever is put into it. */
    private fun waitForStart(id: Int) {
        val start = string(id, "\u0000").substringBefore('\u0000')
        waitFor("a line beginning '$start' is shown") { rule.onAllNodesWithText(start, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun passwordGoesOverPlainHttpOnlyAfterTheReaderAgrees() {
        show()
        fillIn(server.url, "reader", "right-password")
        rule.onNodeWithText(string(R.string.plain_http_title)).assertIsDisplayed()
        rule.onNodeWithTag("plain-http-cancel").performClick()
        rule.waitForIdle()
        assertTrue("nothing was sent: ${server.seen}", server.seen.isEmpty())

        rule.onNodeWithTag("server-sign-in").performClick()
        rule.onNodeWithTag("plain-http-send").performClick()
        waitUntilGone(string(R.string.server_about))
        assertEquals(1, server.asked("/auth/login").size)
    }

    @Test
    fun bookIsDownloadedIntoTheLibraryAndOpened() {
        serverHas(book(7, "Listed Title", 70), book(8, "A Comic", 80, format = "cbz"))
        server.downloads[70] = Answer(200, bookFile.readBytes(), "application/x-fictionbook+xml", mapOf("Content-Disposition" to "attachment; filename=\"listed.fb2\""))
        show()
        signIn()
        waitForText("Listed Title")
        // A comic is not something the app reads, and is not listed.
        rule.onNodeWithText("A Comic").assertDoesNotExist()
        rule.onNodeWithText(string(R.string.catalog_no_readable_file)).assertDoesNotExist()

        rule.onNodeWithText(string(R.string.catalog_download, "FB2")).performClick()
        waitForText(string(R.string.catalog_downloaded))
        // The engine read the file: the book is known by what it says, not by what the server listed.
        val book = runBlocking { library.books().first().single() }
        assertEquals("Title In The File", book.title)

        rule.onNodeWithText(string(R.string.catalog_read)).performClick()
        waitFor("the book is opened") { opened != null }
        assertEquals(book.path, opened?.path)
    }

    private companion object {
        // An emulator on a shared CI machine can stall for seconds; only a failing wait lasts this long.
        const val TIMEOUT = 30_000L
    }
}
