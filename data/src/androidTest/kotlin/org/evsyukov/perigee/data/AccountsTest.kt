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
