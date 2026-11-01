package org.evsyukov.perigee.sync

import java.io.IOException
import java.net.URLEncoder
import org.evsyukov.perigee.data.Accounts
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.sync.BookOrbit.Companion.each
import org.evsyukov.perigee.sync.BookOrbit.Companion.stringOrNull
import org.evsyukov.perigee.sync.BookOrbit.Companion.textOf
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What the reader does on the server beyond looking through its list of books: the shelves of its home
 * page, their collections, the books the server lists beside another, and their own status, stars and
 * note of a book. All of it goes through the reader's session, so none of it waits for the devices'
 * channel; a version of the server without one of these says so by [UnsupportedOnServerException] or
 * [Shelf.Unsupported], and the rest goes on working.
 *
 * Nothing is kept here: every answer is the server's as it gave it, and a change is only taken for made
 * once the server confirmed it.
 */
class OrbitArea internal constructor(private val orbit: BookOrbit, private val accounts: Accounts, private val library: Library) {

    /**
     * The shelves [kinds], at most [limit] books on each, asked for together. Each shelf stands on its own:
     * one the server could not fill is [Shelf.Failed] beside others that are full. An older server, which
     * answers shelves one at a time or has some not at all, is asked shelf by shelf.
     * @throws IOException when the server cannot be reached at all
     */
    suspend fun shelves(kinds: List<ShelfKind> = ShelfKind.DEFAULT, limit: Int = SHELF_PREVIEW): Map<ShelfKind, Shelf> {
        val who = orbit.signedInAs()
        val items = JSONArray()
        for (kind in kinds) items.put(JSONObject().put("id", kind.type).put("type", kind.type).put("limit", limit))
        val reply = orbit.session("POST", "/dashboard/scrollers/batch", JSONObject().put("items", items), owner = who)
        return when {
            // The answer of the batch is a list of its own, by the ids asked with, without totals.
            reply.ok -> {
                val answered = HashMap<String, Shelf>()
                reply.json()?.optJSONArray("items").each { item ->
                    answered[item.textOf("id")] = if (item.optBoolean("failed")) Shelf.Failed else shelfOf(item.optJSONArray("books"))
                }
                kinds.associateWith { answered[it.type] ?: Shelf.Failed }
            }
            // No batch on this server, or one that refuses a list naming a shelf it does not have.
            reply.code == 404 || reply.code == 400 -> kinds.associateWith { shelf(it, limit, who) }
            reply.code >= 500 -> kinds.associateWith { Shelf.Failed }
            else -> throw orbit.refusal(reply)
        }
    }

    /**
     * One shelf, as when it is tried again. The server tells how many books the shelf stands for, not always
     * and not always of the whole library; that number is not taken, so nothing is counted that is not shown.
     */
    suspend fun shelf(kind: ShelfKind, limit: Int = SHELF_PREVIEW): Shelf = shelf(kind, limit, orbit.signedInAs())

    private suspend fun shelf(kind: ShelfKind, limit: Int, who: String?): Shelf {
        val reply = orbit.session("GET", "/dashboard/scrollers/${kind.type}?limit=$limit", owner = who)
        return when {
            reply.ok -> shelfOf(reply.json()?.optJSONArray("books"))
            // A shelf this version has no word for is refused as a value it cannot parse.
            reply.code == 404 || reply.code == 400 -> Shelf.Unsupported
            reply.code >= 500 -> Shelf.Failed
            else -> throw orbit.refusal(reply)
        }
    }

    private fun shelfOf(books: JSONArray?): Shelf.Books {
        val all = (0 until (books?.length() ?: 0)).mapNotNull { orbit.bookOf(books?.optJSONObject(it)) }
        val readable = all.filter { book -> book.files.any { it.format in Library.FORMATS } }
        return Shelf.Books(readable, leftOut = all.size - readable.size)
    }

    /** The reader's own collections and those other readers share, as the server orders them. */
    suspend fun collections(): List<RemoteCollection> {
        val reply = orbit.session("GET", "/collections")
        if (!reply.ok) throw failed(reply, "collections")
        return listIn(reply).mapNotNull(::collectionOf)
    }

