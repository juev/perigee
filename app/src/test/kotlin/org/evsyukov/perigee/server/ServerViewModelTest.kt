package org.evsyukov.perigee.server

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.FakeAndroidKeyStore
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LibraryPreferences
import org.evsyukov.perigee.data.LibraryPrefs
import org.evsyukov.perigee.data.LocalBook
import org.evsyukov.perigee.engine.BookDescription
import org.evsyukov.perigee.library.Arrivals
import org.evsyukov.perigee.library.BookSink
import org.evsyukov.perigee.library.Describe
import org.evsyukov.perigee.sync.Answer
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.DeviceChannel
import org.evsyukov.perigee.sync.FakeOrbit
import org.evsyukov.perigee.sync.TestServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What the bookOrbit server's screen does, checked on its model: requirements R6 and R10.
 * The screen itself is checked on a device by ServerScreenTest.
 */
@RunWith(RobolectricTestRunner::class)
class ServerViewModelTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val library = Library.inMemory(context)
    private val accounts = Accounts.of(library)
    private val orbit = BookOrbit(accounts, library, "0.0.1")
    private val server = FakeOrbit()
    private val bookFile = File(context.cacheDir, "server-book.fb2").apply { writeText("<FictionBook>a book</FictionBook>") }

    /** A file the engine would read, as it would describe it; the engine itself does not run on the JVM. */
    private var describe: Describe = { _, _, _ ->
        BookDescription("Title In The File", listOf("File Author"), "en", "", "", "", null)
    }

    private val model by lazy {
        val arrivals = Arrivals(
            context, library, Covers(File(context.cacheDir, "server-covers")), LibraryPreferences.get(context),
            { c, file, name -> describe(c, file, name) },
        ) { BookSink { _, _ -> null } }
        ServerViewModel(context, orbit, accounts, library, arrivals)
    }

    @After
    fun stopServer() = server.close()

    /** Tokens are sealed as on a device, with a key of the JVM's. */
    @Before
    fun keystore() = FakeAndroidKeyStore.install()

    /** Downloaded books stay in the app, as after the reader said so. */
    @Before
    fun downloadsStayInTheApp() = runBlocking<Unit> {
        LibraryPreferences.get(context).update { LibraryPrefs(downloadsFolder = null, downloadsAsked = true) }
    }

    /** Lets the model's work on the main thread run until [done] holds of its state. */
    private fun until(what: String, done: (ServerState) -> Boolean): ServerState {
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val state = model.state.value
            if (done(state)) return state
            if (System.currentTimeMillis() > deadline) throw AssertionError("$what; the state is $state")
            Thread.sleep(5)
        }
    }

    private fun serverHas(vararg books: String) {
        server.books = JSONArray(books.joinToString(",", "[", "]"))
    }

    private fun book(id: Long, title: String, fileId: Long, format: String = "fb2") =
        """{"id":$id,"title":"$title","authors":["Server Author"],"seriesName":null,"seriesIndex":null,"hasCover":false,
            "files":[{"id":$fileId,"format":"$format","role":"primary","sizeBytes":100}]}"""

    private fun signIn(address: String = server.url, password: String = "right-password"): ServerState {
        until("the account is looked up") { it.loaded }
        model.signIn(address, "reader", password)
        return until("signing in is over") { !it.signingIn }
    }

    private fun listed(title: String) = until("$title is listed") { state -> state.books.any { it.title == title } }

    private fun keepBookFile(): String {
        val hash = FileHash.partialMd5(bookFile)
        runBlocking { library.keep(bookFile, LocalBook(hash, "fb2", "Title In The File", emptyList(), "", "", "", false)) }
        return hash
    }

    @Test
    fun signingInSaysWhatIsWrongUntilItIsRight() {
        serverHas(book(7, "Listed Title", 70))
        assertEquals(ServerFailure.WrongPassword, signIn(password = "wrong").signInFailure)

        TestServer { Answer(200, "<html>A router</html>".toByteArray(), "text/html") }.use { other ->
            assertEquals(ServerFailure.NotAServer, signIn(address = other.url).signInFailure)
        }
        assertEquals(ServerFailure.BadAddress, signIn(address = "ftp://books.example").signInFailure)
        assertNull("nobody was signed in on the way", runBlocking { accounts.current() })

        assertNull(signIn().signInFailure)
        val state = listed("Listed Title")
        assertEquals(listOf("Server Author"), state.books.single().authors)
        assertEquals(server.url, runBlocking { accounts.current() }?.url)
        assertEquals("reading is ready to be synced, and nothing is asked", DeviceChannel.Ready, state.channel)
    }

    @Test
    fun bookIsDownloadedIntoTheLibrary() {
        serverHas(book(7, "Listed Title", 70), book(8, "A Comic", 80, format = "cbz"))
        server.downloads[70] = Answer(200, bookFile.readBytes(), "application/x-fictionbook+xml", mapOf("Content-Disposition" to "attachment; filename=\"listed.fb2\""))
        signIn()
        val books = listed("Listed Title").books
        assertTrue("a comic is not something the app reads, and is not offered", readableFiles(books.single { it.title == "A Comic" }).isEmpty())

        val listedBook = books.single { it.title == "Listed Title" }
        model.download(listedBook, readableFiles(listedBook).single())
        until("the book is in the library") { 70L in it.onDevice && it.downloads.isEmpty() }

        val book = runBlocking { library.books().first().single() }
        assertEquals("the book is known by what its file says", "Title In The File", book.title)
        assertEquals("fb2", book.format)
        assertEquals("known to be the server's without asking it", 70L, book.serverFileId)
        assertEquals(7L, book.serverBookId)
        assertEquals(book.path, runBlocking { model.fileOf(book.id) }?.path)
    }

    @Test
    fun fileThatIsNotABookIsRefused() {
        describe = { _, _, _ -> null }
        serverHas(book(7, "Listed Title", 70))
        server.downloads[70] = Answer(200, ByteArray(0), "application/x-fictionbook+xml")
        signIn()
        val listedBook = listed("Listed Title").books.single()
        model.download(listedBook, readableFiles(listedBook).single())

        val state = until("the download is over") { it.downloads[70L] is Fetched.Failed }
        assertEquals(Fetched.Failed(ServerFailure.NotABook), state.downloads[70L])
        assertTrue(runBlocking { library.books().first().isEmpty() })
    }

    @Test
    fun bookTheReaderAlreadyHasIsFoundOnTheServer() {
        // The book came to the device some other way; the server has the same file.
        val hash = keepBookFile()
        server.files[hash] = 7L to 70L
        serverHas(book(7, "Listed Title", 70))
        signIn()

        val state = until("the book is known to be on the server") { it.onDevice[70L] == hash }
        assertEquals("it is shown as kept, not offered for download again", hash, state.onDevice[70L])
        assertEquals(70L, runBlocking { library.book(hash) }?.serverFileId)
    }

    @Test
    fun readerWithAKoreaderUserNameIsAskedForItsPassword() {
        server.deviceUser = "kindle-of-mine"
        server.deviceKey = FakeOrbit.md5("chosen in the browser")
        serverHas(book(7, "Listed Title", 70))
        signIn()

        val asked = listed("Listed Title")
        assertEquals(DeviceChannel.PasswordNeeded, asked.channel)
        assertEquals("kindle-of-mine", asked.account?.deviceUser)

        model.devicePassword("a guess")
        until("the password is turned down") { it.channelPasswordWrong && !it.channelBusy }

        model.devicePassword("chosen in the browser")
        until("the channel is ready") { it.channel == DeviceChannel.Ready }
        assertEquals(server.deviceKey, runBlocking { accounts.deviceKey() })
    }

    /**
     * A new password is set with PATCH, which the JDK's HttpURLConnection refuses to send and
     * Android's sends: setting one is checked on a device, by BookOrbitTest in :sync.
     */
    @Test
    fun newKoreaderPasswordTooShortIsTurnedDown() {
        // The app made the password up, and was installed anew since.
        server.deviceUser = "reader"
        server.deviceKey = FakeOrbit.md5("made up and lost")
        signIn()
        until("the password is asked for") { it.channel == DeviceChannel.PasswordNeeded }

        model.newDevicePassword("short")
        val state = until("the password is turned down") { it.channelPasswordWrong && !it.channelBusy }
        assertEquals(DeviceChannel.PasswordNeeded, state.channel)
        assertEquals("nothing was changed on the server", FakeOrbit.md5("made up and lost"), server.deviceKey)
    }

    @Test
    fun serverOfAnUnknownVersionIsReadButNotSyncedWith() {
        server.version = "v4.0.0"
        serverHas(book(7, "Listed Title", 70))
        signIn()
        assertEquals(DeviceChannel.Unsupported, listed("Listed Title").channel)
        assertTrue("its devices' channel is left alone", server.seen.none { "/koreader/" in it.path })
    }

    @Test
    fun readerNotAllowedToSyncIsToldSo() {
        server.deviceAllowed = false
        signIn()
        until("the reader is told") { it.channel == DeviceChannel.NotAllowed }
    }

    @Test
    fun searchAsksTheServerAndAnEmptyOneShowsAllAgain() {
        serverHas(book(7, "Listed Title", 70))
        signIn()
        listed("Listed Title")

        model.search("tolstoy")
        until("the search is over") { it.query == "tolstoy" && !it.loading }
        assertTrue(server.asked("/books/query").any { JSONObject(it.body).optString("q") == "tolstoy" })

        val before = server.asked("/books/query").size
        model.search("")
        until("all the books are back") { it.query.isEmpty() && it.books.isNotEmpty() }
        assertTrue(server.asked("/books/query").size > before)
        assertTrue(!JSONObject(server.asked("/books/query").last().body).has("q"))
    }

    @Test
    fun listGrowsAsTheReaderReachesItsEnd() {
        serverHas(book(7, "Listed Title", 70))
        // The server says it has more than it gave.
        server.total = 2
        signIn()
        until("the list says it has more") { it.more && !it.loading }

        model.more()
        until("the next part was asked for") {
            server.asked("/books/query").any { JSONObject(it.body).getJSONObject("pagination").getInt("page") == 1 }
        }
    }

    @Test
    fun sessionThatEndedAsksToSignInAgainToTheSameServer() {
        serverHas(book(7, "Listed Title", 70))
        signIn()
        listed("Listed Title")
        // As after the reader ended the app's session from the server's own pages.
        server.access.clear()
        server.refresh.clear()

        model.search("anything")
        val over = until("the session is over") { it.sessionOver }
        assertEquals(server.url, over.account?.url)

        signIn()
        listed("Listed Title")
    }

    @Test
    fun signingOutForgetsTheServer() {
        val hash = keepBookFile()
        server.files[hash] = 7L to 70L
        signIn()
        until("the book is known to be on the server") { it.onDevice[70L] == hash }

        model.signOut()
        until("signed out") { it.loaded && it.account == null }
        assertNull(runBlocking { accounts.current() })
        assertNull(runBlocking { library.book(hash) }?.serverFileId)
        assertEquals("the book stays", 1, runBlocking { library.books().first().size })
    }

    @Test
    fun serverThatDoesNotAnswerCanBeTriedAgain() {
        serverHas(book(7, "Listed Title", 70))
        signIn()
        listed("Listed Title")
        server.queryAnswer = Answer(500, """{"statusCode":500,"message":"Internal server error"}""")

        model.search("anything")
        until("the failure is told") { it.failure == ServerFailure.Unreachable }

        server.queryAnswer = null
        model.retry()
        listed("Listed Title")
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
