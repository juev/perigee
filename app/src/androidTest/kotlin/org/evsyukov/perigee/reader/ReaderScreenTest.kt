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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.ColorScheme
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.PageTurn
import org.evsyukov.perigee.data.ReaderPreferences
import org.evsyukov.perigee.data.ReaderPrefs
import org.evsyukov.perigee.data.TapScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Before
    fun freshBookAndDefaultSettings() {
        runBlocking { preferences.update { ReaderPrefs() } }
        // The library knows a book by its contents: a title of its own makes every test a new book.
        book = TestBook.write(File(activity.cacheDir, "screen-test.fb2"), title = testName.methodName)
    }

    private fun show() {
        edgeToEdge()
        rule.setContent { MaterialTheme { ReaderScreen(book, onClose = {}) } }
    }

    /** As in the app: the page has the whole screen from the start, whatever the system bars do. */
    private fun edgeToEdge() = rule.runOnUiThread { activity.enableEdgeToEdge() }

    private fun model(): ReaderViewModel = ViewModelProvider(activity)[ReaderViewModel::class.java]

    private fun string(id: Int, vararg args: Any) = activity.getString(id, *args)

    private fun shownPage(): Int? = model().state.value.page?.index

    private fun waitForPage(index: Int) = rule.waitUntil(TIMEOUT) { shownPage() == index }

    private fun waitForBook() {
        rule.waitUntil(TIMEOUT) { model().state.value.page != null }
        rule.waitForIdle()
    }

    private fun waitForTag(tag: String) =
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun tapPage(x: Float, y: Float) =
        rule.onNodeWithTag("page").performTouchInput { click(Offset(width * x, height * y)) }

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
        tapPage(0.9f, 0.5f)
        tapPage(0.9f, 0.5f)
        waitForPage(2)
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
        repeat(3) { tapPage(0.9f, 0.5f) }
        waitForPage(3)
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
    fun bookReopensWhereItWasLeft() {
        var key by mutableStateOf("first")
        edgeToEdge()
        rule.setContent { MaterialTheme { ReaderScreen(book, onClose = {}, viewModel = viewModel(key = key)) } }
        fun keyed() = ViewModelProvider(activity)[key, ReaderViewModel::class.java]
        rule.waitUntil(TIMEOUT) { keyed().state.value.page != null }
        repeat(3) { tapPage(0.9f, 0.5f) }
        rule.waitUntil(TIMEOUT) { keyed().state.value.page?.index == 3 }
        val left = keyed().state.value.page!!.xpointer
        // The position is written to the library right after the page is shown.
        rule.waitUntil(TIMEOUT) { runBlocking { Library.get(activity).lastOpened() }?.xpointer == left }

        // A new model knows nothing of the old one, like the app started again.
        key = "second"
        rule.waitUntil(TIMEOUT) { keyed().state.value.page != null }
        val reopened = keyed().state.value.page!!
        assertEquals(left, reopened.xpointer)
        assertEquals(3, reopened.index)
    }

    // --- R3: controls

    @Test
    fun touchingTheMiddleShowsTheMenuWithTitleAndPosition() {
        show()
        waitForBook()
        openMenu()
        val pages = model().state.value.page!!.pageCount
        rule.onNodeWithTag("position").assertTextEquals(string(R.string.page_of, 1, pages))
        rule.onNodeWithText(testName.methodName).assertIsDisplayed()
        rule.onNodeWithTag("chapter").assertTextEquals("Chapter 1")

        tapPage(0.5f, 0.2f)
        rule.onNodeWithTag("position").assertDoesNotExist()
        assertEquals("the touch that closed the menu did not turn the page", 0, shownPage())
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
    fun topRightCornerSetsAndClearsABookmark() {
        show()
        waitForBook()
        tapPage(0.9f, 0.5f)
        waitForPage(1)

        tapPage(0.95f, 0.03f)
        rule.waitUntil(TIMEOUT) { model().state.value.pageBookmark != null }
        assertEquals("the corner did not turn the page", 1, shownPage())

        // The bookmark is listed and leads back to its page.
        tapPage(0.1f, 0.5f)
        waitForPage(0)
        assertNull(model().state.value.pageBookmark)
        openMenu()
        rule.onNodeWithContentDescription(string(R.string.action_contents)).performClick()
        rule.onNodeWithText(string(R.string.tab_bookmarks)).performClick()
        rule.onNodeWithText("Chapter 1").performClick()
        waitForPage(1)

        tapPage(0.95f, 0.03f)
        rule.waitUntil(TIMEOUT) { model().state.value.pageBookmark == null && model().state.value.bookmarks.isEmpty() }
    }

    @Test
    fun topLeftCornerSwitchesDayAndNight() {
        show()
        waitForBook()
        tapPage(0.05f, 0.03f)
        rule.waitUntil(TIMEOUT) { model().prefs.value.colorScheme == ColorScheme.Night }
        tapPage(0.05f, 0.03f)
        rule.waitUntil(TIMEOUT) { model().prefs.value.colorScheme == ColorScheme.Day }
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
        repeat(4) { rule.onNodeWithTag("font-size-more").performClick() }

        // Each press is stored first and shown when the stored value comes back.
        rule.waitUntil(TIMEOUT) { model().prefs.value.fontSize == ReaderPrefs().fontSize + 4 }
        rule.onNodeWithTag("font-size").assertTextEquals((ReaderPrefs().fontSize + 4).toString())
        rule.waitUntil(TIMEOUT) { model().state.value.page!!.pageCount > pagesBefore }
        assertEquals("the setting is stored", ReaderPrefs().fontSize + 4, runBlocking { preferences.prefs.first() }.fontSize)
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

        // Touching the quote brings its actions back; deleting removes it.
        rule.onNodeWithTag("page").performTouchInput { click(onWord) }
        rule.waitUntil(TIMEOUT) { model().state.value.quoteTouch != null }
        assertNotNull(model().state.value.quoteTouch)
        rule.onNodeWithContentDescription(string(R.string.action_delete)).performClick()
        rule.waitUntil(TIMEOUT) { model().state.value.quotes.isEmpty() }
        assertEquals("touching a quote does not turn the page", 0, shownPage())
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

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
