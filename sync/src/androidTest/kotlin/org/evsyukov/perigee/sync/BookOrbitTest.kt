package org.evsyukov.perigee.sync

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LocalBook
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The client against a stand-in server the test runs itself: requirements R6, R10 and R12. */
@RunWith(AndroidJUnit4::class)
class BookOrbitTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var clock = 1_000_000L
    private val library = Library.inMemory(context)
    private val accounts = Accounts.of(library)
    private val orbit = BookOrbit(accounts, library, "0.0.1") { clock }
    private val server = FakeOrbit()
    private val dir = File(context.cacheDir, "orbit-test").apply { deleteRecursively() }

    @After
    fun stop() = server.close()

    private fun signIn() = runBlocking { orbit.signIn(server.url, "reader", "right-password") }

    private inline fun <reified T : Throwable> failing(crossinline block: suspend () -> Unit): T =
        assertThrows(T::class.java) { runBlocking { block() } }

    private suspend fun book(name: String): String {
        val file = File(context.cacheDir, name).apply { writeText("the text of $name") }
        return library.keep(file, LocalBook(FileHash.partialMd5(file), "epub", name, emptyList(), "", "", "", false)).id
    }

    @Test
    fun addressIsTakenAsTheReaderMeansIt() {
        assertEquals("https://books.example", BookOrbit.serverAddress(" books.example/ "))
        assertEquals("http://192.168.1.5:3000", BookOrbit.serverAddress("http://192.168.1.5:3000/api/v1/"))
        assertEquals("https://home.example/orbit", BookOrbit.serverAddress("https://Home.Example/orbit/api/v1"))
        for (bad in listOf("ftp://books.example", "", "https://", "books example")) {
            assertThrows(bad, IllegalArgumentException::class.java) { BookOrbit.serverAddress(bad) }
        }
    }

    @Test
    fun onlyVersionsTheAppWasMadeForAreSupported() {
        for (version in listOf("v3.3.0", "3.3.1", "v3.4.0", "v3.12.2")) assertTrue(version, BookOrbit.supports(version))
        for (version in listOf("v3.2.9", "v2.9.0", "v4.0.0", "Local build", "")) assertFalse(version, BookOrbit.supports(version))
    }

    @Test
    fun signingInKeepsTheSessionAndSetsUpTheDevicesChannel() = runBlocking<Unit> {
        assertEquals(DeviceChannel.Ready, orbit.signIn("${server.url}/api/v1/", "reader", "right-password"))

        val login = JSONObject(server.asked("/auth/login").single().body)
        assertEquals("native", login.getString("clientKind"))
        val account = checkNotNull(accounts.current())
        assertEquals(server.url, account.url)
        assertEquals("Reader Name", account.name)
        assertEquals("v3.3.0", account.serverVersion)
        assertTrue(accounts.tokens()!!.access in server.access)
        assertFalse("the password is kept nowhere", "right-password" in account.toString())

        assertEquals("the channel is named after the reader", "reader", server.deviceUser)
        assertEquals(server.deviceUser, account.deviceUser)
        assertEquals("the app holds the key the server will take", server.deviceKey, accounts.deviceKey())
    }

    @Test
    fun passwordIsNotSentWhereThereIsNoServer() {
        TestServer { Answer(200, "<html>A router's page</html>".toByteArray(), "text/html") }.use { other ->
            failing<NotAServerException> { orbit.signIn(other.url, "reader", "right-password") }
            assertTrue(other.seen.none { it.method == "POST" })
        }
        TestServer { Answer(404, "{\"message\":\"Not Found\"}") }.use { other ->
            failing<NotAServerException> { orbit.signIn(other.url, "reader", "right-password") }
            assertTrue(other.seen.none { it.method == "POST" })
        }
        failing<IllegalArgumentException> { orbit.signIn("ftp://books.example", "reader", "x") }
        assertNull(runBlocking { accounts.current() })
    }

    @Test
    fun passwordDoesNotFollowARedirectToAnotherServer() {
        TestServer { Answer(302, headers = mapOf("Location" to "${server.url}${it.path}")) }.use { front ->
            failing<NotAServerException> { orbit.signIn(front.url, "reader", "right-password") }
        }
        assertTrue("the server behind was never asked: ${server.seen}", server.seen.isEmpty())
    }

    @Test
    fun serverThatLetsNobodyInByPasswordIsNotSentOne() {
        server.passwordLogin = false
        failing<PasswordLoginDisabledException> { signIn() }
        assertTrue(server.asked("/auth/login").isEmpty())
    }

    @Test
    fun refusalsToSignInAreToldApart() {
        failing<WrongPasswordException> { orbit.signIn(server.url, "reader", "wrong") }
        assertNull(runBlocking { accounts.current() })

        server.loginAnswer = Answer(401, """{"statusCode":401,"message":"Account temporarily locked","errorCode":"account_locked","retryAfterSeconds":840}""")
        assertEquals(840, failing<TooManyAttemptsException> { signIn() }.seconds)

        server.loginAnswer = Answer(429, """{"statusCode":429,"message":"ThrottlerException: Too Many Requests"}""")
        assertEquals(0, failing<TooManyAttemptsException> { signIn() }.seconds)

        server.loginAnswer = Answer(403, """{"statusCode":403,"message":"Password authentication is disabled","errorCode":"password_auth_disabled"}""")
        failing<PasswordLoginDisabledException> { signIn() }
    }

    @Test
    fun tokenAboutToRunOutIsRenewedOnceForAllWhoAsk() = runBlocking<Unit> {
        signIn()
        val first = accounts.tokens()!!
        clock = first.accessExpiresAt - 5_000
        server.accessExpiresAt = "2030-01-01T00:30:00.000Z"

        coroutineScope { (1..6).map { async { orbit.books("", 0) } }.awaitAll() }

        assertEquals("one renewal for six requests", 1, server.asked("/auth/refresh").size)
        val renewed = accounts.tokens()!!
        assertNotEquals(first.access, renewed.access)
        assertNotEquals(first.refresh, renewed.refresh)
        assertTrue("the new pair is the one kept", renewed.refresh in server.refresh)
    }

    @Test
    fun tokenTheServerRefusesIsRenewedAndTheRequestRepeated() = runBlocking<Unit> {
        signIn()
        // As after the server was restarted with other keys: the token looks good and is not taken.
        server.access.clear()

        assertEquals(0, orbit.books("", 0).total)

        assertEquals(1, server.asked("/auth/refresh").size)
        assertEquals(2, server.asked("/books/query").size)
    }

    @Test
    fun sessionThatCannotBeRenewedEndsAndTheAccountStays() = runBlocking<Unit> {
        signIn()
        server.access.clear()
        server.refresh.clear()

        failing<SignedOutException> { orbit.books("", 0) }

        assertEquals("the reader is asked to sign in again, to the same server", server.url, accounts.current()?.url)
        val asked = server.seen.size
        failing<SignedOutException> { orbit.books("", 0) }
        assertEquals("a session known to be over is not tried again", asked, server.seen.size)

        orbit.signIn(server.url, "reader", "right-password")
        assertEquals(0, orbit.books("", 0).total)
    }

    @Test
    fun readerWithAUserNameInTheDevicesChannelIsAskedForItsPassword() = runBlocking<Unit> {
        server.deviceUser = "kindle-of-mine"
        server.deviceKey = FakeOrbit.md5("chosen in the browser")

        assertEquals(DeviceChannel.PasswordNeeded, signIn())
        assertEquals("kindle-of-mine", accounts.current()?.deviceUser)
        assertTrue("the reader's own user name is left as it is", server.asked("/koreader/credentials").none { it.method != "GET" })
        failing<DeviceChannelException> { orbit.match(listOf("0".repeat(32))) }

        failing<WrongPasswordException> { orbit.devicePassword("a guess") }
        assertNull(accounts.deviceKey())

        assertEquals(DeviceChannel.Ready, orbit.devicePassword("chosen in the browser"))
        assertEquals(DeviceChannel.Ready, orbit.deviceChannel())
        assertTrue(orbit.match(listOf("0".repeat(32))).isEmpty())
    }

    @Test
    fun channelLookedAtFromTwoPlacesAtOnceIsSetUpOnce() = runBlocking<Unit> {
        signIn()
        // As when the screen of the server opens while signing in is still at it.
        accounts.saveDevice("", "")
        server.deviceUser = null

        val states = coroutineScope { (1..4).map { async { orbit.deviceChannel() } }.awaitAll() }

        assertEquals(List(4) { DeviceChannel.Ready }, states)
        assertEquals("one user name was made", 2, server.asked("/koreader/credentials").count { it.method == "POST" })
        assertEquals(server.deviceKey, accounts.deviceKey())
    }

    @Test
    fun takenUserNameIsMadeUpAnother() = runBlocking<Unit> {
        server.takenNames += "reader"
        assertEquals(DeviceChannel.Ready, signIn())
        assertTrue(server.deviceUser, server.deviceUser!!.startsWith("reader-"))
    }

    @Test
    fun readerNotAllowedToSyncDevicesStaysSignedIn() = runBlocking<Unit> {
        server.deviceAllowed = false
        assertEquals(DeviceChannel.NotAllowed, signIn())
        assertEquals("the server's books are still there to see", 0, orbit.books("", 0).total)
    }

    @Test
    fun passwordChangedElsewhereIsAskedForAgain() = runBlocking<Unit> {
        signIn()
        server.deviceKey = FakeOrbit.md5("changed in the browser")

        assertEquals(DeviceChannel.PasswordNeeded, failing<DeviceChannelException> { orbit.match(listOf("0".repeat(32))) }.state)
        assertNull("the key that is not taken is dropped", accounts.deviceKey())
        assertEquals(DeviceChannel.PasswordNeeded, orbit.deviceChannel())
        assertEquals(DeviceChannel.Ready, orbit.devicePassword("changed in the browser"))
    }

    @Test
    fun syncSwitchedOffOnTheServerIsSaidSo() = runBlocking<Unit> {
        signIn()
        server.deviceSwitchedOn = false
        assertEquals(DeviceChannel.SwitchedOff, orbit.deviceChannel())
        assertEquals(DeviceChannel.SwitchedOff, failing<DeviceChannelException> { orbit.match(listOf("0".repeat(32))) }.state)
        assertEquals("the key is good and is kept", server.deviceKey, accounts.deviceKey())
    }

    @Test
    fun serverOfAnotherVersionIsReadButNotSyncedWith() = runBlocking<Unit> {
        server.version = "v4.0.0"
        assertEquals(DeviceChannel.Unsupported, signIn())
        assertNull("no user name is made on a server the app does not know", server.deviceUser)

        assertEquals(0, orbit.books("", 0).total)
        assertEquals("v4.0.0", failing<IncompatibleServerException> { orbit.match(listOf("0".repeat(32))) }.version)
        assertTrue(server.seen.none { it.path.contains("/koreader/") })

        // The server was brought back to a version the app knows.
        server.version = "v3.4.1"
        assertTrue(orbit.checkServer())
        assertEquals(DeviceChannel.Ready, orbit.deviceChannel())
    }

    @Test
    fun serverUpdatedPastWhatTheAppKnowsStopsBeingSyncedWith() = runBlocking<Unit> {
        signIn()
        server.version = "v4.0.0"
        assertFalse(orbit.checkServer())
        book("a.epub")
        val before = server.seen.size
        failing<IncompatibleServerException> { orbit.matchLibrary(all = true) }
        assertEquals(DeviceChannel.Unsupported, orbit.deviceChannel())
        assertEquals("nothing was sent to it", before, server.seen.size)
    }

    @Test
    fun booksAreListedAsTheServerHasThem() = runBlocking<Unit> {
        signIn()
        server.books = JSONArray(
            """[{"id":7,"title":"War and Peace","authors":["Leo Tolstoy"],"seriesName":"Classics","seriesIndex":"2.5","hasCover":true,
                 "files":[{"id":70,"format":"EPUB","role":"primary","sizeBytes":1234},{"id":71,"format":null,"role":"content","sizeBytes":null}]},
                {"id":8,"title":null,"authors":[],"seriesName":null,"seriesIndex":null,"hasCover":false,"files":[]}]""",
        )
        val page = orbit.books(" peace ", 2)

        val asked = JSONObject(server.asked("/books/query").single().body)
        assertEquals("peace", asked.getString("q"))
        assertEquals(2, asked.getJSONObject("pagination").getInt("page"))
        assertEquals(2, page.total)
        val book = page.books[0]
        assertEquals(RemoteBook(7, "War and Peace", listOf("Leo Tolstoy"), "Classics", "2.5", book.files, true), book)
        assertEquals(listOf(RemoteFile(7, 70, "epub", 1234), RemoteFile(7, 71, "", 0)), book.files)
        assertEquals("what the server leaves out is empty, not the word null", RemoteBook(8, "", emptyList(), "", "", emptyList(), false), page.books[1])
        assertFalse(page.hasMore(shown = 0))
        assertTrue(RemotePage(page.books, total = 5, page = 0).hasMore(shown = 2))

        assertTrue(orbit.cover(book)!!.isNotEmpty())
        assertNull("a book without a cover is not asked for one", orbit.cover(page.books[1]))
        assertEquals(1, server.seen.count { it.path.endsWith("/thumbnail") })
    }

    @Test
    fun fileIsDownloadedUnderTheNameTheServerGives() = runBlocking<Unit> {
        signIn()
        val text = "a whole book".toByteArray()
        server.downloads[70] = Answer(200, text, "application/epub+zip", mapOf("Content-Disposition" to "attachment; filename=\"war.epub\"; filename*=UTF-8''%D0%92%D0%BE%D0%B9%D0%BD%D0%B0.epub"))
        server.downloads[71] = Answer(200, text, "application/epub+zip", mapOf("Content-Disposition" to "attachment; filename=\"../../evil.epub\""))
        server.downloads[72] = Answer(200, text, "application/epub+zip", mapOf("Content-Disposition" to "attachment; filename=\"page.html\""))

        val named = orbit.download(RemoteFile(7, 70, "epub", 0), "War", dir)
        assertEquals("Война.epub", named.name)
        assertEquals("a whole book", named.readText())

        val contained = orbit.download(RemoteFile(7, 71, "epub", 0), "War", dir)
        assertEquals(dir.canonicalPath, contained.parentFile?.canonicalPath)

        assertEquals("a name that is not the format's gives way to the title", "War.epub", orbit.download(RemoteFile(7, 72, "epub", 0), "War", dir).name)

        failing<ServerResponseException> { orbit.download(RemoteFile(7, 99, "epub", 0), "Gone", dir) }
        assertTrue("nothing half-done is left", dir.listFiles().orEmpty().none { it.name.endsWith(".part") || it.name.startsWith("Gone") })
    }

    @Test
    fun booksOfTheLibraryAreFoundOnTheServerByTheirFiles() = runBlocking<Unit> {
        signIn()
        val there = book("there.epub")
        val absent = book("absent.epub")
        server.files[there] = 7L to 70L

        assertEquals(1, orbit.matchLibrary(all = false))

        val asked = server.asked("/koreader/plugin/match-check").single()
        assertEquals(server.deviceUser, asked.headers["x-auth-user"])
        assertEquals(server.deviceKey, asked.headers["x-auth-key"])
        val body = JSONObject(asked.body)
        assertEquals(accounts.current()!!.deviceId, body.getString("deviceId"))
        assertEquals("0.0.1", body.getString("pluginVersion"))
        assertEquals(70L, library.book(there)?.serverFileId)
        assertEquals(7L, library.book(there)?.serverBookId)
        assertNull(library.book(absent)?.serverFileId)

        // Nothing new in the library: the server is not asked again.
        assertEquals(0, orbit.matchLibrary(all = false))
        assertEquals(1, server.asked("/koreader/plugin/match-check").size)

        // The book was added to the server since; asking about everything finds it.
        server.files[absent] = 8L to 80L
        assertEquals(1, orbit.matchLibrary(all = true))
        assertEquals(80L, library.book(absent)?.serverFileId)
        assertEquals("only what the server was not known to have is asked about", 1, JSONObject(server.asked("/koreader/plugin/match-check").last().body).getJSONArray("hashes").length())
    }

    @Test
    fun manyBooksAreAskedAboutInPartsTheServerTakes() = runBlocking<Unit> {
        signIn()
        val hashes = (1..1201).map { "%032x".format(it) }
        server.files[hashes[0]] = 1L to 10L
        server.files[hashes[1200]] = 2L to 20L

        val found = orbit.match(hashes + hashes.take(10).map { it.uppercase() })

        assertEquals(setOf(hashes[0], hashes[1200]), found.keys)
        assertEquals(listOf(500, 500, 201), server.asked("/koreader/plugin/match-check").map { JSONObject(it.body).getJSONArray("hashes").length() })
    }

    @Test
    fun signingOutEndsTheSessionOnTheServerToo() = runBlocking<Unit> {
        signIn()
        val id = book("kept.epub")
        library.onServer(id, 7, 70)
        val refresh = accounts.tokens()!!.refresh

        orbit.signOut()

        assertFalse(refresh in server.refresh)
        assertNull(accounts.current())
        assertNull(library.book(id)?.serverFileId)
        failing<SignedOutException> { orbit.books("", 0) }
        Unit
    }

    @Test
    fun signingOutWorksWithoutTheServer() = runBlocking<Unit> {
        signIn()
        server.close()
        orbit.signOut()
        assertNull(accounts.current())
    }
}
