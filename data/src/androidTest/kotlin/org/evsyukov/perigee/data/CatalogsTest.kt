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
class CatalogsTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalogs = Catalogs.inMemory(context)

    @Test
    fun passwordIsKeptSealedAndReadBack() = runBlocking {
        val catalog = catalogs.add("https://books.example/opds", "Home", "reader", "pässword-1")
        assertEquals("pässword-1", catalogs.password(catalog))
        assertFalse("the stored form is not the password", "pässword-1" in catalog.secret)
        assertTrue(catalog.secret.isNotEmpty())

        val again = catalogs.add("https://other.example/opds", "Other", "reader", "pässword-1")
        assertTrue("the same password is not stored the same way twice", again.secret != catalog.secret)
    }

    @Test
    fun catalogWithoutAPasswordHasAnEmptyOne() = runBlocking {
        val catalog = catalogs.add("https://open.example/opds", "Open", "", "")
        assertEquals("", catalog.secret)
        assertEquals("", catalogs.password(catalog))
    }

    @Test
    fun passwordThatCannotBeOpenedIsReportedAsMissing() = runBlocking {
        val catalog = catalogs.add("https://books.example/opds", "Home", "reader", "secret")
        // As after the app's files were carried to another device.
        assertNull(catalogs.password(catalog.copy(secret = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")))
        assertNull(catalogs.password(catalog.copy(secret = "not base64 at all !!!")))
        assertNull(catalogs.password(catalog.copy(secret = "AAAA")))
    }

    @Test
    fun addingACatalogAgainChangesItInPlace() = runBlocking {
        val first = catalogs.add("https://books.example/opds", "Home", "reader", "old")
        val second = catalogs.add("https://books.example/opds", "Home library", "other", "new")
        assertEquals(first.id, second.id)
        val stored = catalogs.all().first().single()
        assertEquals("Home library", stored.title)
        assertEquals("other", stored.user)
        assertEquals("new", catalogs.password(stored))
    }

    @Test
    fun catalogIsChangedInPlace() = runBlocking {
        val home = catalogs.add("http://books.example/opds", "Home", "reader", "old")
        val changed = catalogs.change(home.id, "https://books.example/opds", "Home library", "other", "new")
        assertEquals(home.id, changed.id)
        assertEquals(home.addedAt, changed.addedAt)
        val stored = catalogs.all().first().single()
        assertEquals("https://books.example/opds", stored.url)
        assertEquals("Home library", stored.title)
        assertEquals("other", stored.user)
        assertEquals("new", catalogs.password(stored))
    }

    @Test
    fun catalogMovedToTheAddressOfAnotherTakesItsPlace() = runBlocking {
        val home = catalogs.add("https://home.example/opds", "Home", "", "")
        catalogs.add("https://books.example/opds", "Books", "", "")
        catalogs.change(home.id, "https://books.example/opds", "Home", "", "")
        assertEquals(listOf(home.id to "https://books.example/opds"), catalogs.all().first().map { it.id to it.url })
    }

    @Test
    fun catalogsAreListedByTitleAndCanBeRemoved() = runBlocking {
        val b = catalogs.add("https://b.example/opds", "beta", "", "")
        catalogs.add("https://a.example/opds", "Alpha", "", "")
        assertEquals(listOf("Alpha", "beta"), catalogs.all().first().map { it.title })
        catalogs.remove(b.id)
        assertEquals(listOf("Alpha"), catalogs.all().first().map { it.title })
    }

    @Test
    fun downloadedFileJoinsTheLibraryUnopened() = runBlocking {
        val library = Library.inMemory(context)
        val file = File(context.cacheDir, "downloaded.epub").apply { writeText("a downloaded book") }
        val id = FileHash.partialMd5(file)
        val facts = LocalBook(id, "epub", "Downloaded", listOf("Some Author"), "en", "Saga", "3", hasCover = false)

        val book = library.keep(file, facts)
        assertEquals(file.path, book.path)
        assertEquals(0L, book.openedAt)
        assertEquals(ReadingStatus.None, book.status)
        assertEquals("Saga", book.series)
        assertEquals(listOf("Downloaded"), library.books().first().map { it.title })
        assertNull("not what the app starts on", library.lastOpened())

        // Downloaded again after it was read: the reader's place is kept.
        library.open(file, "", "")
        library.savePosition(id, "/body/p[3]", 0.3f)
        val again = library.keep(file, facts.copy(title = "Downloaded, second edition"))
        assertEquals("/body/p[3]", again.xpointer)
        assertEquals("Downloaded, second edition", again.title)
        assertEquals(1, library.books().first().size)
    }
}