    /**
     * Which of the reader's own collections [bookId] is in. The server says it by the list of collections
     * asked for with the book; one that does not is asked about the book itself, which names its collections.
     */
    suspend fun collectionsOf(bookId: Long): Set<Long> {
        val who = orbit.signedInAs()
        val reply = orbit.session("GET", "/collections?bookIds=$bookId", owner = who)
        if (!reply.ok) throw failed(reply, "collections")
        val listed = listIn(reply)
        if (listed.all { it.has("memberCount") }) {
            return listed.filter { it.optInt("memberCount") > 0 }.map { it.optLong("id") }.toSet()
        }
        val book = orbit.session("GET", "/books/$bookId", owner = who)
        if (!book.ok) throw failed(book, "books")
        val named = book.json()?.optJSONArray("collections") ?: throw UnsupportedOnServerException("the collections of a book")
        val own = listed.filter { it.optBoolean("isOwner") }.map { it.optLong("id") }.toSet()
        return buildSet { named.each { if (it.optLong("id") in own) add(it.optLong("id")) } }
    }

    /**
     * Makes a collection of the reader's: private, with the server's plain book icon, and with nothing set
     * of syncing it to other devices, which stays as the server has it for a new collection.
     * @throws NameTakenException when the reader has a collection of that name
     */
    suspend fun createCollection(name: String): RemoteCollection = createCollection(name, withBook = null)

    /**
     * Makes a collection as [createCollection] does and puts [withBook] into it, for the same reader: nothing
     * is put into a collection of another one signed in meanwhile.
     * @throws NotAddedException when the collection was made and the book not put into it; it carries the collection
     */
    suspend fun createCollection(name: String, withBook: Long?): RemoteCollection {
        val who = orbit.signedInAs()
        val body = JSONObject().put("name", name.trim().take(MAX_COLLECTION_NAME)).put("icon", COLLECTION_ICON).put("isPublic", false)
        val made = collectionAnswer(orbit.session("POST", "/collections", body, owner = who))
        if (withBook == null) return made
        return try {
            collectionAnswer(orbit.session("POST", "/collections/${made.id}/books", JSONObject().put("bookIds", JSONArray().put(withBook)), owner = who))
        } catch (e: AccountChangedException) {
            throw e
        } catch (e: SignedOutException) {
            throw e
        } catch (e: IOException) {
            throw NotAddedException(made, e)
        }
    }

    /** Renames a collection of the reader's; its icon, description, sharing and syncing stay as they are. */
    suspend fun renameCollection(id: Long, name: String): RemoteCollection =
        collectionAnswer(orbit.session("PATCH", "/collections/$id", JSONObject().put("name", name.trim().take(MAX_COLLECTION_NAME))))

    /** Deletes a collection: the books in it stay, on the server and in the library. One that is gone already is deleted. */
    suspend fun deleteCollection(id: Long) {
        val reply = orbit.session("DELETE", "/collections/$id")
        if (reply.ok || (reply.code == 404 && !reply.routeMissing())) return
        throw failed(reply, "collections")
    }

    /** Puts a book into a collection of the reader's, or takes it out; the book itself stays where it is. */
    suspend fun setInCollection(id: Long, bookId: Long, inIt: Boolean): RemoteCollection {
        val body = JSONObject().put("bookIds", JSONArray().put(bookId))
        return collectionAnswer(orbit.session(if (inIt) "POST" else "DELETE", "/collections/$id/books", body))
    }

    /**
     * A part of a collection's books that the app reads, by title; with [text], of those the server finds by
     * it within the collection. A server without the collection's query is asked for its plain list, and the
     * books the app does not read are left out of each part here.
     * @param page counted from zero
     */
    suspend fun collectionBooks(id: Long, text: String, page: Int): BookPart {
        val size = BookOrbit.PAGE_SIZE
        val body = JSONObject().put("pagination", JSONObject().put("page", page).put("size", size))
            .put("sort", JSONArray().put(JSONObject().put("field", CatalogueSort.Title.field).put("dir", CatalogueSort.Title.dir)))
        BookOrbit.filterOf(CatalogueQuery(), readableOnly = true)?.let { body.put("filter", it) }
        if (text.isNotBlank()) body.put("q", text.trim().take(BookOrbit.MAX_QUERY))
        val who = orbit.signedInAs()
        var reply = orbit.session("POST", "/collections/$id/books/query", body, owner = who)
        var filtered = true
        if (reply.routeMissing()) {
            val q = if (text.isBlank()) "" else "&q=" + URLEncoder.encode(text.trim().take(BookOrbit.MAX_QUERY), "UTF-8")
            reply = orbit.session("GET", "/collections/$id/books?page=$page&size=$size$q", owner = who)
            filtered = false
        }
        if (!reply.ok) throw failed(reply, "the books of a collection")
        val json = reply.json() ?: throw ServerResponseException(reply.code, "no list of books")
        val items = json.optJSONArray("items") ?: JSONArray()
        val all = (0 until items.length()).mapNotNull { orbit.bookOf(items.optJSONObject(it)) }
        val books = if (filtered) all else all.filter { book -> book.files.any { it.format in Library.FORMATS } }
        return BookPart(books, more = items.length() > 0 && (page + 1) * size < json.optInt("total"))
    }

