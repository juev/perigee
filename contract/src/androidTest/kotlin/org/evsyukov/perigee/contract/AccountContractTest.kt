package org.evsyukov.perigee.contract

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.LocalBook
import org.evsyukov.perigee.engine.Engine
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.DeviceChannel
import org.evsyukov.perigee.sync.SignedOutException
import org.evsyukov.perigee.sync.WrongPasswordException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's account on a real bookOrbit server: signing in, the server's
 * books, and finding the reader's files among them. Requirements R6, R10, R12.
 */
@RunWith(AndroidJUnit4::class)
class AccountContractTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "account-contract").apply { deleteRecursively() }
    private lateinit var server: BookOrbitServer

    @Before
    fun connect() {
        server = ContractServer.connect()
    }

    private fun device(): ContractDevice = ContractDevice.signedIn(context)

    @Test
    fun readerSignsInAndTheSessionIsKeptSealed() = runBlocking<Unit> {
        val device = device()
        val account = checkNotNull(device.accounts.current())
        assertEquals("Reader", account.name)
        assertEquals(BookOrbitServer.KO_USERNAME, account.deviceUser)
        assertTrue("version ${account.serverVersion}", BookOrbit.supports(account.serverVersion))
        assertFalse(BookOrbitServer.PASSWORD in account.toString())
        assertTrue(device.orbit.checkServer())
        assertEquals(DeviceChannel.Ready, device.orbit.deviceChannel())
    }

    @Test
    fun wrongPasswordIsRefused() {
        val stranger = ContractDevice(context)
        assertThrows(WrongPasswordException::class.java) { runBlocking { stranger.signIn("not-the-password") } }
        assertNull(runBlocking { stranger.accounts.current() })
    }

    @Test
    fun wrongPasswordOfTheDevicesChannelIsRefused() = runBlocking<Unit> {
        val device = device()
        assertThrows(WrongPasswordException::class.java) { runBlocking { device.orbit.devicePassword("not-the-password") } }
        assertEquals("the key that works is kept", DeviceChannel.Ready, device.orbit.deviceChannel())
    }

    @Test
    fun serversBooksAreListedAndFound() = runBlocking<Unit> {
        val orbit = device().orbit
        val all = orbit.books("", 0)
        val titles = all.books.map { it.title }
        assertTrue("got $titles", titles.containsAll(listOf("Contract Plain", "Contract Fb2", "Contract Media Css")))
        assertEquals("by title", titles.sortedBy { it.lowercase() }, titles)
        assertEquals(all.total, all.books.size)
        assertFalse(all.hasMore(shown = 0))
        assertTrue("every book has a file with a format", all.books.all { book -> book.files.any { it.format.isNotEmpty() && it.size > 0 } })
        assertEquals("fb2", all.books.first { it.title == "Contract Fb2" }.files.single().format)

        assertEquals(listOf("Contract Plain"), orbit.books("Plain", 0).books.map { it.title })
        assertTrue(orbit.books("", 50).books.isEmpty())

        // The server has a book in PDF only, and leaves it out of the app's list and its count.
        val pdf = server.jwt("POST", "/books/query", JSONObject().put("q", "Contract Pdf")).json()
        assertEquals("the server has it", 1, pdf.getInt("total"))
        assertTrue("got $titles", "Contract Pdf" !in titles)
        assertEquals(0, orbit.books("Contract Pdf", 0).total)
    }

    @Test
    fun downloadedFileIsTheBookAndIsFoundBackByItsFingerprint() = runBlocking<Unit> {
        val device = device()
        val book = device.orbit.books("Plain", 0).books.single()
        val remote = book.files.first { it.format == "epub" }

        val file = device.orbit.download(remote, book.title, dir)

        assertTrue("named as a book: ${file.name}", file.name.endsWith(".epub"))
        assertEquals(remote.size, file.length())
        val said = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { Engine.describe(context, it, file.name) }
        assertEquals("Contract Plain", said?.title)

        // The same file, had it come to the device some other way.
        val hash = FileHash.partialMd5(file)
        val unknown = "0123456789abcdef0123456789abcdef"
        val found = device.orbit.match(listOf(hash, unknown))
        assertEquals(setOf(hash), found.keys)
        assertEquals(remote.id, found.getValue(hash).id)
        assertEquals(book.id, found.getValue(hash).bookId)

        val known = device.library.keep(file, LocalBook(hash, "epub", "Contract Plain", emptyList(), "", "", "", false))
        val stray = File(dir, "stray.txt").apply { writeText("a book the server never saw ${System.nanoTime()}") }
        val local = device.library.keep(stray, LocalBook(FileHash.partialMd5(stray), "txt", "Stray", emptyList(), "", "", "", false))
        assertEquals(1, device.orbit.matchLibrary(all = true))
        assertEquals(remote.id, device.library.book(known.id)?.serverFileId)
        assertEquals(book.id, device.library.book(known.id)?.serverBookId)
        assertNull("a book the server has not is read as before", device.library.book(local.id)?.serverFileId)
    }

    @Test
    fun bookWithoutACoverHasNone() = runBlocking<Unit> {
        val orbit = device().orbit
        // The corpus is made without covers; the server says so, and is not asked for one.
        val book = orbit.books("Plain", 0).books.single()
        assertFalse(book.hasCover)
        assertNull(orbit.cover(book))
        assertNull("nor does it have one when asked all the same", orbit.cover(book.copy(hasCover = true)))
    }

    @Test
    fun booksDescriptionIsTheServersOwnHtml() = runBlocking<Unit> {
        val orbit = device().orbit
        val books = orbit.books("", 0).books
        val plain = books.first { it.title == "Contract Plain" }

        val detail = orbit.detail(plain.id)
        assertEquals(plain.id, detail.id)
        assertEquals("Contract Plain", detail.title)
        // Named in objects here and in plain names in the list: the same people all the same.
        assertTrue(detail.authors.isNotEmpty())
        assertEquals(plain.authors, detail.authors)
        // The corpus gives the book a description in HTML, which the server keeps as it is for the app to flatten.
        val about = checkNotNull(detail.description) { "the server has no description of ${plain.title}" }
        assertTrue(about, "A book made for the contract tests" in about)
        assertTrue(about, "Its second paragraph" in about)
        assertTrue(about, "<em>inline</em>" in about)

        // A book without one says so rather than failing.
        assertNull(orbit.detail(books.first { it.title == "Contract Media Css" }.id).description)
    }

    @Test
    fun tokenThatRanOutIsRenewedWithoutTheReader() = runBlocking<Unit> {
        val device = device()
        val before = checkNotNull(device.accounts.tokens())
        // As after the quarter of an hour the server gives a token.
        device.accounts.saveTokens(before.copy(accessExpiresAt = 0))

        assertTrue(device.orbit.books("", 0).total > 0)

        val after = checkNotNull(device.accounts.tokens())
        assertNotEquals(before.access, after.access)
        assertNotEquals("the token to renew with is good for one use", before.refresh, after.refresh)
        assertTrue(after.accessExpiresAt > System.currentTimeMillis())
        assertTrue(after.refreshExpiresAt >= before.refreshExpiresAt)

        // A token the server does not take at all, whatever its date says.
        device.accounts.saveTokens(after.copy(access = "not.a.token"))
        assertTrue(device.orbit.books("", 0).total > 0)
    }

    @Test
    fun signingOutEndsTheSessionOnTheServer() = runBlocking<Unit> {
        val device = ContractDevice(context)
        device.signIn()
        val tokens = checkNotNull(device.accounts.tokens())

        device.orbit.signOut()

        assertNull(device.accounts.current())
        assertEquals("the token is taken no more", 401, server.plain("GET", "/app-info", mapOf("authorization" to "Bearer ${tokens.access}")).code)
        assertEquals(401, server.plain("POST", "/auth/refresh", emptyMap(), JSONObject().put("refreshToken", tokens.refresh)).code)
        assertThrows(SignedOutException::class.java) { runBlocking { device.orbit.books("", 0) } }
    }

    @Test
    fun devicesChannelIsMadeForAReaderWhoHasNone() = runBlocking<Unit> {
        val device = device()
        val own = checkNotNull(device.accounts.current()).deviceUser
        assertEquals(200, server.jwt("DELETE", "/koreader/credentials").code)
        try {
            // The key the app held is of a user name that is no more.
            device.accounts.saveDevice("", "")
            assertEquals(DeviceChannel.Ready, device.orbit.deviceChannel())

            val made = checkNotNull(device.accounts.current()).deviceUser
            assertEquals("named after the reader", BookOrbitServer.USERNAME, made)
            assertEquals(made, server.jwt("GET", "/koreader/credentials").json().getString("username"))
            assertTrue(device.orbit.match(listOf("0123456789abcdef0123456789abcdef")).isEmpty())
        } finally {
            // The other contract tests go by the user name the server was seeded with.
            server.jwt("DELETE", "/koreader/credentials")
            server.ensureKoreaderCredentials()
            device.accounts.saveDevice(own, "")
            device.orbit.devicePassword(BookOrbitServer.KO_PASSWORD)
        }
    }
}
