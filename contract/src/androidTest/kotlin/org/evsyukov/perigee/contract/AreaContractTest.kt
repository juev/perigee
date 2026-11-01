package org.evsyukov.perigee.contract

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.sync.CatalogueQuery
import org.evsyukov.perigee.sync.CatalogueSort
import org.evsyukov.perigee.sync.DeviceChannel
import org.evsyukov.perigee.sync.NotOnServerException
import org.evsyukov.perigee.sync.ReadStatuses
import org.evsyukov.perigee.sync.Related
import org.evsyukov.perigee.sync.RemoteBook
import org.evsyukov.perigee.sync.Shelf
import org.evsyukov.perigee.sync.ShelfKind
import org.evsyukov.perigee.sync.UnsupportedOnServerException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the app does on a real bookOrbit server beyond its list of books: the home page's shelves, the
 * catalogue's filters, collections, the books beside a book, and the reader's status, stars and note of a
 * book that is not on the device. Run against the version the app is written for and the latest: a
 * version without one of these routes must say so as the app takes it, and the test checks that the
 * route is indeed not there rather than taking the app's word for it.
 */
@RunWith(AndroidJUnit4::class)
class AreaContractTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: BookOrbitServer
    private lateinit var device: ContractDevice
    private val area get() = device.orbit.area

    @Before
    fun connect() {
        server = ContractServer.connect()
        device = readerWithNothingDownloaded(context)
    }

    private fun items(body: JSONObject): List<JSONObject> {
        val items = server.jwt("POST", "/books/query", body).json().getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }
    }

    private fun page() = JSONObject().put("pagination", JSONObject().put("page", 0).put("size", 100))

    /** The titles of the server's books the app reads, as its own list without any filter has them. */
    private fun readableTitles(): Set<String> = runBlocking { device.orbit.books(CatalogueQuery(), 0).books.map { it.title }.toSet() }

    /** A route this version has not: the server's router answers it, not the code behind it. */
    private fun assertNoRoute(method: String, path: String, body: JSONObject? = null) {
        val reply = server.jwt(method, path, body)
        assertEquals("$method $path: $reply", 404, reply.code)
        assertTrue(reply.body, reply.json().optString("message").startsWith("Cannot "))
    }

    @Test
    fun shelvesAreTheServersSamplesOrSayTheyAreNotThere() = runBlocking<Unit> {
        val batch = JSONObject().put("items", JSONArray().put(JSONObject().put("id", "r").put("type", "recently-added").put("limit", 12)))
        val raw = server.jwt("POST", "/dashboard/scrollers/batch", batch)
        val shelves = area.shelves()
        assertEquals(ShelfKind.DEFAULT.toSet(), shelves.keys)
        assertTrue("a shelf of the server is never taken for a failure: $shelves", shelves.values.none { it == Shelf.Failed })

        val recent = shelves.getValue(ShelfKind.RecentlyAdded)
        if (raw.code == 200 || server.jwt("GET", "/dashboard/scrollers/recently-added?limit=12").code == 200) {
            recent as Shelf.Books
            val titles = recent.books.map { it.title }
            assertTrue(titles.toString(), "Contract Plain" in titles)
            assertTrue("a book the app does not read is left out: $titles", "Contract Pdf" !in titles)
            assertTrue("and counted as left out", recent.leftOut >= 1)
            assertTrue(recent.books.all { book -> book.files.isNotEmpty() })
            // The single shelf names a total; the app shows the books, never that number as if it were theirs.
            val single = server.jwt("GET", "/dashboard/scrollers/recently-added?limit=12").json()
            assertTrue(single.has("books"))
            assertEquals(recent.books.map { it.id }, (area.shelf(ShelfKind.RecentlyAdded) as Shelf.Books).books.map { it.id })
        } else {
            assertEquals(Shelf.Unsupported, recent)
            assertNoRoute("GET", "/dashboard/scrollers/recently-added?limit=12")
        }
        // The shelves of books the reader wants and reads stand for the reader's own statuses; empty is an answer.
        for (kind in listOf(ShelfKind.WantToRead, ShelfKind.ContinueReading, ShelfKind.UpNext, ShelfKind.Random)) {
            val shelf = shelves.getValue(kind)
            assertTrue("$kind: $shelf", shelf is Shelf.Books || shelf == Shelf.Unsupported)
        }
    }

    @Test
    fun catalogueIsNarrowedByEachOfItsFiltersOrSaysItCannotBe() = runBlocking<Unit> {
        val all = readableTitles()
        assertTrue(all.toString(), "Contract Plain" in all)

        suspend fun found(query: CatalogueQuery, rule: JSONObject?): Set<String>? = try {
            device.orbit.books(query, 0).books.map { it.title }.toSet()
        } catch (e: UnsupportedOnServerException) {
            // What the app took for a filter the server has not, the server refuses as a request it cannot read.
            val body = page().put("filter", JSONObject().put("type", "group").put("join", "AND").put("rules", JSONArray().put(rule)))
            assertEquals(400, server.jwt("POST", "/books/query", body).code)
            null
        }
        fun rule(field: String, operator: String, value: Any) =
            JSONObject().put("type", "rule").put("field", field).put("operator", operator).put("value", value)

        found(CatalogueQuery(author = "Perigee Contract"), rule("author", "includesAny", JSONArray().put("Perigee Contract")))?.let {
            // The FB2 names its author in its own way, and the server reads it as the same one.
            assertEquals(setOf("Contract Plain", "Contract Spine NonXhtml", "Contract Media Css", "Contract Fb2"), it)
        }
        found(CatalogueQuery(series = "Contract Series"), rule("series", "contains", "Contract Series"))?.let {
            assertEquals(setOf("Contract Spine NonXhtml", "Contract Media Css"), it)
        }
        found(CatalogueQuery(genre = "Contract Genre"), rule("genre", "includesAny", JSONArray().put("Contract Genre")))?.let {
            assertEquals(setOf("Contract Plain", "Contract Media Css"), it)
        }
        val libraries = area.libraries()
        assertTrue(libraries.toString(), "Contract" in libraries)
        found(CatalogueQuery(library = "Contract"), rule("library", "includesAny", JSONArray().put("Contract")))?.let { assertEquals(all, it) }

        // Newest first, as the server orders them by when they were added.
        val newest = device.orbit.books(CatalogueQuery(sort = CatalogueSort.RecentlyAdded), 0).books.map { it.id }
        val added = items(page().put("sort", JSONArray().put(JSONObject().put("field", "addedAt").put("dir", "desc"))))
            .map { it.getLong("id") }.filter { it in newest }
        assertEquals(added, newest)
    }

    @Test
    fun bookOnlyOnTheServerIsMarkedRatedAndNotedAndTheServerKeepsIt() = runBlocking<Unit> {
        val book = server.bookByTitle("Contract Spine NonXhtml")
        val id = book.bookId.toLong()
        assertTrue("the book is not on this device", device.library.copiesOf(id).isEmpty())
        val before = device.orbit.detail(id)
        try {
            try {
                assertEquals(ReadStatuses.WANT_TO_READ, area.setStatus(id, ReadStatuses.WANT_TO_READ))
                assertEquals(ReadStatuses.WANT_TO_READ, device.orbit.detail(id).status)
                assertTrue(
                    "the catalogue says so, and the shelf's query finds it",
                    device.orbit.books(CatalogueQuery(status = ReadStatuses.WANT_TO_READ), 0).books.any { it.id == id },
                )
            } catch (e: UnsupportedOnServerException) {
                assertNoRoute("PATCH", "/books/$id/status", JSONObject().put("status", "want_to_read"))
            }

            // A status the app has no word for is kept as the server has it, and shown as it is.
            assertEquals(200, server.koreader("PUT", "/koreader/plugin/catalog/books/$id/read-status", JSONObject().put("status", "on_hold")).code)
            assertEquals("on_hold", device.orbit.detail(id).status)

            assertEquals(4, area.rate(id, 4))
            assertEquals(4, device.orbit.detail(id).rating)
            val card = items(page().put("q", "Contract Spine NonXhtml")).single { it.getLong("id") == id }
            assertEquals("as the rounds of syncing read it", 4, card.getInt("rating"))
            assertNull(area.rate(id, null))

            assertEquals("Kept on the server.", area.writeNote(id, "  Kept on the server. "))
            assertEquals("Kept on the server.", device.orbit.detail(id).note)
            assertNull(area.writeNote(id, ""))
            assertNull(device.orbit.detail(id).note)
        } finally {
            server.koreader("PUT", "/koreader/plugin/catalog/books/$id/read-status", JSONObject().put("status", before.status))
            runCatching { area.rate(id, before.rating) }
            runCatching { area.writeNote(id, before.note) }
        }
    }

    @Test
    fun collectionIsMadeFilledRenamedAndDeletedWithoutItsBooks() = runBlocking<Unit> {
        val name = "Perigee contract ${System.currentTimeMillis()}"
        val made = try {
            area.createCollection(name)
        } catch (e: UnsupportedOnServerException) {
            assertNoRoute("POST", "/collections", JSONObject().put("name", name).put("icon", "book"))
            return@runBlocking
        }
        try {
            assertTrue(made.isOwner)
            assertFalse("a new collection is private", made.isPublic)
            val kept = server.jwt("GET", "/collections/${made.id}").json()
            assertEquals("book", kept.getString("icon"))
            assertFalse(kept.getBoolean("isPublic"))
            assertFalse("nothing of syncing is set", kept.optBoolean("syncToKobo"))
            assertTrue(area.collections().any { it.id == made.id && it.isOwner })

            val plain = server.bookByTitle("Contract Plain").bookId.toLong()
            val pdf = server.bookByTitle("Contract Pdf").bookId.toLong()
            area.setInCollection(made.id, plain, inIt = true)
            assertEquals(2, area.setInCollection(made.id, pdf, inIt = true).bookCount)
            assertTrue(made.id in area.collectionsOf(plain))
            assertEquals("the books the app reads", listOf("Contract Plain"), area.collectionBooks(made.id, "", 0).books.map { it.title })
            assertEquals(listOf("Contract Plain"), area.collectionBooks(made.id, "Plain", 0).books.map { it.title })

            // Shared and described in the browser meanwhile: renaming here keeps both.
            assertEquals(200, server.jwt("PATCH", "/collections/${made.id}", JSONObject().put("isPublic", true).put("description", "Kept as it was")).code)
            assertEquals("$name renamed", area.renameCollection(made.id, "$name renamed").name)
            val renamed = server.jwt("GET", "/collections/${made.id}").json()
            assertTrue(renamed.getBoolean("isPublic"))
            assertEquals("Kept as it was", renamed.getString("description"))
            assertEquals("book", renamed.getString("icon"))

            assertEquals("taken out, the book is not deleted", 1, area.setInCollection(made.id, plain, inIt = false).bookCount)
            server.bookByTitle("Contract Plain")
        } finally {
            area.deleteCollection(made.id)
        }
        assertEquals(404, server.jwt("GET", "/collections/${made.id}").code)
        assertThrows(NotOnServerException::class.java) { runBlocking { area.collectionBooks(made.id, "", 0) } }
        // Its books stay on the server.
        server.bookByTitle("Contract Plain")
        server.bookByTitle("Contract Pdf")
    }

    /** Books beside a book of [kind], or, on a server without the route, the route checked to be not there: each route on its own. */
    private suspend fun beside(bookId: Long, kind: Related): List<RemoteBook>? = try {
        area.related(bookId, kind).books
    } catch (e: UnsupportedOnServerException) {
        assertNoRoute("GET", "/books/$bookId/${kind.path}")
        null
    }

    @Test
    fun booksBesideABookAreTheServersOrSayTheyAreNotThere() = runBlocking<Unit> {
        val plain = server.bookByTitle("Contract Plain").bookId.toLong()
        val media = server.bookByTitle("Contract Media Css").bookId.toLong()

        beside(plain, Related.Author)?.let { byAuthor ->
            assertTrue(byAuthor.toString(), byAuthor.any { it.title == "Contract Media Css" })
            assertTrue("not the book itself", byAuthor.none { it.id == plain })
            assertTrue("listed without files", byAuthor.all { !it.filesKnown && it.files.isEmpty() })
            // The page of one of them is downloaded by what the server tells of it alone: one known to be an EPUB.
            val epub = byAuthor.first { it.title == "Contract Media Css" }
            assertTrue(device.orbit.detail(epub.id).files.any { it.format == "epub" })
            // Whatever comes first in the server's order has a file the app reads, as the FB2 does.
            assertTrue(device.orbit.detail(byAuthor.first().id).files.any { it.format in Library.FORMATS })
        }

        beside(media, Related.Series)?.let { series ->
            assertEquals(listOf("Contract Spine NonXhtml"), series.map { it.title })
            assertEquals("2", series.single().seriesNumber.substringBefore('.'))
        }

        // A likeness the server may not have worked out is an answer, empty or not: never a failure.
        beside(plain, Related.Similar)
    }

    private companion object {
        private var shared: ContractDevice? = null

        /** A reader on a device of their own, with nothing downloaded: every book of the server is only there. */
        @Synchronized
        fun readerWithNothingDownloaded(context: Context): ContractDevice = shared ?: ContractDevice(context).also { device ->
            runBlocking {
                if (device.signIn() == DeviceChannel.PasswordNeeded) device.orbit.devicePassword(BookOrbitServer.KO_PASSWORD)
            }
            shared = device
        }
    }
}