    /**
     * The books the server lists beside [bookId] as [kind], without the book itself. They come without their
     * files, which the book's page asks for when one is opened; an audiobook or a comic is left out. An empty
     * answer is an answer: the server finds nothing like it, or has no likeness worked out for it.
     */
    suspend fun related(bookId: Long, kind: Related): RelatedBooks {
        val reply = orbit.session("GET", "/books/$bookId/${kind.path}")
        if (!reply.ok) throw failed(reply, "books ${kind.path}")
        val all = listIn(reply).filter { it.optLong("id") != bookId }
        val readable = all.filter { !it.optBoolean("isAudiobook") && !it.optBoolean("isComic") }
        return RelatedBooks(readable.map { relatedOf(it, kind) }, leftOut = all.size - readable.size)
    }

    /** One of the server's suggestions: what it says of a book is less than a line of the list does. */
    private fun relatedOf(json: JSONObject, kind: Related): RemoteBook {
        val names = json.optJSONArray("authors") ?: JSONArray()
        return RemoteBook(
            id = json.optLong("id"),
            title = json.textOf("title"),
            authors = (0 until names.length()).map { names.optString(it) }.filter { it.isNotBlank() },
            series = "",
            seriesNumber = if (kind == Related.Series) json.textOf("seriesIndex") else "",
            files = emptyList(),
            hasCover = json.optBoolean("hasCover"),
            filesKnown = false,
            status = json.optJSONObject("readStatus")?.textOf("status").orEmpty(),
        )
    }

    /** The names of the server's libraries the reader can see, to narrow the catalogue to one. */
    suspend fun libraries(): List<String> {
        val reply = orbit.session("GET", "/libraries")
        if (!reply.ok) throw failed(reply, "libraries")
        return listIn(reply).map { it.textOf("name") }.filter { it.isNotBlank() }.distinct()
    }

    /**
     * Sets the reader's status of a book, in the server's word, and answers with the one the server
     * confirmed. A book in the library takes it there too, between rounds of syncing, see [reconciled].
     */
    suspend fun setStatus(bookId: Long, status: String): String {
        val who = orbit.signedInAs()
        return reconciled(
            who, bookId,
            change = {
                val reply = orbit.session("PATCH", "/books/$bookId/status", JSONObject().put("status", status), owner = who)
                if (!reply.ok) throw failed(reply, "statuses")
                reply.json()?.textOf("status")?.ifEmpty { null } ?: throw ServerResponseException(reply.code, "no status")
            },
            take = { seen, confirmed, appeared ->
                if (!(appeared && seen.statusUnsent)) library.statusFromServer(seen, BookOrbit.STATUSES[confirmed])
            },
        )
    }

    /**
     * Gives a book [rating] stars, null taking them off, and answers with the rating the server keeps. The
     * session sets a rating for a reader who may edit the library's books; anyone else's goes through the
     * devices' channel, when it is ready. A rating the server keeps locked is not taken for set: it is read
     * back, and one that did not change is [NotSavedException].
     */
    suspend fun rate(bookId: Long, rating: Int?): Int? {
        val who = orbit.signedInAs()
        val stars = rating?.coerceIn(1, 5)
        return reconciled(
            who, bookId,
            change = {
                val reply = orbit.session(
                    "POST", "/books/bulk-set-rating", JSONObject().put("bookIds", JSONArray().put(bookId)).put("rating", stars ?: JSONObject.NULL),
                    owner = who,
                )
                when {
                    reply.ok -> Unit
                    (reply.code == 403 || reply.routeMissing()) && channelReady() -> try {
                        orbit.channel("PUT", "/koreader/plugin/catalog/books/$bookId/rating", JSONObject().put("rating", stars ?: JSONObject.NULL), owner = who)
                    } catch (e: DeviceChannelException) {
                        throw NotAllowedException("the session may not rate, and the devices' channel is ${e.state}")
                    }
                    else -> throw failed(reply, "ratings")
                }
                val confirmed = orbit.detail(bookId, owner = who).rating
                if (confirmed != stars) throw NotSavedException("the server keeps the rating at $confirmed")
                confirmed
            },
            take = { seen, confirmed, appeared ->
                // A copy that came meanwhile takes the stars unless it has stars of its own waiting; a status of
                // its own waiting stays, and still goes.
                if (!(appeared && seen.ratingUnsent)) library.ratingFromServer(seen, confirmed)
            },
        )
    }

