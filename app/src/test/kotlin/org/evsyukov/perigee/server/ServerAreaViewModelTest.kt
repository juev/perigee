package org.evsyukov.perigee.server

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.evsyukov.perigee.sync.Answer
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.CatalogueQuery
import org.evsyukov.perigee.sync.CatalogueSort
import org.evsyukov.perigee.sync.FakeOrbit
import org.evsyukov.perigee.sync.OrbitArea
import org.evsyukov.perigee.sync.ReadStatuses
import org.evsyukov.perigee.sync.Related
import org.evsyukov.perigee.sync.RemoteBook
import org.evsyukov.perigee.sync.ShelfKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
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
 * The richer part of the server's screen, checked on its model: the home page's shelves, the catalogue's
 * filters, collections, the books beside a book, and what the reader keeps of a book on the server. A change
 * that the server takes through PATCH is checked on a device, by OrbitAreaTest and ServerScreenTest: the
 * JDK's HttpURLConnection does not send that method.
 */
@RunWith(RobolectricTestRunner::class)
class ServerAreaViewModelTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val library = Library.inMemory(context)
    private val accounts = Accounts.of(library)
    private val orbit = BookOrbit(accounts, library, "0.0.1")
    private val server = FakeOrbit()
    private val storeScope = CoroutineScope(Dispatchers.IO + Job())
    private val preferences = LibraryPreferences.inFile(File(context.filesDir, "area.preferences_pb"), storeScope)
    private val bookFile = File(context.cacheDir, "area-book.fb2").apply { writeText("<FictionBook>a book of the area</FictionBook>") }

    private val model by lazy {
        val arrivals = Arrivals(
            context, library, Covers(File(context.cacheDir, "area-covers")), preferences,
            { _, _, _ -> BookDescription("Title In The File", listOf("File Author"), "en", "", "", "", null) },
        ) { BookSink { _, _ -> null } }
        ServerViewModel(context, orbit, accounts, library, arrivals, MutableStateFlow(null))
    }

    @After
    fun stopServer() {
        server.close()
        storeScope.cancel()
    }

    @Before
    fun keystore() = FakeAndroidKeyStore.install()

    @Before
    fun downloadsStayInTheApp() = runBlocking<Unit> {
        preferences.update { LibraryPrefs(downloadsFolder = null, downloadsAsked = true) }
    }

    private fun until(what: String, done: (ServerState) -> Boolean): ServerState {
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val state = model.state.value
            if (done(state)) return state
            if (System.currentTimeMillis() > deadline) throw AssertionError("$what; the server was asked ${server.seen.map { "${it.method} ${it.path}" }}, the state is $state")
            Thread.sleep(5)
        }
    }

    /** Lets [millis] pass with the model's work running, as for an answer that comes late. */
    private fun pass(millis: Long) {
        val end = System.currentTimeMillis() + millis
        until("time passes") { System.currentTimeMillis() > end }
    }

    private fun book(
        id: Long, title: String, fileId: Long, format: String = "fb2", author: String = "Server Author",
        series: String? = null, number: Int? = null, genres: List<String> = emptyList(),
    ): JSONObject = JSONObject().put("id", id).put("title", title).put("authors", JSONArray().put(author))
        .put("seriesName", series ?: JSONObject.NULL).put("seriesIndex", number ?: JSONObject.NULL).put("hasCover", false)
        .put("genres", JSONArray(genres))
        .put("files", JSONArray().put(JSONObject().put("id", fileId).put("format", format).put("role", "primary").put("sizeBytes", 100)))

    private fun serverHas(vararg books: JSONObject) {
        server.books = JSONArray().apply { books.forEach { put(it) } }
    }

    private fun signIn(): ServerState {
        until("the account is looked up") { it.loaded }
        model.signIn(server.url, "reader", "right-password")
        return until("signing in is over") { !it.signingIn && it.account != null }
    }

    private fun shelvesCame() = until("the shelves came") { state ->
        ShelfKind.DEFAULT.all { state.shelves[it] != null && state.shelves[it] != ShelfState.Loading }
    }

    private fun listed(title: String) = until("$title is listed") { state -> state.books.any { it.title == title } }

    private fun opened(book: RemoteBook): ServerState {
        model.openBook(book)
        return until("the page of ${book.title} has what the server says of it") { it.detail?.id == book.id && !it.detailLoading }
    }

    private fun saved(what: Personal, bookId: Long = 7) = until("$what of $bookId is saved, or refused") { what !in it.saving(bookId) }

    private fun bodies(path: String) = server.asked(path).map { JSONObject(it.body) }

    @Test
    fun homeComesFirstWithTheServersOwnShelves() {
        serverHas(book(7, "Seven", 70), book(8, "Eight", 80), book(9, "Only Pdf", 90, format = "pdf"))
        server.shelves["continue-reading"] = listOf(8, 7)
        server.shelves["recently-added"] = listOf(9, 8, 7)
        server.shelves["random"] = listOf(7)
        assertEquals(Section.Home, signIn().section)

        val state = shelvesCame()
        assertEquals("in the server's order", listOf(8L, 7L), (state.shelves[ShelfKind.ContinueReading] as ShelfState.Shown).books.map { it.id })
        val recent = state.shelves[ShelfKind.RecentlyAdded] as ShelfState.Shown
        assertEquals("a book the app does not read is left out, and not made up for", listOf(8L, 7L), recent.books.map { it.id })
        assertEquals(1, recent.leftOut)
        assertEquals("an empty shelf is an answer", ShelfState.Shown(emptyList(), 0), state.shelves[ShelfKind.WantToRead])

        val items = bodies("/dashboard/scrollers/batch").single().getJSONArray("items")
        assertEquals(ShelfKind.DEFAULT.map { it.type }, (0 until items.length()).map { items.getJSONObject(it).getString("type") })
        assertTrue("a preview of each, not the whole catalogue", (0 until items.length()).all { items.getJSONObject(it).getInt("limit") == OrbitArea.SHELF_PREVIEW })
        assertTrue("no book is asked about for being on a shelf", server.seen.none { Regex("/api/v1/books/\\d+$").matches(it.path) })
    }

    @Test
    fun shelfTheServerCouldNotFillIsTriedAgainAlone() {
        serverHas(book(7, "Seven", 70))
        server.shelves["random"] = listOf(7)
        server.shelves["recently-added"] = listOf(7)
        server.failedShelves += "random"
        signIn()
        val failed = shelvesCame()
        assertTrue(failed.shelves[ShelfKind.Random] is ShelfState.Failed)
        assertEquals("the others are as the server filled them", listOf(7L), (failed.shelves[ShelfKind.RecentlyAdded] as ShelfState.Shown).books.map { it.id })

        server.failedShelves.clear()
        model.retryShelf(ShelfKind.Random)
        val again = until("the shelf came") { it.shelves[ShelfKind.Random] is ShelfState.Shown }
        assertEquals(listOf(7L), (again.shelves[ShelfKind.Random] as ShelfState.Shown).books.map { it.id })
        assertEquals("that shelf alone was asked for", 1, server.asked("/dashboard/scrollers/random?limit=${OrbitArea.SHELF_PREVIEW}").size)
        assertEquals(1, server.asked("/dashboard/scrollers/batch").size)
        assertEquals(failed.shelves[ShelfKind.RecentlyAdded], again.shelves[ShelfKind.RecentlyAdded])
    }

    @Test
    fun olderServerIsAskedShelfByShelfAndOneItHasNotIsUnsupported() {
        serverHas(book(7, "Listed Title", 70))
        server.shelfBatch = false
        server.shelfTypes = setOf("recently-added", "continue-reading", "want-to-read", "random")
        server.shelves["recently-added"] = listOf(7)
        signIn()

        val state = shelvesCame()
        assertEquals(ShelfState.Unsupported, state.shelves[ShelfKind.UpNext])
        assertEquals(listOf(7L), (state.shelves[ShelfKind.RecentlyAdded] as ShelfState.Shown).books.map { it.id })
        listed("Listed Title")
        assertNull("the catalogue does not depend on the shelves", model.state.value.failure)
    }

    @Test
    fun homeThatCannotBeReachedFailsWithoutTakingTheCatalogue() {
        serverHas(book(7, "Listed Title", 70))
        server.shelvesAnswer = Answer.Dropped
        signIn()

        val state = shelvesCame()
        assertTrue(ShelfKind.DEFAULT.all { state.shelves[it] == ShelfState.Failed(ServerFailure.Unreachable) })
        listed("Listed Title")
        assertNull(model.state.value.failure)

        server.shelvesAnswer = null
        server.shelves["random"] = listOf(7)
        model.refreshShelves()
        until("the home page came") { (it.shelves[ShelfKind.Random] as? ShelfState.Shown)?.books?.map { book -> book.id } == listOf(7L) }
    }

    @Test
    fun seeAllIsTheCataloguesQueryOfAShelfThatHasOne() {
        serverHas(book(7, "Listed Title", 70))
        signIn()
        shelvesCame()

        model.seeAll(ShelfKind.Random)
        assertEquals("a sample has no list behind it", Section.Home, model.state.value.section)

        model.seeAll(ShelfKind.WantToRead)
        until("the catalogue of the shelf is shown") { it.section == Section.Catalogue && it.filters.status == ReadStatuses.WANT_TO_READ && !it.loading }
        val rule = bodies("/books/query").last().getJSONObject("filter").getJSONArray("rules").getJSONObject(1)
        assertEquals("readStatus", rule.getString("field"))
        assertEquals(ReadStatuses.WANT_TO_READ, rule.getJSONArray("value").getString(0))

        model.seeAll(ShelfKind.RecentlyAdded)
        until("the newest books are shown") { it.filters.sort == CatalogueSort.RecentlyAdded && !it.loading }
        val sort = bodies("/books/query").last().getJSONArray("sort").getJSONObject(0)
        assertEquals("addedAt", sort.getString("field"))
        assertEquals("desc", sort.getString("dir"))
        assertEquals("the shelf's own query only", "", model.state.value.filters.status)
    }

    @Test
    fun catalogueIsNarrowedAndOrderedByTheServerAndKeepsItsPhrase() {
        serverHas(
            book(7, "War and Peace", 70, author = "Leo Tolstoy", series = "War Tales", number = 1, genres = listOf("Novel")),
            book(8, "Other", 80, author = "Someone Else"),
        )
        signIn()
        listed("Other")
        model.show(Section.Catalogue)
        model.search("peace")
        until("the phrase is looked up") { it.query == "peace" && !it.loading }

        model.filter(CatalogueQuery(author = "tolstoy", series = "war", genre = "Novel", library = "Books", sort = CatalogueSort.Author))
        val state = until("the catalogue is narrowed") { it.filters.author == "tolstoy" && !it.loading }
        assertEquals("the phrase stays", "peace", state.query)
        assertEquals(listOf(7L), state.books.map { it.id })
        val body = bodies("/books/query").last()
        assertEquals("peace", body.getString("q"))
        val rules = body.getJSONObject("filter").getJSONArray("rules")
        assertEquals(listOf("format", "author", "series", "genre", "library"), (0 until rules.length()).map { rules.getJSONObject(it).getString("field") })
        assertEquals("author", body.getJSONArray("sort").getJSONObject(0).getString("field"))

        model.search("")
        val all = until("the phrase is taken off") { it.query.isEmpty() && !it.loading }
        assertEquals("the filters stay", "tolstoy", all.filters.author)
    }

    @Test
    fun filterTheServerDoesNotTakeSaysSoAndIsTakenOff() {
        serverHas(book(7, "Listed Title", 70))
        server.unknownFields = setOf("genre")
        signIn()
        listed("Listed Title")

        model.filter(CatalogueQuery(genre = "Novel"))
        until("the reader is told") { it.failure == ServerFailure.Unsupported }
        model.filter(CatalogueQuery())
        listed("Listed Title")
        assertNull(model.state.value.failure)
    }

    @Test
    fun cataloguePagesCountOnlyTheBooksTheAppReads() {
        server.paged = true
        val readable = (1..55).map { book(it.toLong(), "Book %02d".format(it), it * 10L) }
        val unreadable = (101..120).map { book(it.toLong(), "Comic $it", it * 10L, format = "cbz") }
        serverHas(*(readable + unreadable).shuffled(java.util.Random(7)).toTypedArray())
        signIn()

        until("the first part") { it.books.size == 50 && it.more && !it.loading }
        model.more()
        val state = until("the rest") { it.books.size == 55 && !it.loading }
        assertFalse(state.more)
        assertTrue("nothing the app does not read", state.books.all { it.id <= 55 })
    }

    @Test
    fun ownAndSharedCollectionsAreToldApart() {
        server.collection("Mine")
        server.collection("Mine shared", public = true)
        server.collection("Theirs", owner = 2, public = true)
        server.collection("Theirs alone", owner = 2)
        signIn()
        model.show(Section.Collections)

        val list = until("the collections came") { it.collections.loaded }.collections.list
        assertEquals(listOf("Mine", "Mine shared", "Theirs"), list.map { it.name })
        assertEquals(listOf(true, true, false), list.map { it.isOwner })
        assertEquals(listOf(false, true, true), list.map { it.isPublic })
    }

    @Test
    fun collectionsBooksAreLookedUpWithinItAndOnlyThoseTheAppReads() {
        serverHas(book(7, "In It", 70), book(8, "In It As Pdf", 80, format = "pdf"), book(9, "Not In It", 90))
        val mine = server.collection("Mine", 7, 8)
        signIn()
        listed("Not In It")
        model.show(Section.Collections)
        model.openCollection(until("the collections came") { it.collections.loaded }.collections.list.single())

        val view = until("its books came") { it.collection?.loaded == true }.collection!!
        assertEquals(listOf(7L), view.books.map { it.id })
        val asked = bodies("/collections/${mine.id}/books/query").single()
        assertEquals("format", asked.getJSONObject("filter").getJSONArray("rules").getJSONObject(0).getString("field"))

        model.searchCollection("in")
        until("the collection is looked up") { it.collection?.query == "in" && it.collection?.loading == false }
        assertEquals("in", bodies("/collections/${mine.id}/books/query").last().getString("q"))
        val state = model.state.value
        assertEquals("the catalogue's phrase is not the collection's", "", state.query)
        assertEquals(CatalogueQuery(), state.filters)
        assertEquals(2, state.books.size)
    }

    @Test
    fun collectionOfAServerWithoutItsQueryIsListedAndNarrowedHere() {
        serverHas(book(7, "In It", 70), book(8, "In It As Pdf", 80, format = "pdf"))
        val mine = server.collection("Mine", 7, 8)
        server.collectionQuery = false
        signIn()
        model.show(Section.Collections)
        model.openCollection(until("the collections came") { it.collections.loaded }.collections.list.single())

        val view = until("its books came") { it.collection?.loaded == true }.collection!!
        assertEquals(listOf(7L), view.books.map { it.id })
        assertEquals(1, server.asked("/collections/${mine.id}/books?page=0&size=50").size)
    }

    @Test
    fun theCollectionLastOpenedIsTheOneShown() {
        serverHas(book(7, "Of A", 70), book(8, "Of B", 80))
        val a = server.collection("A", 7)
        server.collection("B", 8)
        server.slow = { if (it == "/collections/${a.id}/books/query") 1000 else 0 }
        signIn()
        model.show(Section.Collections)
        val list = until("the collections came") { it.collections.loaded }.collections.list
        model.openCollection(list[0])
        model.openCollection(list[1])

        until("the second one's books came") { it.collection?.loaded == true }
        pass(1500)
        val view = model.state.value.collection!!
        assertEquals("B", view.collection.name)
        assertEquals(listOf(8L), view.books.map { it.id })
    }

    @Test
    fun collectionIsMadePrivateWithAnIconAndNothingOfSyncingIsSet() {
        signIn()
        model.show(Section.Collections)
        until("the collections came") { it.collections.loaded }

        model.createCollection("Holiday")
        until("the collection is made") { state -> !state.collectionBusy && state.collections.list.any { it.name == "Holiday" } }
        val sent = server.asked("/collections").single { it.method == "POST" }.let { JSONObject(it.body) }
        assertEquals("Holiday", sent.getString("name"))
        assertEquals(OrbitArea.COLLECTION_ICON, sent.getString("icon"))
        assertFalse(sent.getBoolean("isPublic"))
        assertFalse("syncing to other devices is left as the server has it", sent.has("syncToKobo"))
        val made = server.collections.single()
        assertFalse(made.isPublic)
        assertFalse(made.syncToKobo)

        model.createCollection("Holiday")
        until("the name is said to be taken") { it.collectionFailure == ServerFailure.NameTaken && !it.collectionBusy }
        assertEquals(1, model.state.value.collections.list.count { it.name == "Holiday" })
    }

    private fun keepBookFile(): String {
        val hash = FileHash.partialMd5(bookFile)
        runBlocking { library.keep(bookFile, LocalBook(hash, "fb2", "Title In The File", emptyList(), "", "", "", false)) }
        return hash
    }

    @Test
    fun deletingACollectionLeavesItsBooksOnTheServerAndHere() {
        val hash = keepBookFile()
        server.files[hash] = 7L to 70L
        serverHas(book(7, "Kept", 70))
        val old = server.collection("Old", 7)
        signIn()
        until("the book is known to be on the server") { it.onDevice[70L] == hash }
        model.show(Section.Collections)
        val listed = until("the collections came") { it.collections.loaded }.collections.list.single()

        model.deleteCollection(listed)
        until("the collection is gone") { it.collections.list.isEmpty() && !it.collectionBusy }
        assertTrue(server.collections.none { it.id == old.id })
        assertEquals("the book stays on the server", 1, server.books.length())
        assertEquals("and here", listOf(hash), runBlocking { library.books().first() }.map { it.id })
        assertTrue("nothing but the collection is deleted", server.seen.filter { it.method == "DELETE" }.all { it.path == "/api/v1/collections/${old.id}" })
    }

    @Test
    fun anotherReadersCollectionRefusesChangesAndSaysSo() {
        val theirs = server.collection("Theirs", owner = 2, public = true)
        signIn()
        model.show(Section.Collections)
        val listed = until("the collections came") { it.collections.loaded }.collections.list.single()
        assertFalse(listed.isOwner)

        model.deleteCollection(listed)
        until("the refusal is told") { it.collectionFailure == ServerFailure.NotAllowed }
        assertEquals("it stays", listOf(theirs.id), model.state.value.collections.list.map { it.id })
        assertTrue(server.collections.contains(theirs))
    }

    @Test
    fun serverOnlyBookIsPutIntoACollectionAndTakenOutWithoutDownloading() {
        serverHas(book(7, "Listed Title", 70))
        val mine = server.collection("Mine")
        val other = server.collection("Other", 7)
        server.collection("Theirs", 7, owner = 2, public = true)
        signIn()
        opened(listed("Listed Title").books.single())

        model.chooseCollections()
        val asked = until("the reader's collections came") { it.membership?.loading == false }.membership!!
        assertEquals("only the reader's own can take it", listOf("Mine", "Other"), asked.owned.map { it.name })
        assertEquals(setOf(other.id), asked.memberOf)

        model.setInCollection(asked.owned[0], inIt = true)
        until("the book is in it") { mine.id in it.membership!!.memberOf && it.membership!!.busy.isEmpty() }
        assertEquals(listOf(7L), mine.books)
        model.setInCollection(asked.owned[1], inIt = false)
        until("the book is out of it") { other.id !in it.membership!!.memberOf && it.membership!!.busy.isEmpty() }
        assertTrue(other.books.isEmpty())
        assertTrue("nothing was downloaded for it", runBlocking { library.books().first() }.isEmpty())
        assertTrue(server.seen.none { "/download" in it.path })
    }

    @Test
    fun serverOnlyBookIsRatedOnlyOnceTheServerConfirmsIt() {
        serverHas(book(7, "Listed Title", 70))
        signIn()
        opened(listed("Listed Title").books.single())

        model.rate(4)
        assertTrue("the stars are on their way, not shown as set", Personal.Rating in model.state.value.saving(7))
        val rated = saved(Personal.Rating)
        assertEquals(4, rated.detail?.rating)
        assertEquals(4, server.ratings[7])
        val sent = bodies("/books/bulk-set-rating").single()
        assertEquals(7L, sent.getJSONArray("bookIds").getLong(0))
        assertEquals(4, sent.getInt("rating"))

        // The server keeps the rating locked: it answers as if it set it, and the read back says otherwise.
        server.ratingsLocked = true
        model.rate(2)
        val refused = saved(Personal.Rating)
        assertEquals(PersonalFailure(7, Personal.Rating, ServerFailure.NotSaved), refused.personalFailure)
        assertEquals(4, refused.detail?.rating)
    }

    @Test
    fun ratingGoesThroughTheDevicesChannelForAReaderWhoMayNotEditTheLibrary() {
        serverHas(book(7, "Listed Title", 70))
        server.ratingThroughSession = false
        signIn()
        opened(listed("Listed Title").books.single())

        model.rate(3)
        assertEquals(3, saved(Personal.Rating).detail?.rating)
        assertEquals(1, server.asked("/koreader/plugin/catalog/books/7/rating").size)
        assertNull(model.state.value.personalFailure)
    }

    @Test
    fun ratingABookTheServerHasNoMoreSaysSo() {
        serverHas(book(7, "Listed Title", 70))
        signIn()
        opened(listed("Listed Title").books.single())
        server.books = JSONArray()

        model.rate(3)
        assertEquals(PersonalFailure(7, Personal.Rating, ServerFailure.NotFound), saved(Personal.Rating).personalFailure)
    }

    @Test
    fun ratingADownloadedBookOverrulesTheOlderOneNotSentFromHere() {
        val hash = keepBookFile()
        server.files[hash] = 7L to 70L
        serverHas(book(7, "Kept", 70))
        signIn()
        until("the book is known to be on the server") { it.onDevice[70L] == hash }
        runBlocking { library.setRating(hash, 2) }
        until("the stars given here wait to be sent") { 7L in it.unsentOnDevice }

        opened(model.state.value.books.single())
        model.rate(5)
        assertEquals(5, saved(Personal.Rating).detail?.rating)
        val here = checkNotNull(runBlocking { library.book(hash) })
        assertEquals(5, here.rating)
        assertFalse("the older stars from here are not sent over the confirmed ones", here.stateUnsent)

        runBlocking { orbit.sync() }
        assertEquals(5, server.ratings[7])
        assertTrue(server.asked("/koreader/plugin/catalog/books/7/rating").none { JSONObject(it.body).opt("rating") == 2 })
    }

    @Test
    fun booksBesideABookOpenTheirPagesAndTheWayBackReturnsThrough() {
        serverHas(
            book(7, "First Of Series", 70, author = "Author A", series = "The Series", number = 1),
            book(8, "Second Of Series", 80, author = "Author A", series = "The Series", number = 2),
            book(9, "By Another", 90, author = "Author B"),
            book(10, "A Comic By A", 100, format = "cbz", author = "Author A"),
        )
        server.similar[7] = listOf(9)
        server.downloads[80] = Answer(200, bookFile.readBytes(), "application/x-fictionbook+xml")
        signIn()
        val first = listed("First Of Series").books.first { it.id == 7L }
        opened(first)

        val beside = until("the books beside it came") { state -> Related.entries.all { state.related[it] is RelatedState.Shown } }.related
        fun ids(kind: Related) = (beside[kind] as RelatedState.Shown).books.map { it.id }
        assertEquals(listOf(9L), ids(Related.Similar))
        assertEquals("the comic is left out", listOf(8L), ids(Related.Author))
        assertEquals("the book itself is not beside itself", listOf(8L), ids(Related.Series))

        val second = (beside[Related.Author] as RelatedState.Shown).books.single()
        assertFalse("it came without its files", second.filesKnown)
        model.openRelated(second)
        val page = until("its page has what the server says of it") { it.opened?.id == 8L && it.detail?.id == 8L }
        assertEquals(listOf(7L), page.trail.map { it.id })
        val file = readableFiles(page.detail!!.files).single()
        assertEquals(80L, file.id)
        model.download(second, file)
        until("the book is in the library") { 80L in it.onDevice }

        model.closeBook()
        val back = model.state.value
        assertEquals("back to the book it was opened from", 7L, back.opened?.id)
        assertEquals("with what was told of it, at once", 7L, back.detail?.id)
        assertEquals(beside, back.related)
        assertEquals(1, server.asked("/books/7").size)
        assertEquals(1, server.asked("/books/7/author-books").size)

        model.closeBook()
        assertNull(model.state.value.opened)
        assertTrue(model.state.value.trail.isEmpty())
    }

    @Test
    fun nothingBesideABookAndAServerThatListsNoneAreNoFailures() {
        serverHas(book(7, "Alone", 70), book(8, "Also Alone", 80, author = "Someone Else"))
        signIn()
        val books = listed("Alone").books
        opened(books.first { it.id == 7L })
        val nothing = until("the server answered") { state -> state.related.size == 2 && state.related.values.all { it is RelatedState.Shown } }
        assertTrue((nothing.related[Related.Similar] as RelatedState.Shown).books.isEmpty())
        assertNull("a book in no series is not asked about one", nothing.related[Related.Series])
        assertNull(nothing.detailFailure)

        server.relatedBooks = false
        opened(books.first { it.id == 8L })
        val none = until("the server answered") { state -> state.related.size == 2 && state.related.values.all { it == RelatedState.Unsupported } }
        assertNull(none.detailFailure)
    }

    @Test
    fun aBookOnSeveralShelvesIsDownloadedOnce() {
        serverHas(book(7, "Everywhere", 70))
        server.shelves["continue-reading"] = listOf(7)
        server.shelves["recently-added"] = listOf(7)
        server.downloads[70] = Answer(200, bookFile.readBytes(), "application/x-fictionbook+xml")
        signIn()
        val shelves = shelvesCame().shelves
        val reading = (shelves[ShelfKind.ContinueReading] as ShelfState.Shown).books.single()
        val recent = (shelves[ShelfKind.RecentlyAdded] as ShelfState.Shown).books.single()
        assertEquals("one book wherever it is shown", reading, recent)

        val file = readableFiles(reading).single()
        model.download(reading, file)
        model.download(recent, file)
        until("the book is in the library") { 70L in it.onDevice && it.downloads.isEmpty() }
        model.download(listed("Everywhere").books.single(), file)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("downloaded once", 1, server.asked("/books/files/70/download").size)
        assertEquals(1, runBlocking { library.books().first() }.size)
    }

    @Test
    fun whatComesForAReaderSignedOutMeanwhileIsNotShown() {
        serverHas(book(7, "Listed Title", 70))
        server.collection("Mine")
        signIn()
        val seven = listed("Listed Title").books.single()
        server.slow = { if (it == "/collections" || it == "/books/7") 1500 else 0 }
        model.show(Section.Collections)
        model.openBook(seven)

        model.signOut()
        until("signed out") { it.account == null }
        pass(2000)
        val state = model.state.value
        assertFalse("no collections of the reader before", state.collections.loaded)
        assertTrue(state.collections.list.isEmpty())
        assertNull(state.opened)
        assertNull(state.detail)

        server.slow = { 0 }
        signIn()
        opened(listed("Listed Title").books.single())
        assertEquals("nothing kept of the reader before is shown as known", 2, server.asked("/books/7").size)
    }

    @Test
    fun sessionThatEndsOnTheHomePageAsksToSignInAgain() {
        signIn()
        shelvesCame()
        server.access.clear()
        server.refresh.clear()

        model.refreshShelves()
        until("the session is over") { it.sessionOver }
    }

    /** A second server, with its own books under the same numbers as the first's. */
    private val other = FakeOrbit()

    @After
    fun stopOther() = other.close()

    private fun signInTo(to: FakeOrbit, user: String = "reader"): ServerState {
        until("the account is looked up") { it.loaded && !it.signingIn }
        model.signIn(to.url, user, "right-password")
        return until("signed in to ${to.url}") { !it.signingIn && it.account?.url == to.url }
    }

    private fun arrived(at: FakeOrbit, path: String, count: Int = 1) =
        until("$path is asked of ${at.url}") { at.asked(path).size >= count }

    @Test
    fun answerForAReaderSignedInBeforeIsNotTakenAndAsksNothingMore() {
        serverHas(book(7, "First Server's Seven", 70))
        other.books = JSONArray().put(book(7, "Second Server's Seven", 70))
        signIn()
        opened(listed("First Server's Seven").books.single())
        server.hold("/books/bulk-set-rating")
        model.rate(4)
        arrived(server, "/books/bulk-set-rating")

        signInTo(other)
        val theirs = opened(listed("Second Server's Seven").books.single())
        assertTrue("the first reader's change is not shown as on its way here", theirs.saving(7).isEmpty())
        server.release("/books/bulk-set-rating")
        pass(1500)

        val state = model.state.value
        assertEquals(other.url, state.account?.url)
        assertNull("the second server's book is as that server has it", state.detail?.rating)
        assertTrue(state.saving(7).isEmpty())
        assertNull(state.personalFailure)
        assertFalse(state.sessionOver)
        assertEquals("the first server's answer was not read back under the second reader", 1, server.asked("/books/7").size)
        assertTrue("and nothing was asked of the second server for it", other.asked("/books/bulk-set-rating").isEmpty())
        assertEquals(1, other.asked("/books/7").size)
    }

    @Test
    fun refusalForAReaderSignedInBeforeDoesNotEndTheNextOnesSession() {
        serverHas(book(7, "First Server's Seven", 70))
        other.books = JSONArray().put(book(7, "Second Server's Seven", 70))
        signIn()
        opened(listed("First Server's Seven").books.single())
        server.hold("/books/bulk-set-rating")
        model.rate(4)
        arrived(server, "/books/bulk-set-rating")

        signInTo(other)
        opened(listed("Second Server's Seven").books.single())
        // The first server ends that reader's session meanwhile.
        server.access.clear()
        server.refresh.clear()
        server.release("/books/bulk-set-rating")
        pass(1500)

        val state = model.state.value
        assertFalse("the second reader is still signed in", state.sessionOver)
        assertEquals(other.url, state.account?.url)
        assertTrue("their tokens are kept", runBlocking { accounts.tokens() }?.refresh.orEmpty().isNotEmpty())
        assertEquals("the first server is not asked again with the second reader's token", 1, server.asked("/books/bulk-set-rating").size)
        assertTrue(other.asked("/auth/refresh").isEmpty())
        listed("Second Server's Seven")
    }

    @Test
    fun collectionMadeForAReaderSignedInBeforeIsNotFilledUnderTheNextOne() {
        serverHas(book(7, "First Server's Seven", 70))
        other.books = JSONArray().put(book(7, "Second Server's Seven", 70))
        val theirs = other.collection("Theirs")
        signIn()
        opened(listed("First Server's Seven").books.single())
        model.chooseCollections()
        until("the reader's collections came") { it.membership?.loading == false }
        server.hold("/collections")
        model.createCollection("Holiday", withOpenBook = true)
        arrived(server, "/collections", count = 2)

        signInTo(other)
        server.release("/collections")
        pass(1500)

        assertTrue("the book is put into no collection of the first server", server.seen.none { it.path.matches(Regex("/api/v1/collections/\\d+/books")) })
        assertTrue("nor of the second", other.seen.none { it.method == "POST" && it.path.startsWith("/api/v1/collections") })
        assertTrue(theirs.books.isEmpty())
        assertTrue(model.state.value.collections.list.none { it.name == "Holiday" })
    }

    @Test
    fun aSaveOfOneBookDoesNotEndAnothersAndAPageOpenedAgainShowsItsOwn() {
        serverHas(book(7, "Seven", 70), book(8, "Eight", 80))
        signIn()
        val books = listed("Eight").books
        val seven = books.first { it.id == 7L }
        val eight = books.first { it.id == 8L }
        opened(seven)
        opened(eight)
        // What each rating is read back with is held, so that each change stays on its way until let go.
        server.hold("/books/7")
        server.hold("/books/8")

        model.openBook(seven)
        model.rate(4)
        arrived(server, "/books/bulk-set-rating")
        model.openBook(eight)
        model.rate(2)
        arrived(server, "/books/bulk-set-rating", count = 2)
        assertEquals(setOf(Personal.Rating), model.state.value.saving(7))
        assertEquals(setOf(Personal.Rating), model.state.value.saving(8))

        server.release("/books/7")
        until("the first book's stars are saved") { it.saving(7).isEmpty() }
        assertEquals("the second book's are still on their way", setOf(Personal.Rating), model.state.value.saving(8))

        // The page of the second book closed and opened again while its stars are on their way: they are not asked for twice.
        model.closeBook()
        model.openBook(eight)
        assertEquals(setOf(Personal.Rating), model.state.value.saving(8))
        model.rate(5)
        assertEquals(2, server.asked("/books/bulk-set-rating").size)

        server.release("/books/8")
        val done = until("the second book's stars are saved") { it.saving(8).isEmpty() }
        assertEquals(2, done.detail?.rating)
        assertEquals(4, server.ratings[7])
        assertEquals(2, server.ratings[8])
        model.openBook(seven)
        assertEquals("the first book's page shows what the server confirmed", 4, model.state.value.detail?.rating)
    }

    @Test
    fun collectionNotMadeFromABooksPageSaysWhyThereAndOneMadeWithoutTheBookIsKept() {
        serverHas(book(7, "Listed Title", 70))
        server.collection("Taken")
        signIn()
        opened(listed("Listed Title").books.single())
        assertEquals("from the home page", Section.Home, model.state.value.section)
        model.chooseCollections()
        until("the reader's collections came") { it.membership?.loading == false }

        model.createCollection("Taken", withOpenBook = true)
        val taken = until("the name is said to be taken") { it.membership?.createFailure == ServerFailure.NameTaken }
        assertFalse(taken.membership!!.creating)
        assertEquals("the name typed is kept", 0, taken.membership!!.created)
        assertNull("it is said in the choice of collections, not elsewhere", taken.collectionFailure)

        server.collectionWriteAnswer = Answer(403, """{"statusCode":403,"message":"Forbidden"}""")
        model.createCollection("Mine", withOpenBook = true)
        until("the refusal is told") { it.membership?.createFailure == ServerFailure.NotAllowed }

        server.collectionWriteAnswer = null
        server.membershipAnswer = Answer(500, """{"statusCode":500,"message":"Internal server error"}""")
        model.createCollection("Fresh", withOpenBook = true)
        val made = until("the collection is made") { it.membership?.created == 1 }.membership!!
        assertFalse(made.creating)
        assertNull(made.createFailure)
        assertEquals("the book is said not to be in it", ServerFailure.Unreachable, made.failure)
        val fresh = made.owned.single { it.name == "Fresh" }
        assertFalse(fresh.id in made.memberOf)
        assertTrue("it is kept with the reader's collections", model.state.value.collections.list.any { it.id == fresh.id })
        assertTrue(server.collections.single { it.name == "Fresh" }.books.isEmpty())
    }

    @Test
    fun takingABookOutOfTheCollectionLookedThroughLeavesNoBookAfterItSkipped() {
        val all = (1..51).map { book(it.toLong(), "Book %02d".format(it), it * 10L) }
        serverHas(*all.toTypedArray())
        val big = server.collection("Big", *(1..51).map { it.toLong() }.toLongArray())
        signIn()
        model.show(Section.Collections)
        model.openCollection(until("the collections came") { it.collections.loaded }.collections.list.single())
        val first = until("the first part") { it.collection?.loaded == true && it.collection?.books?.size == 50 }.collection!!
        assertTrue(first.more)
        assertFalse("the last book is not shown yet", first.books.any { it.id == 51L })

        opened(first.books.first { it.id == 3L })
        model.chooseCollections()
        until("the reader's collections came") { it.membership?.loading == false }
        model.setInCollection(model.state.value.membership!!.owned.single(), inIt = false)

        val again = until("the collection is asked for again") { state ->
            state.collection?.loading == false && state.collection?.books?.none { it.id == 3L } == true
        }.collection!!
        assertEquals((1..51).filter { it != 3 }.map { it.toLong() }.take(50), again.books.map { it.id })
        assertFalse("the last book is in the part shown: nothing is left behind", again.more)
        assertFalse(big.books.contains(3L))
    }

    @Test
    fun partsOfACollectionWithNoBookTheAppReadsDoNotEndIt() {
        val pdfs = (1..50).map { book(it.toLong(), "Pdf %02d".format(it), it * 10L, format = "pdf") }
        serverHas(*(pdfs + book(101, "Readable One", 1010) + book(102, "Readable Two", 1020)).toTypedArray())
        server.collection("Mixed", *((1..50).map { it.toLong() } + 101L + 102L).toLongArray())
        server.collectionQuery = false
        signIn()
        model.show(Section.Collections)
        model.openCollection(until("the collections came") { it.collections.loaded }.collections.list.single())

        val first = until("the first part came") { it.collection?.loaded == true && it.collection?.loading == false }.collection!!
        assertTrue("a part of only PDFs shows nothing", first.books.isEmpty())
        assertTrue("and the collection is not over", first.more)
        assertEquals(1, first.pages)
        model.moreOfCollection()
        val second = until("the next part came") { it.collection?.pages == 2 && it.collection?.loading == false }.collection!!
        assertEquals(listOf(101L, 102L), second.books.map { it.id })
        assertFalse(second.more)

        model.searchCollection("two")
        val found = until("the collection is looked up") { it.collection?.query == "two" && it.collection?.loaded == true && it.collection?.loading == false }.collection!!
        assertEquals("only the book that has the phrase", listOf(102L), found.books.map { it.id })
        model.searchCollection("nowhere")
        val none = until("nothing is found") { it.collection?.query == "nowhere" && it.collection?.loaded == true && it.collection?.loading == false }.collection!!
        assertTrue(none.books.isEmpty())
        assertFalse(none.more)
    }

    @Test
    fun bookBesideABookThatIsInTheLibraryIsKnownToBeThere() {
        val hash = keepBookFile()
        server.files[hash] = 8L to 80L
        serverHas(book(7, "First", 70, author = "Author A"), book(8, "Kept Here", 80, author = "Author A"))
        signIn()
        until("the book is known to be on the server") { it.onDeviceByBook[8L] == hash }
        opened(listed("First").books.first { it.id == 7L })

        val beside = until("the books by the author came") { it.related[Related.Author] is RelatedState.Shown }
        val kept = (beside.related[Related.Author] as RelatedState.Shown).books.single()
        assertTrue("it came without files", kept.files.isEmpty())
        assertEquals("and is known to be in the library all the same", hash, beside.onDeviceByBook[kept.id])
    }

    @Test
    fun librariesThatCannotBeAskedForSayWhy() {
        signIn()
        server.librariesAnswer = Answer(500, """{"statusCode":500,"message":"Internal server error"}""")
        model.loadLibraries()
        until("the failure is told") { it.librariesFailure == ServerFailure.Unreachable }

        server.librariesAnswer = Answer(404, """{"message":"Cannot GET /api/v1/libraries","error":"Not Found","statusCode":404}""")
        model.loadLibraries()
        until("the server has none") { it.librariesFailure == ServerFailure.Unsupported }

        server.librariesAnswer = null
        model.loadLibraries()
        val state = until("the libraries came") { it.libraries.isNotEmpty() }
        assertEquals(listOf("Books"), state.libraries)
        assertNull(state.librariesFailure)
    }

    @Test
    fun collectionAskedForAgainAndFailingIsAskedForAgainWholeOnTheNextTry() {
        val all = (1..51).map { book(it.toLong(), "Book %02d".format(it), it * 10L) }
        serverHas(*all.toTypedArray())
        server.collection("Big", *(1..51).map { it.toLong() }.toLongArray())
        signIn()
        model.show(Section.Collections)
        model.openCollection(until("the collections came") { it.collections.loaded }.collections.list.single())
        val first = until("the first part") { it.collection?.loaded == true && it.collection?.books?.size == 50 }.collection!!
        opened(first.books.first { it.id == 3L })
        model.chooseCollections()
        until("the reader's collections came") { it.membership?.loading == false }

        // The book is taken out; asking for the collection again then fails.
        server.collectionBooksAnswer = Answer(500, """{"statusCode":500,"message":"Internal server error"}""")
        model.setInCollection(model.state.value.membership!!.owned.single(), inIt = false)
        val failed = until("the failure is told") { it.collection?.failure != null }.collection!!
        assertTrue("what is shown is from before", failed.stale)

        server.collectionBooksAnswer = null
        model.retryCollection()
        val again = until("the collection is asked for again") { it.collection?.let { view -> !view.loading && view.failure == null && !view.stale } == true }.collection!!
        assertEquals("from its start, with no book skipped", (1..51).filter { it != 3 }.map { it.toLong() }.take(50), again.books.map { it.id })
        assertFalse(again.more)
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
