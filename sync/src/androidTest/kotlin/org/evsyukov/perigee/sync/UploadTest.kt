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
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.FileHash
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.data.LocalBook
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sending the reader's books to the server, and pointing out the books it has
 * under the same title while their files are other ones. Against a stand-in server.
 */
@RunWith(AndroidJUnit4::class)
class UploadTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val library = Library.inMemory(context)
    private val accounts = Accounts.of(library)
    private val orbit = BookOrbit(accounts, library, "0.0.1")
    private val server = FakeOrbit()
    private val dir = File(context.cacheDir, "upload-test").apply {
        deleteRecursively()
        mkdirs()
    }
    private val place = UploadPlace(1, 10)

    @After
    fun stop() = server.close()

    private fun signIn() = runBlocking { orbit.signIn(server.url, "reader", "right-password") }

    private inline fun <reified T : Throwable> failing(crossinline block: suspend () -> Unit): T =
        assertThrows(T::class.java) { runBlocking { block() } }

    /** A book of the library with a file of its own, and that file. */
    private suspend fun book(
        title: String, vararg authors: String, format: String = "epub", text: String = "the text of $title",
        series: String = "", number: String = "",
    ): Pair<Book, File> {
        val file = File(dir, "${dir.list()!!.size}.$format").apply { writeText(text) }
        val kept = library.keep(file, LocalBook(FileHash.partialMd5(file), format, title, authors.toList(), "", series, number, false))
        return kept to file
    }

    private fun sentFiles() = server.seen.filter { it.path.startsWith("/api/v1/libraries/") }

    private fun serverBook(id: Long, title: String, vararg authors: String, series: String? = null, number: String? = null) =
        """{"id":$id,"title":${quote(title)},"authors":${JSONArray(authors.toList())},"seriesName":${series?.let(::quote)},
            "seriesIndex":${number?.let(::quote)},"hasCover":false,"files":[{"id":${id * 10},"format":"epub","role":"primary","sizeBytes":100}]}"""

    private fun quote(text: String) = org.json.JSONObject.quote(text)

    private fun serverHas(vararg books: String) {
        server.books = JSONArray(books.joinToString(",", "[", "]"))
    }

    @Test
    fun serverSaysWhatItTakesAndWhereTo() = runBlocking<Unit> {
        signIn()
        server.maxUploadBytes = 1234
        server.libraries = JSONArray(
            """[{"id":1,"name":"Books","allowedFormats":[],"organizationMode":"book_per_file","folders":[{"id":10,"name":"books"}]},
                {"id":2,"name":"Comics","allowedFormats":["CBZ"," pdf "],"organizationMode":"book_per_folder",
                 "folders":[{"id":20,"name":"new"},{"id":21,"name":"old"}]}]""",
        )

        val options = orbit.uploadOptions()

        assertTrue(options.allowed)
        assertEquals(1234L, options.maxBytes)
        assertTrue(options.formats.containsAll(listOf("epub", "fb2", "mobi")))
        assertEquals(listOf("Books", "Comics"), options.libraries.map { it.name })
        assertEquals(listOf(UploadFolder(20, "new"), UploadFolder(21, "old")), options.libraries[1].folders)
        assertEquals("formats are told apart whatever their case", setOf("cbz", "pdf"), options.libraries[1].formats)
        assertEquals(listOf(1L), options.librariesFor("epub").map { it.id })
        assertEquals(listOf(1L, 2L), options.librariesFor("pdf").map { it.id })
        assertEquals("a format the server does not take goes nowhere", emptyList<UploadLibrary>(), options.librariesFor("txt"))
    }

    @Test
    fun readerWhoMayNotSendIsOfferedNoLibrary() = runBlocking<Unit> {
        signIn()
        server.uploadAllowed = false

        val options = orbit.uploadOptions()

        assertFalse(options.allowed)
        assertEquals(emptyList<UploadLibrary>(), options.librariesFor("epub"))
        val (book, file) = book("Not Allowed")
        failing<NotAllowedException> { orbit.upload(book, file, place) }
        assertTrue("nothing was sent", sentFiles().isEmpty())
    }

    @Test
    fun bookIsSentWholeUnderItsTitleAndIsOnTheServerFromThenOn() = runBlocking<Unit> {
        signIn()
        // Longer than one sample of the fingerprint, and not text all through.
        val (book, file) = book("Война и мир: «том» 1/2", "Лев Толстой", format = "fb2")
        file.writeBytes(ByteArray(70_000) { (it * 31).toByte() })
        val kept = library.keep(file, LocalBook(FileHash.partialMd5(file), "fb2", book.title, book.authors, "", "", "", false))
        val steps = ArrayList<Long>()

        assertEquals(Uploaded.Sent, orbit.upload(kept, file, place) { steps += it })

        val sent = server.uploads.single()
        assertEquals("a name the server can file the book by, without what a file name cannot hold", "Война и мир «том» 1 2.fb2", sent.name)
        assertEquals(1L, sent.libraryId)
        assertEquals(10L, sent.folderId)
        assertArrayEquals(file.readBytes(), sent.bytes)
        assertEquals("the last step is the whole file", 70_000L, steps.last())
        assertEquals(steps.sorted(), steps)
        val now = checkNotNull(library.book(kept.id))
        assertEquals(sent.bookId, now.serverBookId)
        assertEquals("the file is learnt by asking for it by its fingerprint", sent.bookId * 10, now.serverFileId)
        assertTrue("it is synced like any book the server has", now.id in library.booksOnServer().map { it.id })
    }

    @Test
    fun bookTheServerHasIsNotSentAgain() = runBlocking<Unit> {
        signIn()
        val (book, file) = book("Once Is Enough")
        assertEquals(Uploaded.Sent, orbit.upload(book, file, place))

        assertEquals(Uploaded.WasThere, orbit.upload(book, file, place))

        assertEquals(1, sentFiles().size)
    }

    @Test
    fun bookThatGotToTheServerMeanwhileIsFoundThereAndNotSent() = runBlocking<Unit> {
        signIn()
        val (book, file) = book("Came Another Way")
        orbit.matchLibrary(all = true)
        assertNull(library.book(book.id)?.serverFileId)
        server.files[book.id] = 5L to 50L

        assertEquals(Uploaded.WasThere, orbit.upload(book, file, place))

        assertTrue(sentFiles().isEmpty())
        assertEquals(50L, library.book(book.id)?.serverFileId)
    }

    @Test
    fun sameBookAskedForTwiceAtOnceIsSentOnce() = runBlocking<Unit> {
        signIn()
        val (book, file) = book("Tapped Twice")

        val results = coroutineScope { List(2) { async { orbit.upload(book, file, place) } }.awaitAll() }

        assertEquals(setOf(Uploaded.Sent, Uploaded.WasThere), results.toSet())
        assertEquals(1, server.uploads.size)
    }

    @Test
    fun bookSentWhoseFileCouldNotBeLearntIsAskedAboutInTheNextRound() = runBlocking<Unit> {
        signIn()
        val (book, file) = book("Answer Lost")
        orbit.matchLibrary(all = true)
        // The file is taken; what is asked through the devices' channel after it gets no answer.
        server.afterUpload = { server.down = true }

        assertEquals("sent is sent", Uploaded.Sent, orbit.upload(book, file, place))
        assertNull(library.book(book.id)?.serverFileId)

        server.down = false
        orbit.matchLibrary(all = false)
        assertEquals(server.uploads.single().bookId * 10, library.book(book.id)?.serverFileId)
    }

    @Test
    fun libraryWithoutAFolderChosenIsSentToAsItIs() = runBlocking<Unit> {
        signIn()
        val (book, file) = book("Any Folder")

        orbit.upload(book, file, UploadPlace(1))

        assertNull(server.uploads.single().folderId)
        assertFalse("?" in sentFiles().single().path)
    }

    @Test
    fun whatCannotBeTakenIsNotSentAtAll() = runBlocking<Unit> {
        signIn()
        server.maxUploadBytes = 10
        val (large, largeFile) = book("Too Large")
        assertEquals(UploadRefusal.TooLarge, failing<UploadRefusedException> { orbit.upload(large, largeFile, place) }.why)
        server.maxUploadBytes = 1_000_000

        val (text, textFile) = book("Plain Text", format = "txt")
        assertEquals(UploadRefusal.Format, failing<UploadRefusedException> { orbit.upload(text, textFile, place) }.why)

        val (book, file) = book("Nowhere To Go")
        assertEquals(UploadRefusal.NoPlace, failing<UploadRefusedException> { orbit.upload(book, file, UploadPlace(9)) }.why)
        assertEquals(UploadRefusal.NoPlace, failing<UploadRefusedException> { orbit.upload(book, file, UploadPlace(1, 99)) }.why)

        server.libraries = JSONArray("""[{"id":1,"name":"Comics","allowedFormats":["cbz"],"organizationMode":"book_per_file","folders":[]}]""")
        assertEquals(UploadRefusal.Format, failing<UploadRefusedException> { orbit.upload(book, file, UploadPlace(1)) }.why)

        assertTrue("none of them went: ${sentFiles()}", sentFiles().isEmpty())
        assertNull(library.book(book.id)?.serverFileId)
    }

    @Test
    fun serversRefusalsAreToldApart() = runBlocking<Unit> {
        signIn()
        val (book, file) = book("Refused")
        fun refused(code: Int, errorCode: String?, message: String = "said the server"): UploadRefusedException {
            val body = org.json.JSONObject().put("statusCode", code).put("message", message)
            if (errorCode != null) body.put("errorCode", errorCode)
            server.uploadAnswer = Answer(code, body.toString())
            return failing { orbit.upload(book, file, place) }
        }

        assertEquals(UploadRefusal.NameTaken, refused(409, "UPLOAD_DESTINATION_CONFLICT").why)
        assertEquals("said the server", refused(409, "UPLOAD_DESTINATION_CONFLICT").message)
        assertEquals(UploadRefusal.TooLarge, refused(413, "UPLOAD_TOO_LARGE").why)
        assertEquals("a file cut short by the server's own limit", UploadRefusal.TooLarge, refused(413, null).why)
        assertEquals(UploadRefusal.Format, refused(400, "UPLOAD_FORMAT_NOT_ALLOWED").why)
        assertEquals(UploadRefusal.Format, refused(400, "UPLOAD_FORMAT_UNSUPPORTED").why)
        assertEquals(UploadRefusal.NotABook, refused(400, "UPLOAD_CONTENT_INVALID").why)
        assertEquals(UploadRefusal.NotABook, refused(400, "UPLOAD_EMPTY").why)
        assertEquals(UploadRefusal.NoRoom, refused(507, "UPLOAD_STORAGE_FULL").why)
        assertEquals(UploadRefusal.NoPlace, refused(404, null, "Library not found").why)
        assertEquals(UploadRefusal.NoPlace, refused(400, null, "Folder does not belong to this library").why)

        server.uploadAnswer = Answer(403, """{"statusCode":403,"message":"Missing permission: library_upload"}""")
        failing<NotAllowedException> { orbit.upload(book, file, place) }
        server.uploadAnswer = Answer(500, """{"statusCode":500,"message":"Internal server error"}""")
        assertEquals(500, failing<ServerResponseException> { orbit.upload(book, file, place) }.code)
        assertNull("a book that was refused is not on the server", library.book(book.id)?.serverFileId)
    }

    @Test
    fun sameNameOnTheServerIsARefusalNotASecondBook() = runBlocking<Unit> {
        signIn()
        val (first, firstFile) = book("One Title", text = "one edition")
        val (second, secondFile) = book("One Title", text = "another edition")
        orbit.upload(first, firstFile, place)

        val refused = failing<UploadRefusedException> { orbit.upload(second, secondFile, place) }

        assertEquals(UploadRefusal.NameTaken, refused.why)
        assertEquals(1, server.uploads.size)
        assertNull(library.book(second.id)?.serverFileId)
    }

    @Test
    fun sessionThatRanOutIsRenewedAndTheFileSentOnce() = runBlocking<Unit> {
        signIn()
        val (book, file) = book("After A While")
        server.access.clear()

        assertEquals(Uploaded.Sent, orbit.upload(book, file, place))

        assertEquals(1, server.uploads.size)
        assertEquals(1, server.asked("/auth/refresh").size)
    }

    @Test
    fun withoutTheDevicesChannelNothingIsSent() = runBlocking<Unit> {
        signIn()
        val (book, file) = book("Cannot Be Asked About")
        server.deviceKey = "changed-elsewhere"

        // Whether the server has the file cannot be asked, so it might be sent a second time.
        failing<DeviceChannelException> { orbit.upload(book, file, place) }

        assertTrue(sentFiles().isEmpty())
    }

    @Test
    fun bookTheServerHasUnderTheSameTitleIsPointedOut() = runBlocking<Unit> {
        signIn()
        val (alike, _) = book("Война и мир", "Толстой Лев")
        val (alone, _) = book("Nowhere Else", "An Author")
        val (other, _) = book("Same Title", "One Author")
        val (there, thereFile) = book("On The Server", "An Author")
        server.files[FileHash.partialMd5(thereFile)] = 4L to 40L
        serverHas(
            serverBook(7, "ВОЙНА И  МИР.", "Лев Толстой"), serverBook(8, "Same Title", "Another Author"),
            serverBook(4, "On The Server", "An Author"),
        )

        orbit.matchLibrary(all = true)

        val marked = checkNotNull(library.book(alike.id))
        assertEquals(7L, marked.serverLikeId)
        assertEquals("ВОЙНА И  МИР. — Лев Толстой", marked.serverLikeName)
        assertNull("a likeness is not the file", marked.serverFileId)
        assertNull(library.book(alone.id)?.serverLikeId)
        assertNull("the same title by another author is another book", library.book(other.id)?.serverLikeId)
        assertNull("its own file is there, which is more than a likeness", library.book(there.id)?.serverLikeId)
        assertEquals(40L, library.book(there.id)?.serverFileId)
    }

    @Test
    fun likenessGoesWhenTheServersBookDoes() = runBlocking<Unit> {
        signIn()
        val (book, _) = book("Here Today", "An Author")
        serverHas(serverBook(7, "Here Today", "An Author"))
        orbit.matchLibrary(all = true)
        assertEquals(7L, library.book(book.id)?.serverLikeId)

        serverHas()
        orbit.matchLibrary(all = true)

        assertNull(library.book(book.id)?.serverLikeId)
    }

    @Test
    fun likenessIsLookedForThroughTheWholeListOfTheServer() = runBlocking<Unit> {
        signIn()
        val (book, _) = book("The Last One", "An Author")
        server.paged = true
        serverHas(*(1L..450L).map { serverBook(it, if (it == 450L) "The Last One" else "Book $it", "An Author") }.toTypedArray())

        assertEquals(1, orbit.findLikes())

        assertEquals("two hundred to a page, and no page past the last", 3, server.asked("/books/query").size)
        assertEquals(450L, library.book(book.id)?.serverLikeId)
    }

    @Test
    fun sendingABookThatLooksLikeOneOnTheServerLeavesNoLikenessBehind() = runBlocking<Unit> {
        signIn()
        val (book, file) = book("Another Edition", "An Author")
        serverHas(serverBook(7, "Another Edition", "An Author"))
        orbit.matchLibrary(all = true)
        assertEquals(7L, library.book(book.id)?.serverLikeId)

        assertEquals(Uploaded.Sent, orbit.upload(book, file, place))

        val now = checkNotNull(library.book(book.id))
        assertNull(now.serverLikeId)
        assertEquals(server.uploads.single().bookId, now.serverBookId)
    }
}
