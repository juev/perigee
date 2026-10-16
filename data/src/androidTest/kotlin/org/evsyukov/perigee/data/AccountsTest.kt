package org.evsyukov.perigee.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountsTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var clock = 1000L
    private val library = Library.inMemory(context) { clock++ }
    private val accounts = Accounts.of(library) { clock++ }
    private val tokens = Tokens("access-1", 5_000, "refresh-1", 9_000)

    private suspend fun book(name: String): String {
        val file = File(context.cacheDir, name).apply { writeText("the text of $name") }
        return library.keep(file, LocalBook(FileHash.partialMd5(file), "epub", name, emptyList(), "", "", "", false)).id
    }

    @Test
    fun nobodyIsSignedInAtFirst() = runBlocking {
        assertNull(accounts.account().first())
        assertNull(accounts.tokens())
        assertNull(accounts.deviceKey())
    }

    @Test
    fun tokensAreKeptSealedAndReadBack() = runBlocking {
        val account = accounts.signIn("https://books.example", "reader", "Reader", tokens, "v3.3.0")
        assertEquals(tokens, accounts.tokens())
        assertFalse("the stored form is not the token", "refresh-1" in account.refreshSecret)
        assertFalse("access-1" in account.accessSecret)
        assertEquals("v3.3.0", accounts.account().first()?.serverVersion)

        val next = Tokens("access-2", 6_000, "refresh-2", 10_000)
        accounts.saveTokens(next)
        assertEquals(next, accounts.tokens())
    }

    @Test
    fun tokensThatCannotBeOpenedAreReportedAsMissing() = runBlocking {
        accounts.signIn("https://books.example", "reader", "Reader", tokens, "v3.3.0")
        // As after the app's files were carried to another device.
        library.database.dao().updateTokens("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 1, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 2)
        assertNull(accounts.tokens())
    }

    @Test
    fun deviceKeyIsKeptSealed() = runBlocking {
        accounts.signIn("https://books.example", "reader", "Reader", tokens, "v3.3.0")
        assertNull("not set up yet", accounts.deviceKey())

        accounts.saveDevice("perigee-reader", "0123456789abcdef")
        val stored = checkNotNull(accounts.current())
        assertEquals("perigee-reader", stored.deviceUser)
        assertFalse("0123456789abcdef" in stored.deviceSecret)
        assertEquals("0123456789abcdef", accounts.deviceKey())
        assertEquals("the session is untouched", tokens, accounts.tokens())
    }

    @Test
    fun booksAreToldApartByWhatTheServerSaidOfThem() = runBlocking {
        val there = book("there.epub")
        val absent = book("absent.epub")
        val unasked = book("unasked.epub")
        assertEquals(setOf(there, absent, unasked), library.unmatched(all = false).toSet())

        library.onServer(there, serverBookId = 7, serverFileId = 70)
        library.notOnServer(listOf(absent))

        assertEquals(70L, library.book(there)?.serverFileId)
        assertEquals(7L, library.book(there)?.serverBookId)
        assertNull(library.book(absent)?.serverFileId)
        assertEquals("only the book never asked about", listOf(unasked), library.unmatched(all = false))
        assertEquals("asking again leaves out what the server has", setOf(absent, unasked), library.unmatched(all = true).toSet())
    }

    @Test
    fun bookTheServerHasALikenessOfIsMarkedUntilItsOwnFileIsThere() = runBlocking {
        val id = book("alike.epub")
        val there = book("there-already.epub")
        library.onServer(there, 8, 80)

        library.likeOnServer(id, 7, "Alike — An Author")
        library.likeOnServer(there, 9, "Never Mind")

        assertEquals(7L, library.book(id)?.serverLikeId)
        assertEquals("Alike — An Author", library.book(id)?.serverLikeName)
        assertNull("a likeness is not the file: nothing is synced by it", library.book(id)?.serverFileId)
        assertNull("a book whose own file is there has no need of a likeness", library.book(there)?.serverLikeId)
        assertEquals(listOf(id), library.booksNotOnServer().map { it.id })

        library.likeOnServer(id, null, "left over")
        assertNull(library.book(id)?.serverLikeId)
        assertEquals("", library.book(id)?.serverLikeName)

        library.likeOnServer(id, 7, "Alike — An Author")
        library.onServer(id, 11, 110)
        assertNull("its own file got there", library.book(id)?.serverLikeId)
        assertEquals("", library.book(id)?.serverLikeName)
    }

    @Test
    fun bookIsAskedAboutAgainWhenToldSo() = runBlocking {
        val id = book("sent.epub")
        library.notOnServer(listOf(id))
        assertEquals(emptyList<String>(), library.unmatched(all = false))

        library.askServerAgain(id)

        assertEquals(listOf(id), library.unmatched(all = false))
    }

    @Test
    fun placeBooksAreSentToIsForgottenWithTheServer() = runBlocking {
        accounts.signIn("https://books.example", "reader", "Reader", tokens, "v3.3.0")
        assertNull(accounts.current()?.uploadLibraryId)
        val id = book("alike.epub")
        library.likeOnServer(id, 7, "Alike")

        accounts.saveUploadPlace(3, 31)
        assertEquals(3L, accounts.current()?.uploadLibraryId)
        assertEquals(31L, accounts.current()?.uploadFolderId)
        accounts.saveTokens(Tokens("access-2", 6_000, "refresh-2", 10_000))
        assertEquals("a renewed session is the same account", 3L, accounts.current()?.uploadLibraryId)

        accounts.signIn("https://other.example", "other", "Other", tokens, "v3.4.0")

        assertNull(accounts.current()?.uploadLibraryId)
        assertNull(accounts.current()?.uploadFolderId)
        assertNull("another server has other books", library.book(id)?.serverLikeId)
    }

    @Test
    fun uploadUnderWayIsKeptUntilTheFileIsOnTheServerOrTheServerIsForgotten() = runBlocking {
        accounts.signIn("https://books.example", "reader", "Reader", tokens, "v3.3.0")
        val sent = book("sent.epub")
        val called = book("called-off.epub")
        val left = book("left.epub")
        library.uploadStarted(sent, "session-1")
        library.uploadStarted(called, "session-2")
        library.uploadStarted(left, "session-3")
        assertEquals("session-1", library.book(sent)?.uploadSession)
        assertEquals(setOf(sent, called, left), library.booksSending().map { it.id }.toSet())

        library.onServer(sent, 7, 70)
        library.uploadEnded(called)

        assertNull("the file got there", library.book(sent)?.uploadSession)
        assertNull(library.book(called)?.uploadSession)
        assertEquals(listOf(left), library.booksSending().map { it.id })

        accounts.signIn("https://other.example", "other", "Other", tokens, "v3.4.0")

        assertNull("the session is the other server's", library.book(left)?.uploadSession)
        assertTrue(library.booksSending().isEmpty())
    }

    @Test
    fun signingOutForgetsTheServerButKeepsTheBooks() = runBlocking {
        accounts.signIn("https://books.example", "reader", "Reader", tokens, "v3.3.0")
        val id = book("kept.epub")
        library.onServer(id, 7, 70)

        accounts.signOut()

        assertNull(accounts.account().first())
        assertNull(accounts.tokens())
        val kept = checkNotNull(library.book(id))
        assertNull(kept.serverFileId)
        assertTrue("the book is asked about anew on the next server", id in library.unmatched(all = false))
    }

    @Test
    fun signingInToAnotherServerForgetsTheBooksOfTheOneBefore() = runBlocking {
        accounts.signIn("https://books.example", "reader", "Reader", tokens, "v3.3.0")
        accounts.saveDevice("perigee-reader", "key")
        val id = book("moved.epub")
        library.onServer(id, 7, 70)

        accounts.signIn("https://other.example", "other", "Other", tokens, "v3.4.0")

        assertEquals("https://other.example", accounts.current()?.url)
        assertTrue("named as the server wants a device named", Regex("[A-Za-z0-9-]{1,100}").matches(accounts.current()!!.deviceId))
        assertNull("the other server has its own channel", accounts.deviceKey())
        assertNull(library.book(id)?.serverFileId)
    }
}
