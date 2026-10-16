package org.evsyukov.perigee.opds

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The catalog wants a user name and password, or does not take the ones given. */
class UnauthorizedException : IOException("the catalog refused the user name and password")

/** The catalog answered with something other than what was asked for. */
class CatalogResponseException(val code: Int, url: String) : IOException("HTTP $code for $url")

/**
 * Talks to one OPDS catalog over HTTP.
 *
 * The user name and password are sent to the catalog's own server only:
 * catalogs link to covers and files on other servers, which must not see them.
 *
 * @param root the address of the catalog's first page
 * @throws IllegalArgumentException when [root] is not an http or https address
 */
class OpdsClient(val root: String, private val user: String = "", private val password: String = "") {
    private val home = originOf(URL(requireNotNull(OpdsParser.resolve(root, root)) { "not a web address: $root" }))

    /**
     * Fetches a page of the catalog.
     * @throws UnauthorizedException when the catalog asks for other credentials
     * @throws NotACatalogException when the address answers with something else
     * @throws IOException when the server cannot be reached or refuses
     */
    suspend fun feed(url: String = root): OpdsFeed = withContext(Dispatchers.IO) {
        get(url, ACCEPT_FEED) { connection -> connection.inputStream.use { OpdsParser.parse(it, connection.url.toString()) } }
    }

    /** Looks [query] up through the search a page offers; null when the page offers none. */
    suspend fun search(page: OpdsFeed, pageUrl: String, query: String): OpdsFeed? {
        val (template, base) = page.searchTemplate?.let { it to pageUrl } ?: run {
            val description = page.searchDescription ?: return null
            withContext(Dispatchers.IO) {
                get(description, "*/*") { connection ->
                    connection.inputStream.use(OpdsParser::searchTemplate)?.let { it to connection.url.toString() }
                }
            } ?: return null
        }
        return feed(OpdsParser.searchUrl(template, base, query) ?: return null)
    }

    /**
     * Downloads a book into [dir] and returns the file. The name is the one
     * the server gives when it ends as the format does, [title] otherwise.
     */
    suspend fun download(link: OpdsLink, title: String, dir: File): File = withContext(Dispatchers.IO) {
        get(link.url, "*/*") { connection ->
            val format = link.format
            val offered = fileName(connection.getHeaderField("Content-Disposition"))
            val name = offered?.takeIf { format.isEmpty() || it.endsWith(".$format", ignoreCase = true) }
                ?: (safeName(title).ifEmpty { "book" } + if (format.isEmpty()) "" else ".$format")
            dir.mkdirs()
            val target = File(dir, name)
            val partial = File(dir, "$name.part")
            try {
                connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
                // Only a whole file takes the name a book is known by.
                if (!partial.renameTo(target)) throw IOException("cannot keep $name")
            } finally {
                partial.delete()
            }
            target
        }
    }

    /** Fetches a picture, such as a cover; null when it is larger than [limit] bytes or cannot be had. */
    suspend fun picture(url: String, limit: Int = 4 * 1024 * 1024): ByteArray? = withContext(Dispatchers.IO) {
        try {
            get(url, "image/*") { connection ->
                connection.inputStream.use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (bytes.size() <= limit) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytes.write(buffer, 0, read)
                    }
                    bytes.toByteArray().takeIf { it.size <= limit }
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun <T> get(url: String, accept: String, read: (HttpURLConnection) -> T): T {
        var address = URL(OpdsParser.resolve(url, url) ?: throw IOException("not a web address: $url"))
        repeat(MAX_REDIRECTS) {
            val connection = address.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                // Followed here, not by the platform: it would carry the password along to another server.
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (user.isNotEmpty() && originOf(address) == home) {
                    val token = Base64.encodeToString("$user:$password".toByteArray(), Base64.NO_WRAP)
                    connection.setRequestProperty("Authorization", "Basic $token")
                }
                when (val code = connection.responseCode) {
                    in 200..299 -> return read(connection)
                    in REDIRECTS -> {
                        val location = connection.getHeaderField("Location")
                        address = URL(OpdsParser.resolve(address.toString(), location.orEmpty())
                            ?: throw CatalogResponseException(code, address.toString()))
                    }
                    HttpURLConnection.HTTP_UNAUTHORIZED -> throw UnauthorizedException()
                    else -> throw CatalogResponseException(code, address.toString())
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects from $url")
    }

    private companion object {
        const val TIMEOUT_MS = 20_000
        const val MAX_REDIRECTS = 6
        const val USER_AGENT = "Perigee"
        const val ACCEPT_FEED = "application/atom+xml;profile=opds-catalog, application/opds+json, application/atom+xml;q=0.9, application/json;q=0.8, application/xml;q=0.8, */*;q=0.5"
        val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /** Scheme, host and port: what a password belongs to. */
        fun originOf(url: URL): String {
            val port = if (url.port == -1) url.defaultPort else url.port
            return "${url.protocol}://${url.host.lowercase()}:$port"
        }

        /** The file name of a Content-Disposition header, without any folders it may name. */
        fun fileName(disposition: String?): String? {
            if (disposition == null) return null
            val encoded = Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)").find(disposition)?.groupValues?.get(1)
            val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?").find(disposition)?.groupValues?.get(1)
            val name = encoded?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: plain ?: return null
            return safeName(name).ifEmpty { null }
        }

        /** A name that stays inside the folder it is put in, whatever the server or a title says. */
        fun safeName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.').take(120).trim()
    }
}