    /**
     * Writes what the reader has to say of a book, the server's personal note; null or white space takes it
     * off. Answers with the note the server keeps.
     */
    suspend fun writeNote(bookId: Long, note: String?): String? {
        val who = orbit.signedInAs()
        val text = note?.trim()?.take(MAX_NOTE)?.ifEmpty { null }
        return reconciled(
            who, bookId,
            change = {
                val reply = orbit.session("PATCH", "/books/$bookId/personal-note", JSONObject().put("note", text ?: JSONObject.NULL), owner = who)
                if (!reply.ok) throw failed(reply, "personal notes")
                val json = reply.json()
                val confirmed = if (json?.has("personalNote") == true) json.stringOrNull("personalNote") else orbit.detail(bookId, owner = who).note
                if (confirmed != text) throw NotSavedException("the server keeps another note")
                confirmed
            },
            take = { seen, confirmed, appeared ->
                if (!(appeared && seen.reviewUnsent)) library.reviewFromServer(seen, confirmed)
            },
        )
    }

    /**
     * A change to a book that is in the library is asked for between rounds of syncing, and taken there as the
     * server confirmed it, for [who] only. Each copy is taken as it was when the change was asked for: one the
     * reader changed here meanwhile keeps the change, which is later and still goes to the server. A copy that
     * came to the library while the server was asked, as by a download, takes it unless it has a change of its
     * own waiting ([take] is told it `appeared`).
     */
    private suspend fun <T> reconciled(
        who: String?,
        bookId: Long,
        change: suspend () -> T,
        take: suspend (seen: Book, value: T, appeared: Boolean) -> Unit,
    ): T {
        if (library.copiesOf(bookId).isEmpty()) {
            val value = change()
            orbit.still(who)
            if (library.copiesOf(bookId).isNotEmpty()) {
                orbit.betweenRounds {
                    orbit.still(who)
                    for (copy in library.copiesOf(bookId)) take(copy, value, true)
                }
            }
            return value
        }
        return orbit.betweenRounds {
            val seen = library.copiesOf(bookId).associateBy { it.id }
            val value = change()
            orbit.still(who)
            for (copy in library.copiesOf(bookId)) take(seen[copy.id] ?: copy, value, copy.id !in seen)
            value
        }
    }

    private suspend fun channelReady(): Boolean {
        val account = accounts.current() ?: return false
        return BookOrbit.supports(account.serverVersion) && accounts.deviceKey() != null
    }

    private fun collectionAnswer(reply: Reply): RemoteCollection {
        if (!reply.ok) throw failed(reply, "collections")
        return reply.json()?.let(::collectionOf) ?: throw ServerResponseException(reply.code, "no collection")
    }

    private fun collectionOf(json: JSONObject): RemoteCollection? {
        val id = json.optLong("id").takeIf { it > 0 } ?: return null
        return RemoteCollection(
            id = id,
            name = json.textOf("name"),
            icon = json.textOf("icon"),
            description = json.stringOrNull("description").orEmpty(),
            isPublic = json.optBoolean("isPublic"),
            isOwner = json.optBoolean("isOwner"),
            bookCount = json.optInt("bookCount"),
        )
    }

    /** The objects of an answer that is a list without an envelope. */
    private fun listIn(reply: Reply): List<JSONObject> {
        val items = try {
            JSONArray(reply.body.trim().ifEmpty { "[]" })
        } catch (e: JSONException) {
            throw IOException("the server did not answer with a list", e)
        }
        return (0 until items.length()).mapNotNull { items.optJSONObject(it) }
    }

    /** What a refusal is to the reader: not on this server, gone, a name taken, a change not taken, or the server's word. */
    private fun failed(reply: Reply, what: String): IOException = when {
        reply.routeMissing() -> UnsupportedOnServerException(what)
        reply.code == 404 -> NotOnServerException(reply.message.ifEmpty { "not found" })
        reply.code == 409 -> NameTakenException(reply.message)
        reply.code == 400 -> NotSavedException(reply.message)
        else -> orbit.refusal(reply)
    }

    companion object {
        /** Books asked for on each shelf of the home page. */
        const val SHELF_PREVIEW = 12

        /** What a new collection is shown with on the server: its icon must not be empty. */
        const val COLLECTION_ICON = "book"
        private const val MAX_COLLECTION_NAME = 255
        private const val MAX_NOTE = 10_000
    }
}

/** A 404 of the server's router rather than of something it looked for: the route is not in this version. */
internal fun Reply.routeMissing(): Boolean = code == 404 && message.startsWith("Cannot ")
