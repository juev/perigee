package org.evsyukov.perigee.library

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.MainActivity
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LibraryPreferences
import org.evsyukov.perigee.data.Shelf
import org.evsyukov.perigee.reader.ReaderViewModel
import org.evsyukov.perigee.reader.TestBook
import org.evsyukov.perigee.share.Sender
import org.evsyukov.perigee.share.Sender.Mode
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Books shared from another app reach the library and the reader through the
 * system, as from a file manager: the sharing app is the test package, in a
 * process and under a user ID of its own (`Sender`), and Perigee reads its
 * files only with the right the share grants.
 */
@RunWith(AndroidJUnit4::class)
class SharedBooksTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    @get:Rule
    val testName = TestName()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val library get() = Library.get(context)
    private val preferences get() = LibraryPreferences.get(context)
    private val sources = File(context.cacheDir, "shared-books").apply { deleteRecursively() }
    private val kept = File(context.filesDir, "books")
    private val lastBook = MainActivity.lastBook
    private lateinit var shelf: Shelf

    @Before
    fun closeTheApps() {
        shelf = runBlocking { preferences.prefs.first().shelf }
        closeAll()
    }

    @After
    fun putBack() {
        MainActivity.lastBook = lastBook
        closeAll()
        runBlocking { preferences.update { it.copy(shelf = shelf) } }
    }

    // --- Files as the other app has them

    /** A title no run of any test has used: the library is the app's own and is kept from run to run. */
    private fun title(what: String) = "$what ${testName.methodName} ${System.nanoTime()}"

    private fun source(name: String) = File(sources, "${UUID.randomUUID()}/$name").apply { parentFile!!.mkdirs() }

    private fun fb2(title: String, author: String = "Test Author", series: String? = null, number: Int = 0) =
        TestBook.write(source("book.fb2"), title, author, series, number)

    // --- The apps as they are shown

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    /**
     * Waits through the rule: while a test of Compose runs, the screens of
     * every activity are composed on its clock, which only its waiting moves.
     * A thread that merely sleeps would leave them as they were.
     */
    private fun waitFor(what: String, done: () -> Boolean) = try {
        rule.waitUntil(TIMEOUT, done)
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("waited in vain: $what; ${state()}", e)
    }

    /** Where the apps stand, for a wait that ends in vain. */
    private fun state(): String {
        val resumed = activities(Stage.RESUMED).map { it.javaClass.simpleName }
        val reader = mains(*living).singleOrNull()?.let { main ->
            reader(main).state.value.let { "the reader has ${it.book?.title}, page ${it.page?.index}, busy ${it.busy}, failed ${it.failed}" }
        } ?: "no single reader's screen"
        val ours = books().filter { testName.methodName in it.title }.map { "${it.title} (opened ${it.openedAt})" }
        return "resumed $resumed; $reader; shares waiting ${SharedBooks.shares.value.size}; this test's books $ours"
    }

    private val living = Stage.values().filter { it != Stage.DESTROYED }.toTypedArray()

    /** The app's activities in these stages; in the app's process, so the sharing app's are not among them. */
    private fun activities(vararg stages: Stage): List<Activity> {
        var found = emptyList<Activity>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            found = stages.flatMap { monitor.getActivitiesInStage(it) }
        }
        return found
    }

    private fun mains(vararg stages: Stage) = activities(*stages).filterIsInstance<MainActivity>()

    private fun shares(vararg stages: Stage) = activities(*stages).filterIsInstance<ShareActivity>()

    /** The activity a test puts over the reader's screen, as a file being picked or a page of signing in. */
    private fun standIns(vararg stages: Stage) = activities(*stages).filter { it.javaClass == ComponentActivity::class.java }

    /** The reader's screen once it is in sight; a second one, or one that is not gone, is waited out in vain. */
    private fun main(): MainActivity {
        waitFor("one reader's screen is shown") { mains(Stage.RESUMED).size == 1 && mains(*living).size == 1 }
        return mains(Stage.RESUMED).single()
    }

    private fun closeAll() {
        Sender.forget()
        for (task in context.getSystemService(ActivityManager::class.java).appTasks) runCatching { task.finishAndRemoveTask() }
        val open = activities(*living)
        instrumentation.runOnMainSync { open.forEach { it.finish() } }
        waitFor("the app is closed") { activities(*living).isEmpty() }
        SharedBooks.takeAll()
    }

    /** The intent the launcher starts the app with. */
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(context, MainActivity::class.java))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    private fun reader(activity: MainActivity): ReaderViewModel {
        var model: ReaderViewModel? = null
        instrumentation.runOnMainSync { model = ViewModelProvider(activity)[ReaderViewModel::class.java] }
        return model!!
    }

    private fun shown(activity: MainActivity): String? = reader(activity).state.value.let { if (it.page != null && !it.busy) it.book?.title else null }

    private fun waitForBook(activity: MainActivity, title: String) = waitFor("the reader shows $title") { shown(activity) == title }

    /**
     * The system's Back, pressed once the screen can take it: settled, and its window holding the
     * input focus. Being in front is not enough: a window of the system may hold the focus for a
     * while, as the notice it shows over a screen that hides its bars, and a key goes to that window.
     */
    private fun pressBack(activity: MainActivity) {
        rule.waitForIdle()
        waitFor("the reader's screen has the input focus") {
            var focused = false
            instrumentation.runOnMainSync { focused = activity.hasWindowFocus() }
            focused
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }

    private fun turn(activity: MainActivity) {
        val model = reader(activity)
        val before = model.state.value.page!!.index
        instrumentation.runOnMainSync { model.turn(1) }
        waitFor("the next page is shown") { model.state.value.page?.index == before + 1 }
    }

    /** Waits for the text on any screen; while no screen of the app is up, as between the apps, there is none yet. */
    private fun waitForText(text: String) =
        waitFor("\"$text\" is shown") { rule.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }

    private fun books(): List<Book> = runBlocking { library.books().first() }

    private fun book(title: String): Book = books().single { it.title == title }

    private fun newCopies(before: Set<String>) = kept.list().orEmpty().toSet() - before

    // --- The share menu

    private fun offered(action: String, type: String): Set<String> {
        @Suppress("DEPRECATION") // The flags as a number are what API 30 has.
        val found = context.packageManager.queryIntentActivities(
            Intent(action).setType(type).setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY,
        )
        return found.map { it.activityInfo.name }.toSet()
    }

    @Test
    fun theShareMenuOffersTheAppForBookFilesAndNotForTextOrOtherFiles() {
        val share = setOf(ShareActivity::class.java.name)
        val books = listOf(
            "application/epub+zip", "application/x-fictionbook+xml", "application/x-zip-compressed-fb2",
            "application/x-mobipocket-ebook", "application/zip", "application/octet-stream",
        )
        for (type in books) {
            assertEquals("one file of $type", share, offered(Intent.ACTION_SEND, type))
            assertEquals("several files of $type", share, offered(Intent.ACTION_SEND_MULTIPLE, type))
        }
        assertEquals("several files of different types", share, offered(Intent.ACTION_SEND_MULTIPLE, "application/*"))
        for (type in listOf("text/plain", "text/html", "image/jpeg", "application/pdf", "video/mp4")) {
            assertEquals(type, emptySet<String>(), offered(Intent.ACTION_SEND, type))
            assertEquals("several of $type", emptySet<String>(), offered(Intent.ACTION_SEND_MULTIPLE, type))
        }
    }

    // --- One book

    @Test
    fun bookSharedWithTheAppClosedStartsItOnThatBookInATaskTheLauncherBringsBack() {
        val left = runBlocking { library.open(fb2(title("Left Open")), "", "") }
        val title = title("Shared Saga")
        val file = fb2(title, author = "Shared Author", series = "Shared Saga", number = 3)
        val bytes = file.readBytes()
        val uri = Sender.stage(file, "Shared Saga 3.fb2")

        Sender.send("application/x-fictionbook+xml", uri)

        val main = main()
        waitForBook(main, title)
        val book = book(title)
        assertEquals(listOf("Shared Author"), book.authors)
        assertEquals(listOf("Shared Saga", "3", "fb2"), listOf(book.series, book.seriesNumber, book.format))
        assertEquals(File(kept, "${book.id}.fb2").path, book.path)
        assertArrayEquals(bytes, File(book.path).readBytes())
        assertEquals(1, Sender.opens(uri))
        assertEquals("the book left open was not opened on the way", left.openedAt, runBlocking { library.book(left.id) }!!.openedAt)
        assertTrue("the sharing app stays where it was", Sender.alive())
        waitFor("the share has gone") { shares(*living).isEmpty() }

        // Started as the launcher starts it: the launcher brings this task back, not a second reader's screen.
        val task = context.getSystemService(ActivityManager::class.java).appTasks.single()
        assertEquals(Intent.ACTION_MAIN, task.taskInfo.baseIntent.action)
        assertTrue(task.taskInfo.baseIntent.hasCategory(Intent.CATEGORY_LAUNCHER))
        instrumentation.runOnMainSync { main.moveTaskToBack(true) }
        waitFor("the app is put away") { mains(Stage.RESUMED).isEmpty() }
        context.startActivity(launcher())
        assertSame(main, main())
        waitForBook(main, title)

        // Made anew, as on a change of language or of the dark theme, the app does not take the share again.
        instrumentation.runOnMainSync { main.recreate() }
        waitFor("the reader's screen is made anew") { mains(Stage.RESUMED).singleOrNull()?.let { it !== main } == true }
        waitForBook(main(), title)
        assertEquals(1, Sender.opens(uri))
        assertEquals(1, books().count { it.title == title })
    }

    @Test
    fun bookSharedWhileAnotherIsReadOpensOnTheSameScreenAndBackLeadsToTheSharingApp() {
        val firstTitle = title("First")
        Sender.send("application/x-fictionbook+xml", Sender.stage(fb2(firstTitle), "First.fb2"))
        val main = main()
        waitForBook(main, firstTitle)
        turn(main)
        val first = reader(main).state.value.book!!.id
        waitFor("the place is kept") { runBlocking { library.book(first) }!!.progress > 0f }
        val place = runBlocking { library.book(first) }!!

        val secondTitle = title("Second")
        val epub = TestBook.epub(source("second.epub"), secondTitle, author = "Epub Author", series = "Epub Saga", seriesNumber = 2)
        Sender.send("application/epub+zip", Sender.stage(epub, "Second.epub"))

        waitForBook(main, secondTitle)
        assertSame("the share reached the screen that was open", main, main())
        waitFor("the share has gone") { shares(*living).isEmpty() }
        val second = book(secondTitle)
        assertEquals(listOf("Epub Author"), second.authors)
        assertEquals(listOf("Epub Saga", "2", "epub"), listOf(second.series, second.seriesNumber, second.format))
        val after = runBlocking { library.book(first) }!!
        assertEquals(place.xpointer, after.xpointer)
        assertEquals(place.progress, after.progress)

        // Back closes the book, then leaves the app: the sharing app, in its own task, is where it was.
        val resumes = Sender.resumes()
        pressBack(main)
        waitFor("the book is closed") { reader(main).state.value.book == null }
        pressBack(main)
        waitFor("the sharing app is back") { Sender.resumes() > resumes }
        assertTrue(Sender.alive())
    }

    @Test
    fun bookSharedWhileAFileIsPickedWaitsAndLeavesThePickingOpen() {
        context.startActivity(launcher())
        val main = main()
        instrumentation.runOnMainSync { main.startActivity(Intent(main, ComponentActivity::class.java)) }
        waitFor("the stand-in is over the reader's screen") { standIns(Stage.RESUMED).size == 1 }
        val standIn = standIns(Stage.RESUMED).single()
        val title = title("Shared Under A Picker")

        Sender.send("application/x-fictionbook+xml", Sender.stage(fb2(title), "Under.fb2"))

        waitFor("the book is taken in") { books().any { it.title == title } && shares(*living).isEmpty() }
        waitFor("the app's task is in front, as it was") { standIns(Stage.RESUMED).singleOrNull() === standIn }
        assertTrue("the share waits for the screen", SharedBooks.shares.value.isNotEmpty())
        assertEquals(listOf(main), mains(*living))
        assertTrue(mains(Stage.RESUMED).isEmpty())
        assertNotEquals(title, reader(main).state.value.book?.title)

        instrumentation.runOnMainSync { standIn.finish() }

        assertSame(main, main())
        waitForBook(main, title)
        assertTrue(SharedBooks.shares.value.isEmpty())
    }

    @Test
    fun bookSharedWhileTheLastOneIsLookedUpStaysOpen() {
        val left = runBlocking { library.open(fb2(title("Left Open")), "", "") }
        val gate = CompletableDeferred<Unit>()
        val lookedUp = CountDownLatch(1)
        MainActivity.lastBook = {
            gate.await()
            left.path.also { lookedUp.countDown() }
        }
        context.startActivity(launcher())
        val main = main()
        val title = title("Shared While Starting")

        Sender.send("application/x-fictionbook+xml", Sender.stage(fb2(title), "Starting.fb2"))
        waitForBook(main, title)
        assertSame(main, main())

        gate.complete(Unit)
        // The looking up goes on in the screen's effects, which run as the rule's clock moves.
        waitFor("the last book is looked up") { lookedUp.count == 0L }
        // What follows it runs at once; after it, the screen is settled.
        instrumentation.runOnMainSync {}
        rule.waitForIdle()
        assertEquals(title, shown(main))
        assertEquals("the book left was not opened over it", left.openedAt, runBlocking { library.book(left.id) }!!.openedAt)
    }

    @Test
    fun bookReadFromAListOfSharedBooksWhileTheLastOneIsLookedUpStaysOpen() {
        val left = runBlocking { library.open(fb2(title("Left Open")), "", "") }
        val gate = CompletableDeferred<Unit>()
        val lookedUp = CountDownLatch(1)
        MainActivity.lastBook = {
            gate.await()
            left.path.also { lookedUp.countDown() }
        }
        context.startActivity(launcher())
        val main = main()
        val chosen = title("Chosen While Starting")
        val other = title("Other While Starting")

        Sender.send("application/x-fictionbook+xml", Sender.stage(fb2(chosen), "Chosen.fb2"), Sender.stage(fb2(other), "Other.fb2"))
        waitForText(context.resources.getQuantityString(R.plurals.import_added, 2, 2))
        assertSame(main, main())
        assertEquals("the last book is still being looked up", 1L, lookedUp.count)
        rule.onNode(hasText(string(R.string.import_read)) and hasAnyAncestor(hasTestTag("imported") and hasAnyDescendant(hasText(chosen))))
            .performScrollTo().performClick()

        // Opened at once, without waiting for the looking up.
        waitForBook(main, chosen)
        assertEquals(1L, lookedUp.count)

        gate.complete(Unit)
        // The looking up goes on in the screen's effects, which run as the rule's clock moves.
        waitFor("the last book is looked up") { lookedUp.count == 0L }
        // What follows it runs at once; after it, the screen is settled.
        instrumentation.runOnMainSync {}
        rule.waitForIdle()
        assertEquals(chosen, shown(main))
        assertEquals("the book left was not opened over it", left.openedAt, runBlocking { library.book(left.id) }!!.openedAt)
    }

    @Test
    fun bookSharedAgainIsTheSameBookWithItsPlaceAndMarks() {
        val title = title("Shared Twice")
        val file = fb2(title)
        Sender.send("application/octet-stream", Sender.stage(file, "Shared Twice.fb2"))
        val main = main()
        waitForBook(main, title)
        turn(main)
        val id = reader(main).state.value.book!!.id
        val path = book(title).path
        runBlocking {
            library.addBookmark(id, "/body/section[2]", "marked words", null)
            library.addQuote(id, "/body/section[1]/p[3]", "/body/section[1]/p[4]", "quoted words", 0xFFEE58, null)
        }

        val again = Sender.stage(file, "Renamed by the file manager.fb2")
        Sender.send("application/octet-stream", again)

        waitFor("the second share is taken") { Sender.opens(again) == 1 && shares(*living).isEmpty() && mains(Stage.RESUMED).isNotEmpty() }
        rule.waitForIdle()
        waitForBook(main, title)
        assertEquals("the place in the open book stays", 1, reader(main).state.value.page?.index)
        assertEquals(1, books().count { it.title == title })
        assertEquals(path, book(title).path)
        assertEquals(listOf("$id.fb2"), kept.list().orEmpty().filter { it.startsWith(id) })
        assertEquals("marked words", runBlocking { library.bookmarks(id).first() }.single().text)
        assertEquals("quoted words", runBlocking { library.quotes(id).first() }.single().text)
        assertTrue(book(title).progress > 0f)
    }

    @Test
    fun oneFileThatIsNoBookIsToldAndNothingOpens() {
        runBlocking { library.open(fb2(title("Left Open")), "", "") }
        val before = books().map { it.id }.toSet()
        // Empty: what the engine refuses for certain (DescribeTest); other bytes it may read as text.
        val damaged = source("damaged.epub").apply { writeBytes(ByteArray(0)) }

        Sender.send("application/epub+zip", Sender.stage(damaged, "Damaged.epub"))

        val main = main()
        waitForText(string(R.string.import_none))
        rule.onNodeWithText("Damaged.epub").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.import_failed_unreadable)).assertIsDisplayed()
        assertNull("the book left is not opened for a share", reader(main).state.value.book)
        assertEquals(before, books().map { it.id }.toSet())

        rule.onNodeWithText(string(R.string.import_close)).performClick()
        rule.onAllNodesWithText(string(R.string.import_none)).assertCountEquals(0)
        assertSame(main, main())
    }

    // --- The right to read

    @Test
    fun fileSharedWithoutTheRightToReadItIsToldAndNotRead() {
        val before = books().map { it.id }.toSet()
        val uri = Sender.stage(fb2(title("Not Granted")), "Not Granted.fb2")

        Sender.send("application/x-fictionbook+xml", uri, grant = false)

        main()
        waitForText(string(R.string.import_none))
        rule.onNodeWithText(string(R.string.import_failed_denied)).assertIsDisplayed()
        assertEquals("the system let nothing be read", 0, Sender.opens(uri))
        assertEquals(before, books().map { it.id }.toSet())
    }

    @Test
    fun rightTakenBackWhileTheFileIsTakenIsToldAndNothingIsLeft() {
        val before = newCopies(emptySet())
        val uri = Sender.stage(fb2(title("Taken Back")), "Taken Back.fb2", Mode.HoldQuery)

        Sender.send("application/x-fictionbook+xml", uri)
        waitFor("the file is asked about") { Sender.held(uri) }
        Sender.revoke(uri)
        Sender.release(uri)

        main()
        waitForText(string(R.string.import_none))
        rule.onNodeWithText("Taken Back.fb2").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.import_failed_denied)).assertIsDisplayed()
        assertEquals("the system let nothing be read", 0, Sender.opens(uri))
        assertEquals(emptySet<String>(), newCopies(before))
    }

    @Test
    fun shareMadeAnewWhileCopyingGoesOnWithTheRightToRead() {
        val title = title("Copied Across")
        val uri = Sender.stage(fb2(title), "Copied Across.fb2", Mode.HoldCopy)

        Sender.send("application/x-fictionbook+xml", uri)
        waitFor("the copying has begun") { Sender.held(uri) && shares(Stage.RESUMED).size == 1 }
        val share = shares(Stage.RESUMED).single()
        instrumentation.runOnMainSync { share.recreate() }
        waitFor("the share is made anew") { shares(Stage.RESUMED).singleOrNull()?.let { it !== share } == true }
        Sender.release(uri)

        waitForBook(main(), title)
        assertEquals("the file was read once", 1, Sender.opens(uri))
        assertEquals(1, books().count { it.title == title })
    }

    @Test
    fun shareCalledOffWhileTheSharingAppIsSlowLeavesNothing() {
        val before = newCopies(emptySet())
        val title = title("Called Off")
        val uri = Sender.stage(fb2(title), "Called Off.fb2", Mode.Stall)

        Sender.send("application/x-fictionbook+xml", uri)
        waitFor("half the file has come") { Sender.held(uri) && newCopies(before).any { it.startsWith(".import-") } }
        val share = shares(Stage.RESUMED).single()
        // As the reader does with Back, or Cancel: the copy waits on the other app all the while.
        instrumentation.runOnMainSync { share.finish() }

        waitFor("the share is gone, and the part copied with it") { shares(*living).isEmpty() && newCopies(before).isEmpty() }
        assertTrue(books().none { it.title == title })
        assertTrue("nothing is handed to the reader's screen", SharedBooks.shares.value.isEmpty())
        assertTrue("nor is the app started", mains(*living).isEmpty())
    }

    // --- Several books

    @Test
    fun severalSharedBooksAreListedToReadWhateverTheShelfShows() {
        runBlocking { preferences.update { it.copy(shelf = Shelf.Reading) } }
        val epubTitle = title("Batch Epub")
        val epub = TestBook.epub(source("batch.epub"), epubTitle, author = "Batch Author")
        val zipTitle = title("Batch Zip")
        val plain = fb2(zipTitle)
        val archive = TestFolder.zip(source("batch.fb2.zip"), plain)
        val oneTitle = title("Same Name One")
        val one = fb2(oneTitle)
        val twoTitle = title("Same Name Two")
        val two = fb2(twoTitle)
        val pdf = source("scan.pdf").apply { writeText("%PDF-1.4 a scan") }

        // A file that is no book comes first: the rest are taken all the same.
        Sender.send(
            "application/*",
            Sender.stage(pdf, "Scan.pdf"), Sender.stage(epub, "Batch.epub"), Sender.stage(archive, "Batch.fb2.zip"),
            Sender.stage(one, "Same.fb2"), Sender.stage(two, "Same.fb2"),
        )

        val main = main()
        waitForText(string(R.string.import_some, 4, 5))
        rule.onNodeWithText("Scan.pdf").assertIsDisplayed()
        rule.onNodeWithText(string(R.string.import_failed_not_a_book)).assertIsDisplayed()
        assertNull("no book of several is opened by itself", reader(main).state.value.book)

        assertEquals(listOf("Batch Author"), book(epubTitle).authors)
        val zipped = book(zipTitle)
        assertEquals("the book in the archive is the book", FileHash.partialMd5(plain), zipped.id)
        assertEquals("fb2", zipped.format)
        assertArrayEquals(plain.readBytes(), File(zipped.path).readBytes())
        assertNotEquals("one name, two books", book(oneTitle).path, book(twoTitle).path)
        assertArrayEquals(one.readBytes(), File(book(oneTitle).path).readBytes())
        assertArrayEquals(two.readBytes(), File(book(twoTitle).path).readBytes())
        assertEquals("not on the Reading shelf the library shows", 0L, zipped.openedAt)

        val read = string(R.string.import_read)
        rule.onNode(hasText(read) and hasAnyAncestor(hasTestTag("imported") and hasAnyDescendant(hasText(zipTitle)))).performScrollTo().performClick()

        waitForBook(main, zipTitle)
        rule.onAllNodesWithText(string(R.string.import_some, 4, 5)).assertCountEquals(0)
    }

    @Test
    fun whatCannotBeTakenIsToldAndLeavesNothingBehind() {
        val before = newCopies(emptySet())
        val goodTitle = title("Good Beside Bad")
        // Empty: what the engine refuses for certain (DescribeTest); other bytes it may read as text.
        val damaged = source("damaged.epub").apply { writeBytes(ByteArray(0)) }

        Sender.send(
            "application/octet-stream",
            Sender.stage(fb2(title("No Stream")), "No Stream.fb2", Mode.NoStream),
            Sender.stage(fb2(title("Gone")), "Gone.fb2", Mode.Gone),
            Sender.stage(fb2(title("Broken Off")), "Broken Off.fb2", Mode.BreaksOff),
            Sender.stage(damaged, "Damaged.epub"),
            Sender.stage(fb2(goodTitle), "Good.fb2"),
        )

        main()
        waitForText(string(R.string.import_some, 1, 5))
        rule.onAllNodesWithText(string(R.string.import_failed_no_file)).assertCountEquals(2)
        rule.onNodeWithText(string(R.string.import_failed_not_copied)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(string(R.string.import_failed_unreadable)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(goodTitle).performScrollTo().assertIsDisplayed()
        val good = book(goodTitle)
        val titles = books().map { it.title }
        for (bad in listOf("No Stream", "Gone", "Broken Off")) assertTrue(bad, titles.none { it.startsWith("$bad ${testName.methodName}") })
        assertEquals("only the good book's copy is new", setOf("${good.id}.fb2"), newCopies(before))

        rule.onNodeWithText(string(R.string.import_close)).performClick()
        rule.onAllNodesWithText(string(R.string.import_some, 1, 5)).assertCountEquals(0)
    }

    @Test
    fun copyThatCannotBePutInPlaceLeavesNeitherItNorItsCover() {
        val title = title("Not Put In Place")
        val plain = TestBook.write(source("covered.fb2"), title, cover = TestFolder.cover(0xFF336699.toInt()))
        val id = FileHash.partialMd5(plain)
        val archive = TestFolder.zip(source("covered.fb2.zip"), plain)
        // Where the book would go, something the copy cannot take the place of.
        val blocked = File(kept, "$id.fb2").apply { mkdirs() }
        try {
            Sender.send("application/zip", Sender.stage(archive, "Covered.fb2.zip"))

            main()
            waitForText(string(R.string.import_none))
            rule.onNodeWithText(string(R.string.import_failed_not_copied)).assertIsDisplayed()
            assertNull(runBlocking { library.book(id) })
            assertFalse("the cover read on the way is gone", Covers.get(context).file(id).exists())
            assertTrue(kept.list().orEmpty().none { it.startsWith("$id.fb2.") })
        } finally {
            blocked.delete()
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
