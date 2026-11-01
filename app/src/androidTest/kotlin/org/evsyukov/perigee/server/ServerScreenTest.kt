package org.evsyukov.perigee.server

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.evsyukov.perigee.sync.Related
import org.evsyukov.perigee.ui.READABLE_WIDTH
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private var closed = false

    /**
     * Shows the screen; with [fontScale], with text that much larger, and with [wide], as on a tablet's
     * screen a thousand points wide, the screen's own being scaled down to that.
     */
    private fun show(fontScale: Float = 1f, wide: Boolean = false) {
        val arrivals = Arrivals(
            activity, library, Covers(File(activity.cacheDir, "server-covers")), LibraryPreferences.get(activity),
        ) { BookSink { _, _ -> null } }
        // A flow of its own: a model made here outlives its test, and would take the answers meant for SignInRedirectTest.
        model = ServerViewModel(activity.application, orbit, accounts, library, arrivals, MutableStateFlow(null))
        rule.setContent {
            val base = LocalDensity.current
            val screen = LocalConfiguration.current
            val scale = if (wide) screen.screenWidthDp / WIDE_DP else 1f
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale * fontScale)) {
                MaterialTheme { ServerScreen(onOpen = { opened = it }, onClose = { closed = true }, model = model) }
            }
        }
    }

    /** Goes to a part of the screen by its tab, once the tabs are there. */
    private fun part(section: Section) {
        val tag = "server-section-${section.name.lowercase()}"
        waitFor("the tabs are shown") { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(tag).performClick()
        waitFor("$section is shown") { model.state.value.section == section }
    }

    private fun catalogue() = part(Section.Catalogue)

    private fun systemBack() = rule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }

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
        // The home page comes first; the catalogue is a tab away.
        waitFor("the home page is shown") { rule.onAllNodesWithTag("server-home").fetchSemanticsNodes().isNotEmpty() }
        catalogue()
        waitForText("Listed Title")
        rule.onNodeWithText("Server Author").assertIsDisplayed()
        assertTrue("reading is ready to be synced, and nothing is asked", rule.onAllNodesWithTag("server-channel").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun screenSaysHowSyncingWentAndWhatIsNotSent() {
        serverHas(book(7, "Listed Title", 70))
        show()
        signIn()
        catalogue()
        waitForText("Listed Title")
        // How syncing goes is told on the home page.
        part(Section.Home)
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
        catalogue()
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

    private val onPage = hasAnyAncestor(hasTestTag("server-book-page"))
    private val onShelf = hasAnyAncestor(hasTestTag("server-books"))

    private fun shown(matcher: SemanticsMatcher) = rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitForShown(what: String, matcher: SemanticsMatcher) = waitFor("$what is shown") { shown(matcher) }

    private fun pageIsOpen() = rule.onAllNodesWithTag("server-book-page").fetchSemanticsNodes().isNotEmpty()

    /** Taps the book's line on its cover, away from the buttons on it, and waits for the book's page. */
    private fun openPageOf(title: String) {
        rule.onNode(hasText(title) and onShelf).performTouchInput { click(percentOffset(0.1f, 0.5f)) }
        waitFor("the page of $title is open") { pageIsOpen() }
    }

    private fun closePage() {
        rule.onNode(hasContentDescription(string(R.string.action_back)) and onPage).performClick()
        waitFor("the page is closed") { !pageIsOpen() }
    }

    @Test
    fun tapOnABookOpensItsPageAndTheWayBackReturnsToTheSamePlace() {
        serverHas(*(1..30).map { book(it.toLong(), "Book %02d".format(it), it * 10L) }.toTypedArray())
        server.descriptions[25] = "<p>First of it.</p><p>Second of <em>it</em>.</p>"
        show()
        signIn()
        catalogue()
        waitForText("Book 01")
        // The stand-in server finds every book by any phrase.
        rule.onNodeWithContentDescription(string(R.string.action_search)).performClick()
        rule.onNodeWithTag("server-search").performTextReplacement("tale")
        rule.onNodeWithTag("server-search").performImeAction()
        waitFor("the books found are listed") { model.state.value.let { it.query == "tale" && !it.loading && it.books.size == 30 } }
        rule.onNodeWithTag("server-books").performScrollToKey(25L)

        openPageOf("Book 25")
        waitForShown("the description", hasText("Second of it.") and onPage)
        rule.onNode(hasText("First of it.") and onPage).assertIsDisplayed()
        rule.onNode(hasText("Book 25") and onPage).assertIsDisplayed()
        rule.onNode(hasText("Server Author") and onPage).assertIsDisplayed()

        closePage()
        rule.onNodeWithText("tale").assertIsDisplayed()
        rule.onNode(hasText("Book 25") and onShelf).assertIsDisplayed()
        rule.onNode(hasText("Book 01") and onShelf).assertDoesNotExist()
        assertEquals("the list keeps all it had", 30, model.state.value.books.size)
    }

    @Test
    fun bookWithNoDescriptionSaysSo() {
        serverHas(book(7, "Listed Title", 70))
        show()
        signIn()
        catalogue()
        waitForText("Listed Title")

        openPageOf("Listed Title")
        waitForShown("that the book says nothing of itself", hasText(string(R.string.book_no_description)) and onPage)
    }

    @Test
    fun downloadBegunOnThePageShowsOnTheLineAndTheBookIsReadFromThePage() {
        serverHas(book(7, "Listed Title", 70))
        server.downloads[70] = Answer(200, bookFile.readBytes(), "application/x-fictionbook+xml", mapOf("Content-Disposition" to "attachment; filename=\"listed.fb2\""))
        show()
        signIn()
        catalogue()
        waitForText("Listed Title")

        openPageOf("Listed Title")
        waitForShown("the download", hasText(string(R.string.catalog_download, "FB2")) and onPage)
        rule.onNode(hasText(string(R.string.catalog_download, "FB2")) and onPage).performClick()
        waitForShown("the book in the library on the page", hasText(string(R.string.catalog_downloaded)) and onPage)
        waitForShown("the book in the library on its line", hasText(string(R.string.catalog_downloaded)) and onShelf)
        val book = runBlocking { library.books().first().single() }

        rule.onNode(hasText(string(R.string.catalog_read)) and onPage).performClick()
        waitFor("the book is opened") { opened != null }
        // The reader goes to the book, as from the library's own page: the page is left, not kept behind the reader.
        waitFor("the page is closed") { !pageIsOpen() }
        assertEquals(book.path, opened?.path)
    }

    @Test
    fun pageThatCannotBeFilledSaysSoAndLeavesTheListUsable() {
        serverHas(book(7, "Listed Title", 70), book(8, "Other Title", 80))
        server.descriptions[7] = "<p>About it.</p>"
        show()
        signIn()
        catalogue()
        waitForText("Other Title")
        server.detailAnswer = Answer(500, """{"statusCode":500,"message":"Internal server error"}""")

        openPageOf("Listed Title")
        waitForShown("the failure", hasText(string(R.string.server_unreachable)) and onPage)
        rule.onNode(hasText(string(R.string.action_retry)) and onPage).assertIsDisplayed()
        assertTrue("the list is there behind the page", shown(hasText("Other Title") and onShelf))
        assertNull("the list itself has not failed", model.state.value.failure)

        server.detailAnswer = null
        rule.onNode(hasText(string(R.string.action_retry)) and onPage).performClick()
        waitForShown("the description", hasText("About it.") and onPage)

        closePage()
        openPageOf("Other Title")
        waitForShown("that the other book says nothing of itself", hasText(string(R.string.book_no_description)) and onPage)
    }

    private fun bookBy(id: Long, title: String, fileId: Long, author: String) =
        """{"id":$id,"title":"$title","authors":["$author"],"seriesName":null,"seriesIndex":null,"hasCover":false,
            "files":[{"id":$fileId,"format":"fb2","role":"primary","sizeBytes":100}]}"""

    private val onHome = hasAnyAncestor(hasTestTag("server-home"))

    private fun onShelfOf(type: String) = hasAnyAncestor(hasTestTag("shelf-$type"))

    @Test
    fun homeComesFirstAndEachPartKeepsItsQueryAndPlace() {
        serverHas(*(1..30).map { book(it.toLong(), "Book %02d".format(it), it * 10L) }.toTypedArray())
        server.shelves["recently-added"] = listOf(3L, 2L, 1L)
        show()
        signIn()
        waitForShown("the newest books", hasText(string(R.string.server_shelf_recently_added)) and onHome)
        rule.onNodeWithTag("server-section-home").assertIsSelected()
        rule.onNode(hasText("Book 03") and onShelfOf("recently-added")).assertIsDisplayed()

        catalogue()
        rule.onNodeWithContentDescription(string(R.string.action_search)).performClick()
        rule.onNodeWithTag("server-search-scope").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.server_scope_catalogue)).assertIsDisplayed()
        rule.onNodeWithTag("server-search").performTextReplacement("tale")
        rule.onNodeWithTag("server-search").performImeAction()
        waitFor("the books found are listed") { model.state.value.let { it.query == "tale" && !it.loading && it.books.size == 30 } }
        rule.onNodeWithTag("server-books").performScrollToKey(25L)

        part(Section.Collections)
        part(Section.Home)
        catalogue()
        rule.onNodeWithText("tale").assertIsDisplayed()
        rule.onNode(hasText("Book 25") and onShelf).assertIsDisplayed()
        rule.onNode(hasText("Book 01") and onShelf).assertDoesNotExist()
        assertEquals("nothing was asked again", 30, model.state.value.books.size)

        // Back takes the phrase off, then returns home, then leaves for the library.
        systemBack()
        waitFor("the phrase is taken off") { model.state.value.query.isEmpty() }
        systemBack()
        waitFor("the home page is back") { model.state.value.section == Section.Home }
        assertFalse(closed)
        systemBack()
        waitFor("the library is gone back to") { closed }
    }

    @Test
    fun shelfThatFailsIsTriedAgainAloneAndTheRestIsUsable() {
        serverHas(book(7, "Listed Title", 70))
        server.shelves["recently-added"] = listOf(7L)
        server.shelves["random"] = listOf(7L)
        server.failedShelves += "random"
        show()
        signIn()
        waitForText(string(R.string.server_shelf_failed))
        rule.onNode(hasText("Listed Title") and onShelfOf("recently-added")).assertIsDisplayed()

        server.failedShelves.clear()
        rule.onNodeWithTag("shelf-retry-random").performClick()
        waitForShown("the shelf filled", hasText("Listed Title") and onShelfOf("random"))
        rule.onNodeWithText(string(R.string.server_shelf_failed)).assertDoesNotExist()
    }

    @Test
    fun seeAllIsOfferedOnlyForAShelfThatIsAQuery() {
        serverHas(book(7, "Listed Title", 70))
        server.shelves["recently-added"] = listOf(7L)
        server.shelves["random"] = listOf(7L)
        show()
        signIn()
        waitForShown("the shelves", hasText("Listed Title") and onShelfOf("random"))
        val random = string(R.string.server_see_all_of, string(R.string.server_shelf_random))
        rule.onAllNodesWithContentDescription(random).assertCountEquals(0)

        rule.onNodeWithContentDescription(string(R.string.server_see_all_of, string(R.string.server_shelf_recently_added))).performClick()
        waitFor("the newest books in the catalogue") { model.state.value.let { it.section == Section.Catalogue && !it.loading && it.books.isNotEmpty() } }
        rule.onNodeWithTag("server-section-catalogue").assertIsSelected()
        rule.onNodeWithText(string(R.string.server_sort_added)).assertIsDisplayed()
    }

    @Test
    fun bookBesideABookOpensOverItsPageAndTheWayBackReturns() {
        serverHas(bookBy(7, "First Book", 70, "Shared Author"), bookBy(8, "Second Book", 80, "Shared Author"))
        show()
        signIn()
        catalogue()
        waitForText("Second Book")
        openPageOf("First Book")
        val beside = hasText("Second Book") and onPage and hasAnyAncestor(hasTestTag("server-related-author-books"))
        waitForShown("the book by the same author", beside)
        rule.onNodeWithTag("server-related-author-books").performScrollTo()
        rule.onNode(beside).performClick()
        waitFor("the second book's page") { model.state.value.opened?.id == 8L && model.state.value.detail?.id == 8L }
        assertEquals(listOf(7L), model.state.value.trail.map { it.id })

        closePageOnce()
        waitFor("back on the first book's page") { model.state.value.opened?.id == 7L }
        assertTrue(pageIsOpen())
        closePage()
        rule.onNode(hasText("First Book") and onShelf).assertIsDisplayed()
    }

    private fun closePageOnce() = rule.onNode(hasContentDescription(string(R.string.action_back)) and onPage).performClick()

    @Test
    fun bookOnlyOnTheServerIsMarkedRatedNotedAndPutIntoACollection() {
        serverHas(book(7, "Listed Title", 70))
        show()
        signIn()
        catalogue()
        waitForText("Listed Title")
        openPageOf("Listed Title")
        waitForShown("what the reader keeps of it", hasTestTag("server-personal"))

        rule.onNodeWithTag("server-status-change").performScrollTo().performClick()
        rule.onNodeWithTag("server-status-want_to_read").performClick()
        val wanted = string(R.string.server_status_label, string(R.string.server_status_want_to_read))
        waitForShown("the status the server confirmed", hasText(wanted) and onPage)
        assertEquals("want_to_read", server.statuses[7])
        // Wherever else the book is shown, it says so too.
        waitForShown("the status on the book's line", hasText(string(R.string.server_status_want_to_read)) and onShelf)

        rule.onNodeWithTag("server-star-4").performScrollTo().performClick()
        waitFor("the stars are on the server") { server.ratings[7] == 4 && Personal.Rating !in model.state.value.saving(7) }

        rule.onNodeWithTag("server-note-edit").performScrollTo().performClick()
        rule.onNodeWithTag("server-note-text").performTextReplacement("Lovely.")
        rule.onNodeWithTag("server-note-save").performClick()
        waitForShown("the note the server keeps", hasText("Lovely.") and onPage)
        assertEquals("Lovely.", server.reviews[7])

        rule.onNodeWithTag("server-book-collections").performScrollTo().performClick()
        waitFor("the reader's collections") { model.state.value.membership?.loading == false }
        rule.onNodeWithTag("server-membership-new").performTextReplacement("Summer")
        rule.onNodeWithTag("server-membership-create").performClick()
        waitFor("the collection is made with the book in it") { server.collections.any { it.name == "Summer" && 7L in it.books } }
        assertFalse(server.collections.single().isPublic)
        rule.onNodeWithText(string(R.string.action_done)).performClick()

        // Asked anew, the server has all of it: nothing was kept here only.
        val again = runBlocking { orbit.detail(7) }
        assertEquals("want_to_read", again.status)
        assertEquals(4, again.rating)
        assertEquals("Lovely.", again.note)
        assertTrue("nothing was downloaded for it", runBlocking { library.books().first() }.isEmpty())
    }

    @Test
    fun refusalOfAChangeIsToldAndNothingIsShownAsSaved() {
        serverHas(book(7, "Listed Title", 70))
        show()
        signIn()
        catalogue()
        waitForText("Listed Title")
        openPageOf("Listed Title")
        waitForShown("what the reader keeps of it", hasTestTag("server-personal"))
        server.statusAnswer = Answer(403, """{"statusCode":403,"message":"Forbidden"}""")

        rule.onNodeWithTag("server-status-change").performScrollTo().performClick()
        rule.onNodeWithTag("server-status-read").performClick()
        waitForShown("the refusal", hasText(string(R.string.server_not_allowed)) and onPage)
        rule.onNode(hasText(string(R.string.server_status_label, string(R.string.server_status_unread))) and onPage).assertExists()
    }

    @Test
    fun sharedCollectionIsOnlyLookedThroughAndDeletingOneLeavesItsBooks() {
        serverHas(book(7, "Listed Title", 70))
        val theirs = server.collection("Their Shelf", 7, owner = 2, public = true)
        val mine = server.collection("My Shelf", 7)
        show()
        signIn()
        part(Section.Collections)
        waitForText("My Shelf")
        rule.onNode(hasTestTag("server-collection-${theirs.id}") and hasText(string(R.string.server_collection_shared), substring = true)).assertExists()
        rule.onAllNodesWithContentDescription(string(R.string.collection_actions, "Their Shelf")).assertCountEquals(0)

        rule.onNodeWithText("Their Shelf").performClick()
        waitForShown("its books", hasText("Listed Title") and hasAnyAncestor(hasTestTag("server-collection-books")))
        systemBack()
        waitFor("the list of collections") { model.state.value.collection == null }

        rule.onNodeWithContentDescription(string(R.string.collection_actions, "My Shelf")).performClick()
        rule.onNodeWithText(string(R.string.action_delete)).performClick()
        rule.onNodeWithText(string(R.string.server_collection_delete_text)).assertIsDisplayed()
        rule.onNodeWithTag("server-collection-delete-confirm").performClick()
        waitUntilGone("My Shelf")
        assertEquals(listOf(theirs.id), server.collections.map { it.id })
        assertTrue(server.collections.none { it.id == mine.id })
        assertEquals("its book stays on the server", 1, server.books.length())
    }

    @Test
    fun bookOnTwoShelvesIsDownloadedOnceAndReadWithNoPageLeftOver() {
        serverHas(book(7, "Listed Title", 70))
        server.shelves["continue-reading"] = listOf(7L)
        server.shelves["recently-added"] = listOf(7L)
        server.downloads[70] = Answer(200, bookFile.readBytes(), "application/x-fictionbook+xml", mapOf("Content-Disposition" to "attachment; filename=\"listed.fb2\""))
        show()
        signIn()
        waitForShown("the shelf", hasText("Listed Title") and onShelfOf("continue-reading"))

        rule.onNode(hasText("Listed Title") and onShelfOf("continue-reading")).performClick()
        waitFor("the page is open") { pageIsOpen() }
        waitForShown("the download", hasText(string(R.string.catalog_download, "FB2")) and onPage)
        rule.onNode(hasText(string(R.string.catalog_download, "FB2")) and onPage).performClick()
        waitForShown("the book in the library", hasText(string(R.string.catalog_downloaded)) and onPage)
        closePage()
        waitFor("both shelves say it is in the library") {
            rule.onAllNodes(hasText(string(R.string.catalog_downloaded)) and onHome).fetchSemanticsNodes().size == 2
        }

        rule.onNode(hasText("Listed Title") and onShelfOf("recently-added")).performClick()
        waitForShown("Read", hasText(string(R.string.catalog_read)) and onPage)
        rule.onNode(hasText(string(R.string.catalog_read)) and onPage).performClick()
        waitFor("the book is opened") { opened != null }
        waitFor("no page is left over the screen") { !pageIsOpen() && model.state.value.trail.isEmpty() }
        assertEquals("downloaded once", 1, server.asked("/books/files/70/download").size)
    }

    @Test
    fun signingOutAndInAgainShowsNothingOfBefore() {
        serverHas(book(7, "Listed Title", 70))
        server.collection("My Shelf", 7)
        show()
        signIn()
        part(Section.Collections)
        waitForText("My Shelf")

        rule.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        rule.onNodeWithText(string(R.string.server_sign_out)).performClick()
        waitFor("the form is shown") { rule.onAllNodesWithTag("server-address").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("My Shelf").assertDoesNotExist()

        server.collections.clear()
        signIn()
        waitFor("the home page comes first again") { model.state.value.section == Section.Home && model.state.value.account != null }
        part(Section.Collections)
        waitForText(string(R.string.server_collections_empty))
        rule.onNodeWithText("My Shelf").assertDoesNotExist()
    }

    @Test
    fun partsAreReachableByNameWithLargeText() {
        serverHas(book(7, "Listed Title", 70))
        show(fontScale = 2f)
        signIn()
        for (section in Section.entries) {
            val tab = rule.onNodeWithTag("server-section-${section.name.lowercase()}")
            tab.assertHasClickAction()
            tab.assertIsDisplayed()
        }
        rule.onNodeWithText(string(R.string.server_section_catalogue)).performClick()
        waitForShown("the catalogue", hasText("Listed Title") and onShelf)
    }

    @Test
    fun onATabletTheTabsKeepToTheWidthOfTheLists() {
        serverHas(book(7, "Listed Title", 70))
        show(wide = true)
        signIn()
        waitFor("the tabs are shown") { rule.onAllNodesWithTag("server-sections").fetchSemanticsNodes().isNotEmpty() }
        val tabs = rule.onNodeWithTag("server-sections").getUnclippedBoundsInRoot()
        val home = rule.onNodeWithTag("server-home").getUnclippedBoundsInRoot()
        assertTrue("the tabs are narrower than the screen: $tabs within $home", tabs.width < home.width)
        // The bounds are in the points of the tabs' own density, the tablet's: no wider than lines are kept to.
        assertTrue("$tabs is $READABLE_WIDTH at most", tabs.width.value <= READABLE_WIDTH.value + 1)
        catalogue()
        waitForShown("the catalogue", hasText("Listed Title") and onShelf)
    }

    private fun bookOf(id: Long, title: String, format: String) =
        """{"id":$id,"title":"$title","authors":["Server Author"],"seriesName":null,"seriesIndex":null,"hasCover":false,
            "files":[{"id":${id * 10},"format":"$format","role":"primary","sizeBytes":100}]}"""

    @Test
    fun collectionOfAnOlderServerGoesOnPastPartsOfBooksTheAppDoesNotRead() {
        // Parts of fifty: the first all PDFs, the second one book the app reads, the third the last of them.
        val books = (1L..50L).map { bookOf(it, "Pdf $it", "pdf") } + bookOf(101, "Readable First", "fb2") +
            (201L..250L).map { bookOf(it, "Pdf $it", "pdf") } + bookOf(301, "Readable Last", "fb2")
        serverHas(*books.toTypedArray())
        server.collection("Mixed", *((1L..50L) + 101L + (201L..250L) + 301L).toLongArray())
        server.collectionQuery = false
        show()
        signIn()
        part(Section.Collections)
        waitForText("Mixed")
        rule.onNodeWithText("Mixed").performClick()

        // Nothing is asked for by the test: the list goes on by itself while its end is in sight.
        waitForShown("the book of the second part", hasText("Readable First") and hasAnyAncestor(hasTestTag("server-collection-books")))
        waitForShown("the book of the third part", hasText("Readable Last") and hasAnyAncestor(hasTestTag("server-collection-books")))
        waitFor("the collection is over") { model.state.value.collection?.let { !it.more && !it.loading && it.pages == 3 } == true }
        rule.onNodeWithText(string(R.string.server_collection_empty)).assertDoesNotExist()
    }

    @Test
    fun catalogueNarrowedByStatusLosesABookGivenAnotherAndPagesOnWithoutSkipping() {
        server.paged = true
        serverHas(*(1L..55L).map { bookOf(it, "Book %02d".format(it), "fb2") }.toTypedArray())
        for (id in 1L..55L) server.statuses[id] = "want_to_read"
        show()
        signIn()
        catalogue()
        waitForText("Book 01")
        rule.onNodeWithTag("server-filters-open").performClick()
        rule.onNode(hasText(string(R.string.server_status_want_to_read)) and hasAnyAncestor(hasTestTag("server-filters-sheet"))).performClick()
        // The button is at the end of the sheet, which scrolls on a short screen or with large text.
        rule.onNodeWithTag("server-filters-apply").performScrollTo().performClick()
        waitFor("the first part of the books wanted") { model.state.value.let { it.filters.status == "want_to_read" && !it.loading && it.books.size == 50 && it.more } }

        openPageOf("Book 03")
        waitForShown("what the reader keeps of it", hasTestTag("server-personal"))
        rule.onNodeWithTag("server-status-change").performScrollTo().performClick()
        rule.onNodeWithTag("server-status-read").performClick()
        waitFor("the status is saved") { server.statuses[3] == "read" && model.state.value.saving(3).isEmpty() }
        closePage()

        waitFor("the book is out of the list, which is asked for again") {
            model.state.value.let { state -> !state.loading && state.books.none { it.id == 3L } && state.books.size == 50 }
        }
        rule.onNode(hasText("Book 03") and onShelf).assertDoesNotExist()
        rule.onNodeWithTag("server-books").performScrollToKey(51L)
        waitFor("the rest of the books wanted") { model.state.value.let { !it.loading && !it.more && it.books.size == 54 } }
        assertEquals((1L..55L).filter { it != 3L }, model.state.value.books.map { it.id })
    }

    private fun pageScroll(): Float = rule.onNodeWithTag("server-book-scroll").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test
    fun wayBackFromABookBesideAnotherReturnsToWhereItsPageWasLeft() {
        serverHas(bookBy(7, "First Book", 70, "Shared Author"), bookBy(8, "Second Book", 80, "Shared Author"))
        server.descriptions[7] = (1..40).joinToString("") { "<p>Paragraph $it of what the first book is about, long enough to scroll by.</p>" }
        show()
        signIn()
        catalogue()
        waitForText("Second Book")
        openPageOf("First Book")
        val beside = hasText("Second Book") and onPage and hasAnyAncestor(hasTestTag("server-related-author-books"))
        // The page is measured once all of it is there: the whole description, and the books beside it.
        waitForShown("the whole description", hasText("Paragraph 40 of what the first book is about, long enough to scroll by.") and onPage)
        waitForShown("the book by the same author", beside)
        waitFor("the books beside it came") { model.state.value.let { it.detail?.id == 7L && it.related[Related.Author] is RelatedState.Shown } }
        rule.onNodeWithTag("server-related-author-books").performScrollTo()
        rule.waitForIdle()
        val left = pageScroll()
        assertTrue("the page was scrolled: $left", left > 0f)

        rule.onNode(beside).performClick()
        waitFor("the second book's page") { model.state.value.opened?.id == 8L && model.state.value.detail?.id == 8L }
        rule.waitForIdle()
        assertEquals("a page opened anew starts at its top", 0f, pageScroll(), 1f)

        closePageOnce()
        waitFor("back on the first book's page, all of it") {
            model.state.value.let { it.opened?.id == 7L && it.detail?.id == 7L && it.related[Related.Author] is RelatedState.Shown }
        }
        waitForShown("its book beside it again", beside)
        rule.waitForIdle()
        assertEquals("at the place it was left", left, pageScroll(), 1f)
    }

    @Test
    fun collectionNotMadeFromABooksPageIsToldThereWithItsNameKept() {
        serverHas(book(7, "Listed Title", 70))
        server.collection("Taken")
        show()
        signIn()
        catalogue()
        waitForText("Listed Title")
        openPageOf("Listed Title")
        waitForShown("what the reader keeps of it", hasTestTag("server-personal"))
        rule.onNodeWithTag("server-book-collections").performScrollTo().performClick()
        waitFor("the reader's collections") { model.state.value.membership?.loading == false }

        rule.onNodeWithTag("server-membership-new").performTextReplacement("Taken")
        rule.onNodeWithTag("server-membership-create").performClick()
        waitForShown("why it was not made", hasTestTag("server-membership-create-failure") and hasText(string(R.string.server_name_taken)))
        rule.onNodeWithTag("server-membership-new").assert(hasText("Taken"))
        assertEquals(1, server.collections.size)
    }

    @Test
    fun bookBesideABookThatIsInTheLibrarySaysSo() {
        serverHas(bookBy(7, "First Book", 70, "Shared Author"), bookBy(8, "Second Book", 80, "Shared Author"))
        server.downloads[80] = Answer(200, bookFile.readBytes(), "application/x-fictionbook+xml", mapOf("Content-Disposition" to "attachment; filename=\"second.fb2\""))
        show()
        signIn()
        catalogue()
        waitForText("Second Book")
        val second = model.state.value.books.first { it.id == 8L }
        rule.runOnUiThread { model.download(second, readableFiles(second).single()) }
        waitFor("the second book is in the library") { 8L in model.state.value.onDeviceByBook }

        openPageOf("First Book")
        val badge = hasText(string(R.string.catalog_downloaded)) and onPage and hasAnyAncestor(hasTestTag("server-related-author-books"))
        waitForShown("the book beside it said to be in the library", badge)
    }

    private companion object {
        // An emulator on a shared CI machine can stall for seconds; only a failing wait lasts this long.
        const val TIMEOUT = 30_000L

        /** How wide a tablet's screen is taken to be, in points. */
        const val WIDE_DP = 1000f
    }
}
