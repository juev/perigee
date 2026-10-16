package org.evsyukov.perigee.opds

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.FakeAndroidKeyStore
import org.evsyukov.perigee.data.Catalogs
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.FoundBook
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LibraryPreferences
import org.evsyukov.perigee.data.LibraryPrefs
import org.evsyukov.perigee.engine.BookDescription
import org.evsyukov.perigee.library.Arrivals
import org.evsyukov.perigee.library.BookSink
import org.evsyukov.perigee.library.Describe
import org.evsyukov.perigee.library.SourceFile
import org.evsyukov.perigee.reader.TestBook
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What the catalogs' screen does, checked on its model: requirement R5.
 * The screen itself is checked on a device by OpdsScreenTest.
 */
@RunWith(RobolectricTestRunner::class)
class OpdsViewModelTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val library = Library.inMemory(context)
    private val catalogs = Catalogs.inMemory(context)
    private val bookFile = TestBook.write(File(context.cacheDir, "catalog-book.fb2"), "Title In The File", author = "File Author")
    private val catalog = TestCatalog("reader", "secret-1", bookFile.readBytes())
    private val storeScope = CoroutineScope(Dispatchers.IO + Job())
    private val preferences = LibraryPreferences.inFile(File(context.filesDir, "library.preferences_pb"), storeScope)

    private val downloads = File(context.cacheDir, "opds-downloads-folder").apply { deleteRecursively() }
    private val downloadsUri = Uri.fromFile(downloads).toString()
    private var folderWritable = true

    /** A plain directory standing in for the folder the reader picked for downloads. */
    private val sink = BookSink { name, from ->
        if (!folderWritable) return@BookSink null
        downloads.mkdirs()
        val file = File(downloads, name).also { from.copyTo(it) }
        SourceFile(Uri.fromFile(file).toString(), name, file.length(), file.lastModified())
    }

    /** A file as the engine would describe it; the engine itself does not run on the JVM. */
    private val describe: Describe = { _, _, _ ->
        BookDescription("Title In The File", listOf("File Author"), "en", "", "", "", null)
    }

    private val model by lazy {
        OpdsViewModel(
            context, catalogs, library,
            Arrivals(context, library, Covers(File(context.cacheDir, "opds-covers")), preferences, describe) { sink },
        )
    }

    @Before
    fun keystore() = FakeAndroidKeyStore.install()

    /** Downloaded books stay in the app, as after the reader said so. */
    @Before
    fun downloadsStayInTheApp() = downloadsGo(null)

    @After
    fun stop() {
        catalog.close()
        storeScope.cancel()
    }

    /** As after the reader was asked where downloads go; [folder] is what they chose, null for the app itself. */
    private fun downloadsGo(folder: String?) = runBlocking {
        preferences.update { LibraryPrefs(downloadsFolder = folder, downloadsAsked = true) }
    }

    /** Lets the model's work on the main thread run until [done] holds of its state. */
    private fun until(what: String, done: (OpdsState) -> Boolean): OpdsState {
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val state = model.state.value
            if (done(state)) return state
            if (System.currentTimeMillis() > deadline) throw AssertionError("$what; the state is $state")
            Thread.sleep(5)
        }
    }

    private fun add(address: String, user: String = "", password: String = ""): OpdsState {
        model.add(address, user, password)
        return until("the catalog is checked") { !it.adding }
    }

    private fun entry(title: String) = model.state.value.page!!.entries.single { it.title == title }

    /** The test catalog, added and opened at its first page. */
    private fun openCatalog() {
        runBlocking { catalogs.add("${catalog.url}/opds", "Home Books", "reader", "secret-1") }
        val stored = until("the catalog is listed") { it.catalogs.isNotEmpty() }.catalogs.single()
        model.open(stored)
        until("its first page is shown") { it.page?.title == "Home Books" && !it.loading }
    }

    /** The section of the test catalog with books in it. */
    private fun toTheBooks() {
        openCatalog()
        model.go(entry("All Books"))
        until("the books are shown") { state -> state.page?.entries?.any { it.title == "Listed Title" } == true }
    }

    private fun downloadFirst() {
        val listed = entry("Listed Title")
        model.download(listed, readableDownloads(listed).first())
    }

    private fun downloaded() = until("the download is over") { state -> state.downloads.values.any { it !is Download.Running } }

    @Test
    fun addingACatalogSaysWhatIsWrongUntilItIsRight() {
        until("the catalogs are read") { it.catalogs.isEmpty() }
        assertEquals(Failure.Unauthorized, add("${catalog.url}/opds", "reader", "wrong").addFailure)
        assertEquals(Failure.NotACatalog, add("${catalog.url}/page", "reader", "secret-1").addFailure)
        assertEquals(Failure.Unreachable, add("http://127.0.0.1:1/opds").addFailure)
        assertEquals(Failure.BadAddress, add("ftp://books.example/opds").addFailure)
        assertTrue("nothing was added on the way", runBlocking { catalogs.all().first().isEmpty() })

        val added = add("${catalog.url}/opds", "reader", "secret-1")
        assertNull(added.addFailure)
        assertEquals("the form is told to close itself", 1, added.added)
        val listed = until("the catalog is listed") { it.catalogs.isNotEmpty() }.catalogs.single()
        assertEquals("the catalog is titled as it titles itself", "Home Books", listed.title)
        assertEquals("${catalog.url}/opds", listed.url)
        assertFalse("secret-1" in listed.secret)
    }

    @Test
    fun catalogIsRemovedFromTheList() {
        runBlocking { catalogs.add("${catalog.url}/opds", "Home Books", "reader", "secret-1") }
        val stored = until("the catalog is listed") { it.catalogs.isNotEmpty() }.catalogs.single()
        model.remove(stored)
        until("the list is empty") { it.catalogs.isEmpty() }
    }

    @Test
    fun bookIsDownloadedIntoTheLibrary() {
        toTheBooks()
        val listed = entry("Listed Title")
        assertEquals("the PDF the catalog also offers is not something the app reads", listOf("fb2"), readableDownloads(listed).map { it.format })

        downloadFirst()
        val done = downloaded().downloads.values.single()
        assertTrue(done is Download.Done)

        val book = runBlocking { library.books().first().single() }
        assertEquals((done as Download.Done).bookId, book.id)
        assertEquals("the book is known by what its file says", "Title In The File", book.title)
        assertEquals(listOf("File Author"), book.authors)
        assertEquals("fb2", book.format)
        assertEquals("not opened yet", 0L, book.openedAt)
        assertTrue("kept in the app's storage", book.path.startsWith(context.filesDir.path) && File(book.path).isFile)
        assertEquals(book.path, runBlocking { model.fileOf(book.id) }?.path)
    }

    @Test
    fun firstDownloadAsksWhereBooksShouldGo() {
        runBlocking { preferences.update { LibraryPrefs() } }
        toTheBooks()
        downloadFirst()
        until("the reader is asked") { it.asking != null }
        assertTrue("nothing is downloaded before the answer", runBlocking { library.books().first().isEmpty() })

        model.downloadIntoApp()
        downloaded()
        val book = runBlocking { library.books().first().single() }
        assertTrue(book.path.startsWith(context.filesDir.path))
        assertTrue("the question is not asked twice", runBlocking { preferences.prefs.first().downloadsAsked })
    }

    @Test
    fun downloadGoesIntoTheFolderTheReaderChose() {
        downloadsGo(downloadsUri)
        toTheBooks()
        downloadFirst()
        downloaded()

        assertEquals("an ordinary file under the book's own name", listOf("Listed Title.fb2"), downloads.list().orEmpty().toList())
        val book = runBlocking { library.books().first().single() }
        assertEquals("Title In The File", book.title)
        assertEquals(Uri.fromFile(File(downloads, "Listed Title.fb2")).toString(), book.uri)
        assertEquals("no copy in the app until the book is opened", "", book.path)
        val folder = runBlocking { library.folders().first().single() }
        assertEquals("the folder is one the library looks through", downloadsUri, folder.uri)
        assertEquals(listOf(book.uri), runBlocking { library.filesIn(folder.id).map { it.uri } })
        assertArrayEquals(File(downloads, "Listed Title.fb2").readBytes(), runBlocking { model.fileOf(book.id) }!!.readBytes())
    }

    @Test
    fun bookThatLiesInAFolderIsNotPutThereTwice() {
        downloadsGo(downloadsUri)
        toTheBooks()
        runBlocking {
            val folder = library.addFolder(downloadsUri, "Downloads")
            library.found(
                folder.id,
                FoundBook(
                    FileHash.partialMd5(bookFile), "file:///elsewhere/mine.fb2", 1, 1, "fb2", "Title In The File",
                    listOf("File Author"), "en", "", "", hasCover = false,
                ),
            )
        }
        downloadFirst()
        downloaded()
        assertTrue("nothing was written: ${downloads.list().orEmpty().toList()}", downloads.list().isNullOrEmpty())
        assertEquals("file:///elsewhere/mine.fb2", runBlocking { library.books().first().single().uri })
    }

    @Test
    fun bookIsKeptInTheAppWhenTheFolderCannotBeWrittenTo() {
        downloadsGo(downloadsUri)
        folderWritable = false
        toTheBooks()
        downloadFirst()
        downloaded()
        val book = runBlocking { library.books().first().single() }
        assertTrue(File(book.path).isFile)
        assertEquals(null, book.uri)
    }

    @Test
    fun downloadShowsHowFarItIsAndIsCancelled() {
        toTheBooks()
        val slow = entry("Slow Title")
        val link = readableDownloads(slow).first()
        model.download(slow, link)
        val running = until("part of the book has come") { state ->
            (state.downloads[link.url] as? Download.Running)?.received?.let { it > 0 } == true
        }.downloads.getValue(link.url) as Download.Running
        assertEquals(1_000_000L, running.total)

        model.cancelDownload(link)
        assertNull("the book can be downloaded again at once", model.state.value.downloads[link.url])
        // That the connection is broken off at once is checked on a device, by OpdsClientTest: the JVM's
        // HttpURLConnection makes disconnect wait for the read it should end, so here it ends by the timeout.
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertNull("nothing comes back after the cancel", model.state.value.downloads[link.url])
        assertTrue(runBlocking { library.books().first().isEmpty() })
    }

    @Test
    fun bookLargerThanABookCanBeIsRefused() {
        toTheBooks()
        val huge = entry("Huge Title")
        val link = readableDownloads(huge).first()
        model.download(huge, link)
        val failed = until("the download is over") { it.downloads[link.url] is Download.Failed }
        assertEquals(Download.Failed(Failure.TooLarge), failed.downloads[link.url])
        assertTrue(runBlocking { library.books().first().isEmpty() })
    }

    @Test
    fun endOfAPageBringsWhatFollowsOnce() {
        toTheBooks()
        assertEquals("/opds/all?page=2", until("the page says what follows") { it.page?.next != null }.page?.next?.substringAfter(catalog.url))

        model.more()
        model.more()
        until("the next part is shown") { state -> state.page?.entries?.any { it.title == "Second Page Title" } == true && !state.loading }
        assertNull("the last part has nothing after it", model.state.value.page?.next)
        assertEquals("fetched once", 1, catalog.asked.count { it == "/opds/all?page=2" })
    }

    @Test
    fun searchShowsWhatTheCatalogFoundAndBackReturns() {
        openCatalog()
        model.search("war and peace")
        until("the search is shown") { state -> state.page?.entries?.any { it.title == "Found for war+and+peace" } == true }

        assertTrue(model.back())
        until("the first page is back") { it.page?.title == "Home Books" }
    }

    @Test
    fun brokenSectionSaysSoAndBackLeadsOut() {
        openCatalog()
        model.go(entry("Broken Shelf"))
        val broken = until("the failure is told") { it.failure != null }
        assertEquals(Failure.Unreachable, broken.failure)
        assertEquals("the page before stays shown", "Home Books", broken.page?.title)

        assertTrue(model.back())
        assertNull("out of the catalog, to the list", until("the list is shown") { it.catalog == null }.page)
        assertFalse("from the list of catalogs there is nowhere to step back to", model.back())
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
