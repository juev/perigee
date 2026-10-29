package org.evsyukov.perigee.reader

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.ColorScheme
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.PageTurn
import org.evsyukov.perigee.data.ReaderPreferences
import org.evsyukov.perigee.data.ReaderPrefs
import org.evsyukov.perigee.data.TapScheme
import org.evsyukov.perigee.server.Server
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.FakeOrbit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.evsyukov.perigee.data.RemotePosition
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** The reading screen as the reader uses it: requirements R1 and R3. */
@RunWith(AndroidJUnit4::class)
class ReaderScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val testName = TestName()

    private val activity get() = rule.activity
    private val preferences by lazy { ReaderPreferences.get(activity) }
    private lateinit var book: File
    private lateinit var title: String

    @Before
    fun freshBookAndDefaultSettings() {
        runBlocking { preferences.update { ReaderPrefs() } }
        // The library knows a book by its contents, and it is the app's own, kept from run to run:
        // a title of its own makes every test, and every run of it, a new book.
        title = "${testName.methodName} ${System.nanoTime()}"
        book = TestBook.write(File(activity.cacheDir, "screen-test.fb2"), title = title)
    }

    private fun show() {
        edgeToEdge()
        rule.setContent { MaterialTheme { ReaderScreen(book, onClose = {}) } }
    }

    /** As in the app: the page has the whole screen from the start, whatever the system bars do. */
    private fun edgeToEdge() = rule.runOnUiThread { activity.enableEdgeToEdge() }

    /** A model made by the test, as with a server of its own; otherwise the one the screen makes. */
    private var made: ReaderViewModel? = null

    private fun model(): ReaderViewModel = made ?: ViewModelProvider(activity)[ReaderViewModel::class.java]

    private fun string(id: Int, vararg args: Any) = activity.getString(id, *args)

    private fun shownPage(): Int? = model().state.value.page?.index

    /** Waits for [done]; on a timeout, says where the model stood. */
    private fun waitFor(what: String, done: () -> Boolean) = try {
        rule.waitUntil(TIMEOUT, done)
    } catch (e: ComposeTimeoutException) {
        val state = model().state.value
        throw AssertionError(
            "$what; the page is ${state.page?.index} of ${state.page?.pageCount}, busy ${state.busy}, " +
                "the scheme ${model().prefs.value.colorScheme}, the menu ${rule.onAllNodesWithTag("position").fetchSemanticsNodes().isNotEmpty()}",
            e,
        )
    }

    private fun waitForPage(index: Int) = waitFor("page $index is shown") { shownPage() == index }

    private fun waitForBook() {
        waitFor("the book is shown") { model().state.value.let { it.page != null && !it.busy } }
        rule.waitForIdle()
    }

    private fun waitForTag(tag: String) =
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun tapPage(x: Float, y: Float) =
        rule.onNodeWithTag("page").performTouchInput { click(Offset(width * x, height * y)) }

    /** Turns forward by touch, one page at a time: touches faster than pages come are dropped. */
    private fun turnForward(times: Int, shown: () -> Int? = ::shownPage) = repeat(times) {
        val before = shown()!!
        tapPage(0.9f, 0.5f)
        rule.waitUntil(TIMEOUT) { shown() == before + 1 }
    }

    private fun openMenu() {
        tapPage(0.5f, 0.5f)
        // What a touch means is settled on the engine thread, a moment later.
        waitForTag("position")
    }

    // --- R1: the book opens, turns, and is found where it was left

    @Test
    fun bookOpensOnItsFirstPage() {
        show()
        waitForBook()
        assertEquals(0, shownPage())
        val pages = model().state.value.page!!.pageCount
        rule.onNodeWithContentDescription(string(R.string.page_description, 1, pages)).assertIsDisplayed()
    }

    @Test
    fun touchingTheRightPartTurnsForwardAndTheLeftPartBack() {
        show()
        waitForBook()
        tapPage(0.9f, 0.5f)
        waitForPage(1)
        tapPage(0.9f, 0.9f)
        waitForPage(2)
        tapPage(0.1f, 0.5f)
        waitForPage(1)
    }

    @Test
    fun swipingTurnsThePage() {
        show()
        waitForBook()
        rule.onNodeWithTag("page").performTouchInput { swipeLeft() }
        waitForPage(1)
        rule.onNodeWithTag("page").performTouchInput { swipeRight() }
        waitForPage(0)
    }

    @Test
    fun withVerticalTurningSwipingUpGoesForward() {
        runBlocking { preferences.update { it.copy(pageTurn = PageTurn.Vertical) } }
        show()
        waitForBook()
        rule.onNodeWithTag("page").performTouchInput { swipeUp() }
        waitForPage(1)
        rule.onNodeWithTag("page").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals("a sideways swipe does nothing then", 1, shownPage())
    }

    @Test
    fun rotationKeepsTheBookOpenAtItsPlace() {
        show()
        waitForBook()
        turnForward(2)
        val position = model().state.value.page!!.xpointer

        rule.activityRule.scenario.recreate()
        show()
        waitForBook()
        rule.waitUntil(TIMEOUT) { model().state.value.page?.xpointer == position }
        assertEquals(2, shownPage())
    }

    @Test
    fun resizingTheScreenKeepsThePlaceAndTheControls() {
        var inset by mutableStateOf(0.dp)
        edgeToEdge()
        rule.setContent {
            MaterialTheme { Box(Modifier.padding(bottom = inset)) { ReaderScreen(book, onClose = {}) } }
        }
        waitForBook()
        turnForward(3)
        val height = model().state.value.page!!.bitmap.height

        // As a split screen or a keyboard would: the area shrinks and comes back.
        repeat(4) {
            inset = 120.dp
            rule.waitUntil(TIMEOUT) { model().state.value.page!!.bitmap.height < height }
            inset = 0.dp
            rule.waitUntil(TIMEOUT) { model().state.value.page!!.bitmap.height == height }
        }
        assertEquals("back on the page the reader was at", 3, shownPage())

        tapPage(0.9f, 0.5f)
        waitForPage(4)
        openMenu()
    }

    @Test
    fun touchingTheMiddleShowsTheMenuWithTitleAndPosition() {
        show()
        waitForBook()
        openMenu()
        val pages = model().state.value.page!!.pageCount
        rule.onNodeWithTag("position").assertTextEquals(string(R.string.page_of, 1, pages))
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.onNodeWithTag("chapter").assertTextEquals("Chapter 1")

        tapPage(0.5f, 0.2f)
        // What a touch means is settled on the engine thread, a moment later.
        waitFor("the menu is closed") { rule.onAllNodesWithTag("position").fetchSemanticsNodes().isEmpty() }
        assertEquals("the touch that closed the menu did not turn the page", 0, shownPage())
    }

    @Test
    fun touchThatMissesAButtonOfTheMenuStaysOnTheMenu() {
        show()
        waitForBook()
        openMenu()
        // On the title, between the buttons: not a button, and not the page either.
        rule.onNodeWithTag("top-bar").performTouchInput { click(Offset(width * 0.4f, height * 0.5f)) }
        rule.onNodeWithTag("position").performTouchInput { click() }
        rule.waitForIdle()
        rule.onNodeWithTag("position").assertIsDisplayed()
        assertEquals(0, shownPage())

        rule.onNodeWithContentDescription(string(R.string.action_contents)).performClick()
        rule.onNodeWithText("Chapter 3").assertIsDisplayed()
    }

    @Test
    fun otherTurnSchemesFollowTheirOwnZones() {
        runBlocking { preferences.update { it.copy(tapScheme = TapScheme.TopBottom) } }
        show()
        waitForBook()
        tapPage(0.1f, 0.9f)
        waitForPage(1)
        tapPage(0.9f, 0.2f)
        waitForPage(0)

        runBlocking { preferences.update { it.copy(tapScheme = TapScheme.Off) } }
        rule.waitUntil(TIMEOUT) { model().prefs.value.tapScheme == TapScheme.Off }
        tapPage(0.9f, 0.9f)
        rule.waitForIdle()
        assertEquals("touches do not turn pages when switched off", 0, shownPage())
    }

    @Test
    fun contentsLeadToAChapterAndBackReturns() {
        show()
        waitForBook()
        openMenu()
        rule.onNodeWithContentDescription(string(R.string.action_contents)).performClick()
        rule.onNodeWithText("Chapter 3").performClick()

        val third = model().state.value.toc[2]
        waitForPage(third.page)
        assertEquals("Chapter 3", model().state.value.chapter?.title)

        rule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        waitForPage(0)
        assertTrue("nothing left to return to", !model().state.value.canGoBack)
    }

    @Test
    fun warmFilterTintsThePageDuringItsHours() {
        show()
        waitForBook()
        fun corner() = rule.onNodeWithTag("page").captureToImage().toPixelMap()[2, 2]
        assertEquals("a white page before", 1f, corner().blue, 0.02f)
        val hour = LocalTime.now().hour * 60
        model().updatePrefs { it.copy(warmFilter = true, warmFromMinute = hour, warmToMinute = (hour + 120) % (24 * 60), warmStrength = 5) }
        rule.waitUntil(TIMEOUT) { corner().blue < 0.5f }
        val tinted = corner()
        assertEquals("the page keeps its red", 1f, tinted.red, 0.02f)
        assertTrue("and loses more blue than green: $tinted", tinted.blue < tinted.green && tinted.green < 0.8f)
    }

    @Test
    fun topLeftCornerSwitchesDayAndNight() {
        show()
        waitForBook()
        tapPage(0.05f, 0.03f)
        waitFor("the scheme is Night") { model().prefs.value.colorScheme == ColorScheme.Night }
        // The page is redrawn in the new colours; touches made meanwhile are dropped.
        val night = 0xFF000000.toInt() or ReaderViewModel.colorsOf(ColorScheme.Night).background
        waitFor("the page is drawn at night") { model().state.value.page?.bitmap?.getPixel(2, 2) == night }
        waitForBook()
        tapPage(0.05f, 0.03f)
        waitFor("the scheme is Day again") { model().prefs.value.colorScheme == ColorScheme.Day }
        assertEquals(0, shownPage())
    }

    @Test
    fun searchListsMatchesAndGoesToTheOneChosen() {
        show()
        waitForBook()
        openMenu()
        rule.onNodeWithContentDescription(string(R.string.action_search)).performClick()
        rule.onNodeWithTag("search-field").performTextInput(TestBook.RARE_WORD)
        rule.onNodeWithTag("search-field").performImeAction()

        rule.waitUntil(TIMEOUT) { model().state.value.search.hits != null }
        val hit = model().state.value.search.hits!!.single()
        rule.onNodeWithText(string(R.string.search_found, 1)).assertIsDisplayed()
        rule.onNodeWithTag("search-results").performTouchInput { click(Offset(width / 2f, 40f)) }
        waitForPage(hit.page)
        assertEquals("Chapter 2", model().state.value.chapter?.title)
    }

    @Test
    fun largerFontFromSettingsReflowsTheBook() {
        show()
        waitForBook()
        val pagesBefore = model().state.value.page!!.pageCount
        openMenu()
        rule.onNodeWithContentDescription(string(R.string.action_settings)).performClick()
        rule.onNodeWithTag("font-size").assertTextEquals(ReaderPrefs().fontSize.toString())
        val charsBefore = model().state.value.charsPerLine!!
        rule.onNodeWithTag("chars-per-line").assertTextEquals(string(R.string.chars_per_line, charsBefore))
        repeat(4) { rule.onNodeWithTag("font-size-more").performClick() }

        // Each press is stored first and shown when the stored value comes back.
        rule.waitUntil(TIMEOUT) { model().prefs.value.fontSize == ReaderPrefs().fontSize + 4 }
        rule.onNodeWithTag("font-size").assertTextEquals((ReaderPrefs().fontSize + 4).toString())
        rule.waitUntil(TIMEOUT) { model().state.value.page!!.pageCount > pagesBefore && model().state.value.charsPerLine!! < charsBefore }
        rule.onNodeWithTag("chars-per-line").assertTextEquals(string(R.string.chars_per_line, model().state.value.charsPerLine!!))
        assertEquals("the setting is stored", ReaderPrefs().fontSize + 4, runBlocking { preferences.prefs.first() }.fontSize)
    }

    @Test
    fun settingsLeaveThePageInViewWhileTheyChangeIt() {
        show()
        waitForBook()
        openMenu()
        rule.onNodeWithContentDescription(string(R.string.action_settings)).performClick()
        waitForTag("settings")
        rule.waitForIdle()
        // The sheet is a window of its own: both are measured on the screen, whose bottom is the page's.
        val page = rule.onNodeWithTag("page").fetchSemanticsNode()
        val half = (page.positionOnScreen.y + page.size.height) * (1 - SETTINGS_SHARE)
        fun settingsTop() = rule.onNodeWithTag("settings-sheet").fetchSemanticsNode().positionOnScreen.y
        assertTrue("the settings keep below the upper half: ${settingsTop()} vs $half", settingsTop() >= half - 1)

        // A setting at the end of the list is reached by scrolling the settings, not by raising them over the page.
        rule.onNodeWithText(string(R.string.setting_page_margins)).performScrollTo().assertIsDisplayed()
        rule.waitForIdle()
        assertTrue("the settings stay below the upper half after a scroll", settingsTop() >= half - 1)

        // A change shows on the page with the settings still open.
        val pagesBefore = model().state.value.page!!.pageCount
        rule.onNodeWithTag("font-size").performScrollTo()
        repeat(2) { rule.onNodeWithTag("font-size-more").performClick() }
        rule.waitUntil(TIMEOUT) { model().state.value.page!!.pageCount > pagesBefore }
        rule.onNodeWithTag("settings").assertIsDisplayed()
    }

    @Test
    fun heldWordBecomesAQuoteWithANote() {
        show()
        waitForBook()
        rule.onNodeWithTag("page").performTouchInput { longClick(Offset(width * 0.5f, height * 0.5f)) }
        rule.waitUntil(TIMEOUT) { model().state.value.selection != null }
        val word = model().state.value.selection!!.range.text
        // Where the word is on the page, to touch it again once it is a quote.
        val place = model().state.value.selection!!.rects.first()
        val onWord = Offset(place.exactCenterX(), place.exactCenterY())
        assertTrue("one word is selected: '$word'", word.isNotBlank() && ' ' !in word.trim())
        waitForTag("selection-bar")

        rule.onNodeWithContentDescription(string(R.string.action_note)).performClick()
        rule.onNodeWithTag("note-field").performTextInput("worth remembering")
        rule.onNodeWithText(string(R.string.action_save)).performClick()

        rule.waitUntil(TIMEOUT) { model().state.value.quotes.size == 1 }
        val quote = model().state.value.quotes.single()
        assertEquals(word, quote.text)
        assertEquals("worth remembering", quote.note)
        assertNull("the selection is done with", model().state.value.selection)

        // Touching the quote brings its actions back: a star, and deleting.
        rule.onNodeWithTag("page").performTouchInput { click(onWord) }
        rule.waitUntil(TIMEOUT) { model().state.value.quoteTouch != null }
        rule.onNodeWithContentDescription(string(R.string.action_star)).performClick()
        waitFor("the quote has a star") { model().state.value.quotes.single().starred }
        rule.onNodeWithTag("page").performTouchInput { click(onWord) }
        rule.waitUntil(TIMEOUT) { model().state.value.quoteTouch != null }
        assertNotNull(model().state.value.quoteTouch)
        rule.onNodeWithContentDescription(string(R.string.action_unstar)).assertIsDisplayed()
        rule.onNodeWithContentDescription(string(R.string.action_delete)).performClick()
        rule.waitUntil(TIMEOUT) { model().state.value.quotes.isEmpty() }
        assertEquals("touching a quote does not turn the page", 0, shownPage())
    }

    @Test
    fun fingerHeldAndMovedStretchesTheSelectionOverLines() {
        show()
        waitForBook()
        val pageBefore = shownPage()
        rule.onNodeWithTag("page").performTouchInput {
            down(Offset(width * 0.3f, height * 0.4f))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            // In small steps, as a finger moves, to a line further down.
            repeat(10) { step -> moveTo(Offset(width * (0.3f + 0.04f * (step + 1)), height * (0.4f + 0.01f * (step + 1)))) }
            up()
        }
        waitFor("the selection takes more than one line") { (model().state.value.selection?.rects?.size ?: 0) > 1 }
        val text = model().state.value.selection!!.range.text
        assertTrue("several words are selected: '$text'", text.trim().contains(' '))
        assertEquals("stretching a selection does not turn the page", pageBefore, shownPage())
    }

    @Test
    fun handlesMoveTheEndsOfTheSelectionAndTheQuoteTakesItAll() {
        show()
        waitForBook()
        val pageBefore = shownPage()
        rule.onNodeWithTag("page").performTouchInput { longClick(Offset(width * 0.5f, height * 0.4f)) }
        waitFor("a word is selected") { model().state.value.selection != null }
        val word = model().state.value.selection!!.range.text.trim()
        waitForTag("selection-end")

        // The end handle is dragged a few lines down: the selection follows it over the lines.
        rule.onNodeWithTag("selection-end").performTouchInput { swipe(center, center + Offset(0f, height * 2f), 500) }
        waitFor("the selection takes more than one line") { (model().state.value.selection?.rects?.size ?: 0) > 1 }
        val stretched = model().state.value.selection!!.range.text
        assertTrue("the selection starts with the word held: '$stretched'", stretched.trim().startsWith(word))

        // The start handle is dragged to the left: the selection gains words before, and keeps its end.
        rule.onNodeWithTag("selection-start").performTouchInput { swipe(center, center - Offset(width * 3f, 0f), 500) }
        waitFor("the selection starts earlier") { model().state.value.selection!!.range.text.length > stretched.length }
        val whole = model().state.value.selection!!.range.text
        assertTrue("the end stays where it was: '$whole'", whole.trimEnd().endsWith(stretched.trimEnd()))
        assertEquals("handles do not turn the page", pageBefore, shownPage())

        rule.onNodeWithContentDescription(string(R.string.action_note)).performClick()
        rule.onNodeWithTag("note-field").performTextInput("the whole phrase")
        rule.onNodeWithText(string(R.string.action_save)).performClick()
        waitFor("the quote is kept") { model().state.value.quotes.size == 1 }
        assertEquals(whole, model().state.value.quotes.single().text)
    }

    @Test
    fun colourDotTurnsTheSelectionIntoAQuoteOfThatColour() {
        show()
        waitForBook()
        rule.onNodeWithTag("page").performTouchInput { longClick(Offset(width * 0.5f, height * 0.4f)) }
        rule.waitUntil(TIMEOUT) { model().state.value.selection != null }
        waitForTag("selection-bar")
        rule.onNodeWithContentDescription(string(R.string.quote_color, 2)).performClick()
        rule.waitUntil(TIMEOUT) { model().state.value.quotes.size == 1 }
        assertEquals(QUOTE_COLORS[1], model().state.value.quotes.single().color)
    }

    @Test
    fun unreadableFileSaysSo() {
        book = File(activity.cacheDir, "no-such-book.epub").apply { delete() }
        show()
        rule.waitUntil(TIMEOUT) { model().state.value.failed }
        rule.onNodeWithText(string(R.string.cannot_open)).assertIsDisplayed()
    }

    // --- R7, R9: a later place from elsewhere is offered, and the time on pages is kept for the server

    private val library get() = Library.get(activity)

    private fun bookId(): String = model().state.value.book!!.id

    private fun offered(position: RemotePosition) {
        runBlocking { library.offer(position) }
        waitForTag("offer")
    }

    /** Where the reader stands with a place from elsewhere is checked by ReaderViewModelTest; here, the question itself. */
    @Test
    fun placeFromElsewhereIsOfferedAndGoneTo() {
        show()
        waitForBook()
        // A place further on, as another device would name it.
        turnForward(5)
        val there = model().state.value.page!!.xpointer
        rule.runOnUiThread { model().goToProgress(0f) }
        waitForPage(0)
        offered(RemotePosition(bookId(), there, 0.3f, "web", at = 100))
        rule.onNodeWithText(string(R.string.offer_text, 30, "web")).assertIsDisplayed()

        rule.onNodeWithText(string(R.string.offer_go)).performClick()

        waitForPage(5)
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithTag("offer").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun quoteMadeElsewhereAppearsInTheOpenBook() {
        val library = Library.inMemory(activity)
        val accounts = Accounts.of(library)
        val orbit = BookOrbit(accounts, library, "0.0.1")
        FakeOrbit().use { server ->
            runBlocking {
                orbit.signIn(server.url, "reader", "right-password")
                // The server has the book: the library knows it by its file, as the screen will open it.
                val known = library.open(book, title, "")
                server.files[known.id] = 7L to 70L
                library.onServer(known.id, 7, 70)
                server.webQuote(known.id, "/FictionBook/body/section/p[2]/text().0", "/FictionBook/body/section/p[2]/text().5", "Words from the web")
            }
            val model = ReaderViewModel(activity.application, library) { Server.bookOpened(accounts, orbit, it) }
            made = model
            edgeToEdge()
            rule.setContent { MaterialTheme { ReaderScreen(book, onClose = {}, viewModel = model) } }
            waitForBook()

            waitFor("the quote from the server is in the book") { model.state.value.quotes.any { it.text == "Words from the web" } }
            openMenu()
            rule.onNodeWithContentDescription(string(R.string.action_contents)).performClick()
            rule.onNodeWithText(string(R.string.tab_quotes)).performClick()
            rule.onNodeWithText("Words from the web").assertIsDisplayed()
        }
    }

    private companion object {
        // An emulator on a shared CI machine can stall for seconds; only a failing wait lasts this long.
        const val TIMEOUT = 30_000L
    }
}
