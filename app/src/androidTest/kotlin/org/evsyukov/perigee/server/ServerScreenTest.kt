package org.evsyukov.perigee.server

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LibraryPreferences
import org.evsyukov.perigee.data.LibraryPrefs
import org.evsyukov.perigee.data.LocalBook
import org.evsyukov.perigee.library.Arrivals
import org.evsyukov.perigee.library.BookSink
import org.evsyukov.perigee.reader.TestBook
import org.evsyukov.perigee.sync.Answer
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.FakeOrbit
import org.evsyukov.perigee.sync.TestServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The bookOrbit server as the reader uses it: requirements R6 and R10. */
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
    private var closed = false

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
        val model = ServerViewModel(activity.application, orbit, accounts, library, arrivals)
        rule.setContent { MaterialTheme { ServerScreen(onOpen = { opened = it }, onClose = { closed = true }, model = model) } }
    }

    private fun waitForText(text: String) =
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun waitUntilGone(text: String) =
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    private fun fillIn(address: String, user: String, password: String) {
        // The form comes once the account has been looked up.
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithTag("server-address").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("server-address").performTextReplacement(address)
        rule.onNodeWithTag("server-user").performTextReplacement(user)
        rule.onNodeWithTag("server-password").performTextReplacement(password)
        rule.onNodeWithTag("server-sign-in").performClick()
    }

    /** Signs in through the form; the stand-in server is reached over plain HTTP, which the reader has to agree to. */
    private fun signIn(address: String = server.url, password: String = "right-password") {
        fillIn(address, "reader", password)
        rule.onNodeWithTag("plain-http-send").performClick()
    }

    private fun serverHas(vararg books: String) {
        server.books = JSONArray(books.joinToString(",", "[", "]"))
    }

    private fun book(id: Long, title: String, fileId: Long, format: String = "fb2") =
        """{"id":$id,"title":"$title","authors":["Server Author"],"seriesName":null,"seriesIndex":null,"hasCover":false,
            "files":[{"id":$fileId,"format":"$format","role":"primary","sizeBytes":100}]}"""

    @Test
    fun signingInSaysWhatIsWrongUntilItIsRight() {
        serverHas(book(7, "Listed Title", 70))
        show()
        // The form comes once the account has been looked up.
        waitForText(string(R.string.server_about))
        rule.onNodeWithText(string(R.string.server_about)).assertIsDisplayed()

        signIn(password = "wrong")
        waitForText(string(R.string.server_wrong_password))

        TestServer { Answer(200, "<html>A router</html>".toByteArray(), "text/html") }.use { other ->
            signIn(address = other.url)
            waitForText(string(R.string.server_not_a_server))
        }

        fillIn("ftp://books.example", "reader", "right-password")
        waitForText(string(R.string.server_bad_address))
        assertNull("nobody was signed in on the way", runBlocking { accounts.current() })

        signIn()
        waitForText("Listed Title")
        rule.onNodeWithText("Server Author").assertIsDisplayed()
        assertEquals(server.url, runBlocking { accounts.current() }?.url)
        assertTrue("reading is ready to be synced, and nothing is asked", rule.onAllNodesWithTag("server-channel").fetchSemanticsNodes().isEmpty())
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
        // A comic is not something the app reads, and is not offered.
        rule.onNodeWithText(string(R.string.catalog_no_readable_file)).assertIsDisplayed()

        rule.onNodeWithText(string(R.string.catalog_download, "FB2")).performClick()
        waitForText(string(R.string.catalog_downloaded))

        val book = runBlocking { library.books().first().single() }
        assertEquals("the book is known by what its file says", "Title In The File", book.title)
        assertEquals("fb2", book.format)
        assertEquals("known to be the server's without asking it", 70L, book.serverFileId)
        assertEquals(7L, book.serverBookId)

        rule.onNodeWithText(string(R.string.catalog_read)).performClick()
        rule.waitUntil(TIMEOUT) { opened != null }
        assertEquals(book.path, opened?.path)
    }

    @Test
    fun fileThatIsNotABookIsRefused() {
        serverHas(book(7, "Listed Title", 70))
        // Empty: noise the engine may take for a text of some kind, nothing it cannot.
        server.downloads[70] = Answer(200, ByteArray(0), "application/x-fictionbook+xml")
        show()
        signIn()
        waitForText("Listed Title")
        rule.onNodeWithText(string(R.string.catalog_download, "FB2")).performClick()
        waitForText(string(R.string.catalog_not_a_book))
        assertTrue(runBlocking { library.books().first().isEmpty() })
    }

    @Test
    fun bookTheReaderAlreadyHasIsFoundOnTheServer() {
        // The book came to the device some other way; the server has the same file.
        val hash = FileHash.partialMd5(bookFile)
        runBlocking { library.keep(bookFile, LocalBook(hash, "fb2", "Title In The File", emptyList(), "", "", "", false)) }
        server.files[hash] = 7L to 70L
        serverHas(book(7, "Listed Title", 70))
        show()
        signIn()

        waitForText(string(R.string.catalog_downloaded))
        assertTrue("it is not offered for download again", rule.onAllNodesWithText(string(R.string.catalog_download, "FB2")).fetchSemanticsNodes().isEmpty())
        assertEquals(70L, runBlocking { library.book(hash) }?.serverFileId)
    }

    @Test
    fun readerWithAKoreaderUserNameIsAskedForItsPassword() {
        server.deviceUser = "kindle-of-mine"
        server.deviceKey = FakeOrbit.md5("chosen in the browser")
        serverHas(book(7, "Listed Title", 70))
        show()
        signIn()

        waitForText(string(R.string.server_channel_password_needed, "kindle-of-mine"))
        rule.onNodeWithText("Listed Title").assertIsDisplayed()

        rule.onNodeWithTag("server-channel-password").performTextReplacement("a guess")
        rule.onNodeWithText(string(R.string.server_channel_connect)).performClick()
        waitForText(string(R.string.server_channel_password_wrong))

        rule.onNodeWithTag("server-channel-password").performTextReplacement("chosen in the browser")
        rule.onNodeWithText(string(R.string.server_channel_connect)).performClick()
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithTag("server-channel").fetchSemanticsNodes().isEmpty() }
        assertEquals(server.deviceKey, runBlocking { accounts.deviceKey() })
    }

    @Test
    fun readerWhoDoesNotKnowTheKoreaderPasswordSetsANewOne() {
        // The app made the password up, and was installed anew since.
        server.deviceUser = "reader"
        server.deviceKey = FakeOrbit.md5("made up and lost")
        show()
        signIn()
        waitForText(string(R.string.server_channel_password_needed, "reader"))

        rule.onNodeWithTag("server-channel-password").performTextReplacement("chosen anew")
        rule.onNodeWithText(string(R.string.server_channel_set_new)).performClick()

        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithTag("server-channel").fetchSemanticsNodes().isEmpty() }
        assertEquals(FakeOrbit.md5("chosen anew"), server.deviceKey)
        assertEquals(server.deviceKey, runBlocking { accounts.deviceKey() })
    }

    @Test
    fun serverOfAnUnknownVersionIsReadButNotSyncedWith() {
        server.version = "v4.0.0"
        serverHas(book(7, "Listed Title", 70))
        show()
        signIn()
        waitForText(string(R.string.server_unsupported, "v4.0.0"))
        // The books are fetched after the server has said what it is.
        waitForText("Listed Title")
        rule.onNodeWithText("Listed Title").assertIsDisplayed()
        assertTrue("its devices' channel is left alone", server.seen.none { "/koreader/" in it.path })
    }

    @Test
    fun readerNotAllowedToSyncIsToldSo() {
        server.deviceAllowed = false
        show()
        signIn()
        waitForText(string(R.string.server_channel_not_allowed))
    }

    @Test
    fun searchAsksTheServerAndBackShowsAllAgain() {
        serverHas(book(7, "Listed Title", 70))
        show()
        signIn()
        waitForText("Listed Title")

        rule.onNodeWithContentDescription(string(R.string.action_search)).performClick()
        rule.onNodeWithTag("server-search").performTextReplacement("tolstoy")
        rule.onNodeWithTag("server-search").performImeAction()
        rule.waitUntil(TIMEOUT) { server.asked("/books/query").any { JSONObject(it.body).optString("q") == "tolstoy" } }
        waitForText("tolstoy")

        val before = server.asked("/books/query").size
        rule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(TIMEOUT) { server.asked("/books/query").size > before }
        assertTrue(!JSONObject(server.asked("/books/query").last().body).has("q"))
        assertTrue("still on the server's screen", !closed)
    }

    @Test
    fun listGrowsAsTheReaderReachesItsEnd() {
        serverHas(book(7, "Listed Title", 70))
        // The server says it has more than it gave.
        server.total = 2
        show()
        signIn()
        waitForText("Listed Title")
        rule.waitUntil(TIMEOUT) { server.asked("/books/query").any { JSONObject(it.body).getJSONObject("pagination").getInt("page") == 1 } }
    }

    @Test
    fun sessionThatEndedAsksToSignInAgainToTheSameServer() {
        serverHas(book(7, "Listed Title", 70))
        show()
        signIn()
        waitForText("Listed Title")
        // As after the reader ended the app's session from the server's own pages.
        server.access.clear()
        server.refresh.clear()

        rule.onNodeWithContentDescription(string(R.string.action_search)).performClick()
        rule.onNodeWithTag("server-search").performTextReplacement("anything")
        rule.onNodeWithTag("server-search").performImeAction()

        waitForText(string(R.string.server_session_over))
        rule.onNodeWithText(server.url).assertIsDisplayed()
        rule.onNodeWithTag("server-password").performTextReplacement("right-password")
        rule.onNodeWithTag("server-sign-in").performClick()
        rule.onNodeWithTag("plain-http-send").performClick()
        waitForText("Listed Title")
    }

    @Test
    fun signingOutReturnsToTheFormAndForgetsTheServer() {
        val hash = FileHash.partialMd5(bookFile)
        runBlocking { library.keep(bookFile, LocalBook(hash, "fb2", "Title In The File", emptyList(), "", "", "", false)) }
        server.files[hash] = 7L to 70L
        show()
        signIn()
        rule.waitUntil(TIMEOUT) { runBlocking { library.book(hash) }?.serverFileId == 70L }

        rule.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        rule.onNodeWithText(string(R.string.server_sign_out)).performClick()

        waitForText(string(R.string.server_about))
        assertNull(runBlocking { accounts.current() })
        assertNull(runBlocking { library.book(hash) }?.serverFileId)
        assertEquals("the book stays", 1, runBlocking { library.books().first().size })
    }

    @Test
    fun serverThatDoesNotAnswerCanBeTriedAgain() {
        serverHas(book(7, "Listed Title", 70))
        show()
        signIn()
        waitForText("Listed Title")
        server.queryAnswer = Answer(500, """{"statusCode":500,"message":"Internal server error"}""")

        rule.onNodeWithContentDescription(string(R.string.action_search)).performClick()
        rule.onNodeWithTag("server-search").performTextReplacement("anything")
        rule.onNodeWithTag("server-search").performImeAction()
        waitForText(string(R.string.server_unreachable))

        server.queryAnswer = null
        rule.onNodeWithText(string(R.string.action_retry)).performClick()
        waitForText("Listed Title")
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
