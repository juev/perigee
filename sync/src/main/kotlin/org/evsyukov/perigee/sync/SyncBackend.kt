package org.evsyukov.perigee.sync

import java.io.File
import java.io.IOException

/** A book as a server lists it. */
data class RemoteBook(
    val id: Long,
    val title: String,
    val authors: List<String>,
    val series: String,
    val seriesNumber: String,
    val files: List<RemoteFile>,
    val hasCover: Boolean,
)

/** One file of a book on a server. */
data class RemoteFile(
    val bookId: Long,
    val id: Long,
    /** Lower-case, as "epub"; empty when the server does not say. */
    val format: String,
    val size: Long,
)

/** A part of a server's list of books. */
data class RemotePage(val books: List<RemoteBook>, val total: Int, val page: Int) {
    /** Whether [page] is not the last one, given how many books came before it. */
    fun hasMore(shown: Int): Boolean = books.isNotEmpty() && shown + books.size < total
}

/**
 * A server the reader's books are kept in step with. What a server has to
 * offer for the app to work with it; how the reader signs in is the server's
 * own business.
 */
interface SyncBackend {
    /**
     * A page of the server's books, by title; with [query], of those it finds by it.
     * @param page counted from zero
     */
    suspend fun books(query: String, page: Int): RemotePage

    /** The cover of a book, small; null when it has none. */
    suspend fun cover(book: RemoteBook): ByteArray?

    /** Downloads a file into [dir] under the name the server gives it, [title] when it gives none. */
    suspend fun download(file: RemoteFile, title: String, dir: File): File

    /**
     * Which of the files with these fingerprints the server has; see
     * [org.evsyukov.perigee.data.FileHash]. A fingerprint it does not know is left out.
     */
    suspend fun match(hashes: Collection<String>): Map<String, RemoteFile>
}

/** There is something at the address, but not a server the app knows. */
class NotAServerException(url: String) : IOException("no server at $url")

/** The server does not take this user name and password. */
class WrongPasswordException : IOException("the server refused the user name and password")

/** The server asks to wait before the next try; [seconds] is zero when it does not say how long. */
class TooManyAttemptsException(val seconds: Int) : IOException("the server asks to wait")

/** The server lets nobody in by password. */
class PasswordLoginDisabledException : IOException("signing in by password is switched off on the server")

/** The session is over and cannot be renewed: the reader has to sign in again. */
class SignedOutException : IOException("the session with the server is over")

/** The reader is not allowed what was asked for; [message] is the server's own word on it. */
class NotAllowedException(message: String) : IOException(message)

/** The server is of a version the app was not made for, so nothing is written to it. */
class IncompatibleServerException(val version: String) : IOException("the server's version $version is not supported")

/** The channel reading devices sync through is not set up, or its key is not taken any more. */
class DeviceChannelException(val state: DeviceChannel) : IOException("the devices' channel is not ready: $state")

/** The server answered with something other than what was asked for. */
class ServerResponseException(val code: Int, message: String) : IOException("HTTP $code: $message")

/** How things stand with the channel reading devices sync through. */
enum class DeviceChannel {
    Ready,

    /** The reader has a user name there already; the app needs its password. */
    PasswordNeeded,

    /** The reader's account on the server is not allowed to sync devices. */
    NotAllowed,

    /** Syncing is switched off for the reader's devices on the server. */
    SwitchedOff,

    /** The server is of a version the app was not made for; the channel is left alone. */
    Unsupported,
}
