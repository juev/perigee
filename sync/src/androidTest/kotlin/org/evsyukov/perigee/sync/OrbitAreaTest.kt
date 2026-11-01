package org.evsyukov.perigee.sync

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.ReadingStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the reader does on the server beyond its list of books, against the stand-in server: the shelves'
 * answers, collections, the books beside a book, and the reader's status, stars and note of a book, with
 * how those meet what waits to be sent from the library. On a device, where PATCH is sent.
 */
@RunWith(AndroidJUnit4::class)
class OrbitAreaTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var clock = 1_700_000_000_000L
    private val library = Library.inMemory(context) { clock }
    private val accounts = Accounts.of(library) { clock }
    private val orbit = BookOrbit(accounts, library, "0.0.1", now = { clock })
    private val area get() = orbit.area
    private val server = FakeOrbit()

    @Before
    fun signIn() = runBlocking<Unit> { orbit.signIn(server.url, "reader", "right-password") }

    @After
    fun stop() = server.close()

    private inline fun <reified T : Throwable> failing(crossinline block: suspend () -> Unit): T =
        assertThrows(T::class.java) { runBlocking { block() } }

    private fun book(id: Long, title: String, format: String = "epub", author: String = "Author", series: String? = null, number: Int? = null) =
        JSONObject().put("id", id).put("title", title).put("authors", JSONArray().put(author)).put("hasCover", true)
            .put("seriesName", series ?: JSONObject.NULL).put("seriesIndex", number ?: JSONObject.NULL).put("genres", JSONArray().put("Novel"))
            .put("files", JSONArray().put(JSONObject().put("id", id * 10).put("format", format).put("role", "primary").put("sizeBytes", 2048)))

    private fun serverHas(vararg books: JSONObject) {
        server.books = JSONArray().apply { books.forEach { put(it) } }
    }

    /** A book of the library whose file the server has, as its book [serverId]. */
    private suspend fun downloaded(name: String, serverId: Long = 7): Book {
        val file = File(context.cacheDir, name).apply { writeText("the text of $name ${System.nanoTime()}") }
        val book = library.open(file, name, "")
        server.files[book.id] = serverId to serverId * 10
        library.onServer(book.id, serverId, serverId * 10)
        library.stateSent(book.id, checkNotNull(library.book(book.id)).stateChangedAt)
        return checkNotNull(library.book(book.id))
    }

    private suspend fun fresh(book: Book): Book = checkNotNull(library.book(book.id))

    private fun later() {
        clock += 60_000
    }

    @Test
    fun shelvesAreTheServersSamplesWithoutItsTotals() = runBlocking<Unit> {
        serverHas(book(7, "Seven"), book(8, "Eight", format = "cbz"))
        server.shelves["recently-added"] = listOf(8, 7)
        server.shelves["random"] = listOf(7)
        server.failedShelves += "random"

        val shelves = area.shelves()
        assertEquals(Shelf.Books(listOf(orbit.books("", 0).books.single()), leftOut = 1), shelves[ShelfKind.RecentlyAdded])
        assertEquals(Shelf.Failed, shelves[ShelfKind.Random])
        assertEquals(Shelf.Books(emptyList()), shelves[ShelfKind.WantToRead])

        // One shelf alone answers with a total the app does not take: it counts this month, or nothing.
        val one = area.shelf(ShelfKind.RecentlyAdded)
        assertEquals(1, (one as Shelf.Books).books.size)
        server.shelfTypes = server.shelfTypes - "up-next-in-series"
        assertEquals(Shelf.Unsupported, area.shelf(ShelfKind.UpNext))
        server.shelfAnswer = Answer(500, """{"statusCode":500,"message":"Internal server error"}""")
        assertEquals(Shelf.Failed, area.shelf(ShelfKind.Random))
    }

    @Test
    fun aBooksPageCarriesWhatTheReaderKeepsOfIt() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))
        server.statuses[7] = "on_hold"
        server.ratings[7] = 4
        server.reviews[7] = "Worth a second look."
        val mine = server.collection("Mine", 7)

        val detail = orbit.detail(7)
        assertEquals("a status the app has no word for is kept as it is", "on_hold", detail.status)
        assertEquals(4, detail.rating)
        assertEquals("Worth a second look.", detail.note)
        assertEquals(listOf("Novel"), detail.genres)
        assertEquals("Books", detail.libraryName)
        assertEquals(listOf(RemoteFile(7, 70, "epub", 2048)), detail.files)
        assertEquals(setOf(mine.id), area.collectionsOf(7))

        server.statuses.remove(7)
        server.ratings.remove(7)
        server.reviews.remove(7)
        val none = orbit.detail(7)
        assertEquals(ReadStatuses.UNREAD, none.status)
        assertNull(none.rating)
        assertNull(none.note)
    }

    @Test
    fun statusIsSetForABookThatIsOnlyOnTheServer() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))

        assertEquals(ReadStatuses.WANT_TO_READ, area.setStatus(7, ReadStatuses.WANT_TO_READ))
        assertEquals("want_to_read", server.statuses[7])
        assertEquals(JSONObject().put("status", "want_to_read").toString(), server.asked("/books/7/status").single().body)
        assertEquals("the catalogue says so too", "want_to_read", orbit.books("", 0).books.single().status)

        assertEquals(ReadStatuses.ABANDONED, area.setStatus(7, ReadStatuses.ABANDONED))
        failing<NotSavedException> { area.setStatus(7, "rereading") }
        assertEquals("a status the server refused leaves the one it had", "abandoned", server.statuses[7])
        server.books = JSONArray()
        failing<NotOnServerException> { area.setStatus(7, ReadStatuses.READ) }
    }

    @Test
    fun statusSetOnTheServersPageIsNotUndoneByAnOlderOneFromTheLibrary() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))
        val book = downloaded("status.fb2")
        later()
        library.setStatus(book.id, ReadingStatus.Read)
        assertTrue(fresh(book).statusUnsent)

        later()
        area.setStatus(7, ReadStatuses.ON_HOLD)
        assertEquals("the reader's own word here stays", ReadingStatus.Read, fresh(book).status)
        assertFalse("and is not sent over the one just set", fresh(book).statusUnsent)

        orbit.sync()
        assertEquals("on_hold", server.statuses[7])
        assertTrue(server.asked("/koreader/plugin/catalog/books/7/read-status").isEmpty())

        area.setStatus(7, ReadStatuses.WANT_TO_READ)
        assertEquals(ReadingStatus.ToRead, fresh(book).status)
    }

    @Test
    fun noteIsWrittenAndTakenOffAndAnOlderOneFromTheLibraryIsNotSentOverIt() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))
        assertEquals("Read in a day.", area.writeNote(7, "  Read in a day.  "))
        assertEquals("Read in a day.", server.reviews[7])
        assertNull(area.writeNote(7, " "))
        assertNull(server.reviews[7])

        val book = downloaded("note.fb2")
        later()
        library.setReview(book.id, "Written in the library.")
        later()
        area.writeNote(7, "Written on the server's page.")
        assertEquals("Written on the server's page.", fresh(book).review)
        assertFalse(fresh(book).reviewUnsent)
        orbit.sync()
        assertEquals("Written on the server's page.", server.reviews[7])

        server.noteAnswer = Answer(403, """{"statusCode":403,"message":"Forbidden"}""")
        failing<NotAllowedException> { area.writeNote(7, "Not mine to write.") }
        assertEquals("Written on the server's page.", fresh(book).review)
    }

    @Test
    fun ratingIsSetThroughTheSessionOrTheDevicesChannelAndReadBack() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))
        assertEquals(5, area.rate(7, 5))
        assertEquals(5, server.ratings[7])
        assertNull(area.rate(7, null))

        server.ratingThroughSession = false
        assertEquals(2, area.rate(7, 2))
        assertEquals(1, server.asked("/koreader/plugin/catalog/books/7/rating").size)

        server.ratingThroughSession = true
        server.ratingsLocked = true
        failing<NotSavedException> { area.rate(7, 4) }
        assertEquals(2, server.ratings[7])
    }

    @Test
    fun collectionIsMadeRenamedAndDeletedAndOnlyItsNameChanges() = runBlocking<Unit> {
        serverHas(book(7, "Seven"), book(8, "Eight", format = "pdf"))
        val made = area.createCollection("  Holiday ")
        assertEquals("Holiday", made.name)
        assertTrue(made.isOwner)
        assertFalse(made.isPublic)
        assertEquals(OrbitArea.COLLECTION_ICON, made.icon)
        failing<NameTakenException> { area.createCollection("Holiday") }

        // Made public, given a description and synced to a reader's Kobo in the browser meanwhile.
        val kept = server.collections.single()
        kept.isPublic = true
        kept.description = "Beach reads"
        kept.syncToKobo = true
        kept.icon = "sun"
        val renamed = area.renameCollection(made.id, "Summer")
        assertEquals("Summer", renamed.name)
        assertEquals("only the name is sent", JSONObject().put("name", "Summer").toString(), server.asked("/collections/${made.id}").last { it.method == "PATCH" }.body)
        assertTrue(kept.isPublic)
        assertEquals("Beach reads", kept.description)
        assertTrue(kept.syncToKobo)
        assertEquals("sun", kept.icon)

        assertEquals(1, area.setInCollection(made.id, 7, inIt = true).bookCount)
        area.setInCollection(made.id, 8, inIt = true)
        assertEquals("the books the app reads", listOf(7L), area.collectionBooks(made.id, "", 0).books.map { it.id })
        assertEquals("taken out, the book is not deleted", 1, area.setInCollection(made.id, 7, inIt = false).bookCount)

        area.deleteCollection(made.id)
        assertTrue(server.collections.isEmpty())
        area.deleteCollection(made.id)
        assertEquals("its books stay", 2, server.books.length())
    }

    @Test
    fun anotherReadersCollectionIsOnlyLookedThrough() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))
        val theirs = server.collection("Theirs", 7, owner = 2, public = true)
        val hidden = server.collection("Hidden", 7, owner = 2)

        val seen = area.collections().single()
        assertFalse(seen.isOwner)
        assertEquals(listOf(7L), area.collectionBooks(theirs.id, "", 0).books.map { it.id })
        failing<NotAllowedException> { area.renameCollection(theirs.id, "Mine now") }
        failing<NotAllowedException> { area.setInCollection(theirs.id, 7, inIt = false) }
        failing<NotAllowedException> { area.deleteCollection(theirs.id) }
        failing<NotAllowedException> { area.collectionBooks(hidden.id, "", 0) }
        failing<NotOnServerException> { area.collectionBooks(9999, "", 0) }
        assertEquals("Theirs", theirs.name)
        assertEquals(listOf(7L), theirs.books)
    }

    @Test
    fun serverWithoutCollectionsSaysSo() = runBlocking<Unit> {
        server.collectionsAnswer = Answer(404, """{"message":"Cannot GET /api/v1/collections","error":"Not Found","statusCode":404}""")
        failing<UnsupportedOnServerException> { area.collections() }
    }

    @Test
    fun booksBesideABookComeWithoutTheirFiles() = runBlocking<Unit> {
        serverHas(
            book(7, "First", series = "Series", number = 1),
            book(8, "Second", series = "Series", number = 2),
            book(9, "Elsewhere", author = "Other"),
            book(10, "Heard", format = "m4b"),
        )
        server.similar[7] = listOf(9, 10)

        val similar = area.related(7, Related.Similar)
        assertEquals(listOf(9L), similar.books.map { it.id })
        assertEquals("an audiobook is left out", 1, similar.leftOut)
        val listed = similar.books.single()
        assertFalse(listed.filesKnown)
        assertTrue(listed.files.isEmpty())
        assertEquals(listOf("Other"), listed.authors)
        assertTrue(listed.hasCover)
        assertEquals(listOf(8L), area.related(7, Related.Author).books.map { it.id })
        val series = area.related(7, Related.Series).books.single()
        assertEquals("2", series.seriesNumber)
        assertTrue("a book listed beside none is no failure", area.related(9, Related.Similar).books.isEmpty())

        failing<NotOnServerException> { area.related(99, Related.Author) }
        server.relatedBooks = false
        failing<UnsupportedOnServerException> { area.related(7, Related.Similar) }
    }

    @Test
    fun catalogueIsNarrowedByTheServersRules() = runBlocking<Unit> {
        serverHas(book(7, "Seven", author = "Leo Tolstoy", series = "War", number = 1), book(8, "Eight", author = "Other"))
        server.statuses[8] = "reading"

        assertEquals(listOf(7L), orbit.books(CatalogueQuery(author = "tolstoy"), 0).books.map { it.id })
        assertEquals(listOf(8L), orbit.books(CatalogueQuery(status = ReadStatuses.READING), 0).books.map { it.id })
        assertEquals(listOf(7L), orbit.books(CatalogueQuery(series = "wa"), 0).books.map { it.id })
        assertEquals(2, orbit.books(CatalogueQuery(genre = "Novel", library = "Books"), 0).books.size)

        server.unknownFields = setOf("series")
        failing<UnsupportedOnServerException> { orbit.books(CatalogueQuery(series = "wa"), 0) }
        assertEquals("without the filter it is not refused", 2, orbit.books(CatalogueQuery(), 0).books.size)
    }

    @Test
    fun librariesAreNamedForTheCatalogueToBeNarrowedTo() = runBlocking<Unit> {
        server.libraryNames = listOf("Books", "Comics")
        assertEquals(listOf("Books", "Comics"), area.libraries())
    }
    /** Waits until the server has the request to [path], held there, as the reader goes on meanwhile. */
    private suspend fun arrived(path: String, count: Int = 1) {
        repeat(600) {
            if (server.asked(path).size >= count) return
            delay(50)
        }
        error("$path was not asked; the server was asked ${server.seen.map { "${it.method} ${it.path}" }}")
    }

    @Test
    fun statusChangedInTheLibraryWhileTheServerSetsOneIsTheLaterAndGoes() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))
        val book = downloaded("status-held.fb2")
        server.hold("/books/7/status")
        val asked = async(Dispatchers.IO) { area.setStatus(7, ReadStatuses.WANT_TO_READ) }
        arrived("/books/7/status")
        later()
        library.setStatus(book.id, ReadingStatus.Read)
        server.release("/books/7/status")
        assertEquals(ReadStatuses.WANT_TO_READ, asked.await())

        assertEquals("the reader's later status stays", ReadingStatus.Read, fresh(book).status)
        assertTrue("and is still to be sent", fresh(book).statusUnsent)
        orbit.sync()
        assertEquals("read", server.statuses[7])
    }

    @Test
    fun ratingGivenInTheLibraryWhileTheServerSetsOneIsTheLaterAndGoes() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))
        val book = downloaded("rating-held.fb2")
        server.hold("/books/bulk-set-rating")
        val asked = async(Dispatchers.IO) { area.rate(7, 5) }
        arrived("/books/bulk-set-rating")
        later()
        library.setRating(book.id, 2)
        server.release("/books/bulk-set-rating")
        assertEquals(5, asked.await())

        assertEquals(2, fresh(book).rating)
        assertTrue(fresh(book).stateUnsent)
        orbit.sync()
        assertEquals(2, server.ratings[7])
    }

    @Test
    fun noteWrittenInTheLibraryWhileTheServerTakesOneIsTheLaterAndGoes() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))
        val book = downloaded("note-held.fb2")
        server.hold("/books/7/personal-note")
        val asked = async(Dispatchers.IO) { area.writeNote(7, "From the server's page.") }
        arrived("/books/7/personal-note")
        later()
        library.setReview(book.id, "Later, in the library.")
        server.release("/books/7/personal-note")
        assertEquals("From the server's page.", asked.await())

        assertEquals("Later, in the library.", fresh(book).review)
        assertTrue(fresh(book).reviewUnsent)
        orbit.sync()
        assertEquals("Later, in the library.", server.reviews[7])
    }

    @Test
    fun statusFromTheServersPageLeavesStarsGivenMeanwhileToGoAndTheOtherWayRound() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))
        val book = downloaded("both-held.fb2")
        server.hold("/books/7/status")
        val status = async(Dispatchers.IO) { area.setStatus(7, ReadStatuses.WANT_TO_READ) }
        arrived("/books/7/status")
        later()
        library.setRating(book.id, 3)
        server.release("/books/7/status")
        status.await()
        assertEquals("the status the server confirmed is taken", ReadingStatus.ToRead, fresh(book).status)
        assertFalse(fresh(book).statusUnsent)
        assertEquals(3, fresh(book).rating)
        orbit.sync()
        assertEquals("the stars given meanwhile go", 3, server.ratings[7])
        assertEquals("and do not carry an old status along", "want_to_read", server.statuses[7])

        server.hold("/books/bulk-set-rating")
        val rating = async(Dispatchers.IO) { area.rate(7, 5) }
        arrived("/books/bulk-set-rating")
        later()
        library.setStatus(book.id, ReadingStatus.Read)
        server.release("/books/bulk-set-rating")
        rating.await()
        assertEquals(5, fresh(book).rating)
        assertTrue("the status set meanwhile still goes", fresh(book).statusUnsent)
        orbit.sync()
        assertEquals("read", server.statuses[7])
        assertEquals(5, server.ratings[7])
    }

    @Test
    fun bookThatCameToTheLibraryWhileTheServerWasAskedTakesWhatItConfirmed() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))
        server.hold("/books/7/status")
        val asked = async(Dispatchers.IO) { area.setStatus(7, ReadStatuses.WANT_TO_READ) }
        arrived("/books/7/status")
        // Downloaded meanwhile, with nothing of its own to send.
        val came = downloaded("came-meanwhile.fb2")
        server.release("/books/7/status")
        asked.await()
        assertEquals(ReadingStatus.ToRead, fresh(came).status)
        assertFalse(fresh(came).stateUnsent)

        // One that came with a status of its own waiting keeps it.
        server.hold("/books/7/status")
        val again = async(Dispatchers.IO) { area.setStatus(7, ReadStatuses.READING) }
        arrived("/books/7/status", count = 2)
        val own = downloaded("came-with-a-status.fb2")
        later()
        library.setStatus(own.id, ReadingStatus.Read)
        server.release("/books/7/status")
        again.await()
        assertEquals(ReadingStatus.Read, fresh(own).status)
        assertTrue(fresh(own).statusUnsent)
    }

    @Test
    fun changeAskedForOneReaderAsksNothingMoreUnderTheNext() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))
        server.hold("/books/bulk-set-rating")
        val rating = async(Dispatchers.IO) { runCatching { area.rate(7, 4) } }
        arrived("/books/bulk-set-rating")
        orbit.signIn(server.url, "another-reader", "right-password")
        server.release("/books/bulk-set-rating")
        assertTrue(rating.await().exceptionOrNull() is AccountChangedException)
        assertTrue("the rating is not read back under the next reader", server.asked("/books/7").isEmpty())

        server.hold("/collections")
        val made = async(Dispatchers.IO) { runCatching { area.createCollection("Holiday", withBook = 7) } }
        arrived("/collections")
        orbit.signIn(server.url, "reader", "right-password")
        server.release("/collections")
        assertTrue(made.await().exceptionOrNull() is AccountChangedException)
        assertTrue("the book is put into no collection under the next reader", server.seen.none { it.path.matches(Regex("/api/v1/collections/\\d+/books")) })
    }

    @Test
    fun collectionMadeAndTheBookNotPutInItSaysSoWithTheCollection() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))
        server.membershipAnswer = Answer(500, """{"statusCode":500,"message":"Internal server error"}""")
        val failed = assertThrows(NotAddedException::class.java) { runBlocking { area.createCollection("Holiday", withBook = 7) } }
        assertEquals("Holiday", failed.collection.name)
        assertEquals(listOf(failed.collection.id), server.collections.map { it.id })
    }

    /** Waits until [done] holds, as for an account replaced by a sign-in on its way. */
    private suspend fun waitUntil(what: String, done: suspend () -> Boolean) {
        repeat(600) {
            if (done()) return
            delay(50)
        }
        error("$what; the server was asked ${server.seen.map { "${it.method} ${it.path}" }}")
    }

    /** Signs another reader in while a renewal of the one before is held, as far as the account being replaced. */
    private suspend fun CoroutineScope.anotherReaderDuring(): Deferred<Result<DeviceChannel>> {
        val next = async(Dispatchers.IO) { runCatching { orbit.signIn(server.url, "another-reader", "right-password") } }
        waitUntil("the account is replaced") { accounts.current()?.user == "another-reader" }
        return next
    }

    @Test
    fun tokensRenewedForAReaderReplacedMeanwhileAreNotKeptForTheNext() = runBlocking<Unit> {
        // The first reader's token is refused, so the next request renews it; the renewal is held.
        server.access.clear()
        server.hold("/auth/refresh")
        val first = async(Dispatchers.IO) { runCatching { area.collections() } }
        arrived("/auth/refresh")
        val next = anotherReaderDuring()
        val theirs = accounts.tokens()

        server.release("/auth/refresh")
        assertTrue(first.await().exceptionOrNull() is AccountChangedException)
        assertTrue(next.await().isSuccess)
        assertEquals("the next reader's tokens are theirs", theirs, accounts.tokens())
        assertEquals("nothing was asked again for the reader before", 1, server.asked("/collections").size)
    }

    @Test
    fun renewalRefusedForAReaderReplacedMeanwhileDoesNotEndTheNextOnesSession() = runBlocking<Unit> {
        server.access.clear()
        server.hold("/auth/refresh")
        val first = async(Dispatchers.IO) { runCatching { area.collections() } }
        arrived("/auth/refresh")
        val next = anotherReaderDuring()
        val theirs = accounts.tokens()
        // The server refuses the renewal of the reader before.
        server.refresh.clear()

        server.release("/auth/refresh")
        assertTrue("not taken for the next reader's session ending", first.await().exceptionOrNull() is AccountChangedException)
        assertTrue(next.await().isSuccess)
        assertEquals(theirs, accounts.tokens())
        assertTrue(accounts.tokens()?.refresh.orEmpty().isNotEmpty())
    }

    @Test
    fun changeOfAReaderReplacedWhileItWaitedForATokenIsNotSentWithTheNextOnes() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))
        val before = checkNotNull(orbit.signedInAs())
        server.access.clear()
        server.hold("/auth/refresh")
        val first = async(Dispatchers.IO) { runCatching { area.collections() } }
        arrived("/auth/refresh")
        // A change of the same reader, waiting for the renewal under way to be over.
        val body = JSONObject().put("bookIds", JSONArray().put(7)).put("rating", 4)
        val change = async(Dispatchers.IO) { runCatching { orbit.session("POST", "/books/bulk-set-rating", body, owner = before) } }
        val next = anotherReaderDuring()

        server.release("/auth/refresh")
        assertTrue(first.await().exceptionOrNull() is AccountChangedException)
        assertTrue(change.await().exceptionOrNull() is AccountChangedException)
        assertTrue(next.await().isSuccess)
        assertTrue("the change was sent under no reader", server.asked("/books/bulk-set-rating").isEmpty())
        assertNull(server.ratings[7])
    }

    @Test
    fun channelRefusingAReaderReplacedMeanwhileLeavesTheNextOnesKey() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))
        server.ratingThroughSession = false
        server.hold("/koreader/plugin/catalog/books/7/rating")
        val rating = async(Dispatchers.IO) { runCatching { area.rate(7, 3) } }
        arrived("/koreader/plugin/catalog/books/7/rating")
        orbit.signIn(server.url, "another-reader", "right-password")
        accounts.saveDevice("their-device", "their-key")
        // The channel's password was changed elsewhere: the held request of the reader before is refused.
        server.deviceKey = FakeOrbit.md5("changed elsewhere")

        server.release("/koreader/plugin/catalog/books/7/rating")
        assertTrue(rating.await().exceptionOrNull() is AccountChangedException)
        assertEquals("their-device", accounts.current()?.deviceUser)
        assertEquals("the next reader's key is not forgotten for the one before", "their-key", accounts.deviceKey())
    }

    @Test
    fun starsConfirmedForABookDownloadedMeanwhileStayWhenOnlyItsStatusChangedThere() = runBlocking<Unit> {
        serverHas(book(7, "Seven"))
        server.hold("/books/bulk-set-rating")
        val rating = async(Dispatchers.IO) { area.rate(7, 5) }
        arrived("/books/bulk-set-rating")
        val came = downloaded("stars-meanwhile.fb2")
        later()
        library.setStatus(came.id, ReadingStatus.Read)
        server.release("/books/bulk-set-rating")
        assertEquals(5, rating.await())

        assertEquals("the copy takes the stars the server confirmed", 5, fresh(came).rating)
        assertTrue("its own status still goes", fresh(came).statusUnsent)
        orbit.sync()
        assertEquals("the stars stay on the server", 5, server.ratings[7])
        assertEquals("read", server.statuses[7])
    }
}
