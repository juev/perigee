package org.evsyukov.perigee.opds

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

/** What was fetched is not a catalog page. */
class NotACatalogException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Reads OPDS 1.x catalog pages (Atom) and OpenSearch descriptions. */
object OpdsParser {
    private const val ACQUISITION = "http://opds-spec.org/acquisition"
    private const val OPEN_ACCESS = "http://opds-spec.org/acquisition/open-access"
    private const val IMAGE = "http://opds-spec.org/image"
    private const val THUMBNAIL = "http://opds-spec.org/image/thumbnail"
    private const val OPEN_SEARCH = "application/opensearchdescription+xml"
    private const val ATOM = "application/atom+xml"
    private const val TERMS = "{searchTerms}"

    // Links of an entry that are about it but do not lead into it.
    private val ASIDE = setOf("self", "related", "search", "start", "up", "replies")

    /**
     * @param base the address the page was fetched from; relative links are resolved against it
     * @throws NotACatalogException when the input is not an Atom feed
     */
    fun parse(input: InputStream, base: String): OpdsFeed {
        try {
            val xml = parser(input)
            xml.nextTag()
            return when (xml.name) {
                "feed" -> feed(xml, base)
                // Some catalogs answer a book's address with the bare entry.
                "entry" -> OpdsFeed("", listOfNotNull(entry(xml, base)))
                else -> throw NotACatalogException("not a catalog page: <${xml.name}>")
            }
        } catch (e: XmlPullParserException) {
            throw NotACatalogException("not a catalog page", e)
        }
    }

    /** The search address template of an OpenSearch description, or null when it offers no catalog search. */
    fun searchTemplate(input: InputStream): String? {
        try {
            val xml = parser(input)
            var template: String? = null
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG || xml.name != "Url") continue
                val type = xml.getAttributeValue(null, "type").orEmpty()
                val candidate = xml.getAttributeValue(null, "template").orEmpty()
                if (!type.startsWith(ATOM) || TERMS !in candidate) continue
                // A template that says it answers with a catalog beats a plain Atom one.
                if (template == null || "opds-catalog" in type) template = candidate
            }
            return template
        } catch (e: XmlPullParserException) {
            throw NotACatalogException("not a search description", e)
        }
    }

    /**
     * The address that searches for [query]. Other placeholders of the
     * template are optional by the OpenSearch rules and are left empty.
     */
    fun searchUrl(template: String, base: String, query: String): String? {
        val filled = template.replace(TERMS, URLEncoder.encode(query.trim(), "UTF-8"))
            .replace(Regex("\\{[^}]*\\}"), "")
        return resolve(base, filled)
    }

    private fun parser(input: InputStream): XmlPullParser =
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser().apply { setInput(input, null) }

    private fun feed(xml: XmlPullParser, base: String): OpdsFeed {
        var title = ""
        val entries = ArrayList<OpdsEntry>()
        var next: String? = null
        var template: String? = null
        var description: String? = null
        children(xml) {
            when (xml.name) {
                "title" -> title = text(xml)
                "entry" -> entry(xml, base)?.let(entries::add)
                "link" -> {
                    val rel = xml.getAttributeValue(null, "rel").orEmpty()
                    val type = xml.getAttributeValue(null, "type").orEmpty()
                    val href = xml.getAttributeValue(null, "href").orEmpty()
                    when {
                        rel == "next" -> next = resolve(base, href)
                        rel == "search" && type.startsWith(OPEN_SEARCH) -> description = resolve(base, href)
                        rel == "search" && TERMS in href -> template = href
                    }
                    skip(xml)
                }
                else -> skip(xml)
            }
        }
        return OpdsFeed(title, entries, next, template, description)
    }

    private fun entry(xml: XmlPullParser, base: String): OpdsEntry? {
        var id = ""
        var title = ""
        var summary = ""
        var content = ""
        val authors = ArrayList<String>()
        var feed: String? = null
        val downloads = ArrayList<OpdsLink>()
        var image: String? = null
        var thumbnail: String? = null
        children(xml) {
            when (xml.name) {
                "id" -> id = text(xml)
                "title" -> title = text(xml)
                "summary" -> summary = text(xml)
                "content" -> content = text(xml)
                "author" -> children(xml) {
                    if (xml.name == "name") text(xml).takeIf { it.isNotEmpty() }?.let(authors::add) else skip(xml)
                }
                "link" -> {
                    val rel = xml.getAttributeValue(null, "rel").orEmpty()
                    val type = xml.getAttributeValue(null, "type").orEmpty()
                    val href = resolve(base, xml.getAttributeValue(null, "href").orEmpty())
                    val linkTitle = xml.getAttributeValue(null, "title").orEmpty()
                    if (href != null) {
                        when {
                            rel == ACQUISITION || rel == OPEN_ACCESS -> downloads += OpdsLink(href, type, linkTitle)
                            rel == THUMBNAIL -> thumbnail = thumbnail ?: href
                            rel == IMAGE -> image = image ?: href
                            type.startsWith(ATOM) && rel !in ASIDE && !rel.startsWith(ACQUISITION) -> feed = feed ?: href
                        }
                    }
                    skip(xml)
                }
                else -> skip(xml)
            }
        }
        if (title.isEmpty() && feed == null && downloads.isEmpty()) return null
        return OpdsEntry(id, title, authors, summary.ifEmpty { content }, feed, downloads, thumbnail ?: image)
    }

    /** Runs [each] on every child element of the current one; [each] must consume the child. */
    private inline fun children(xml: XmlPullParser, each: () -> Unit) {
        while (xml.next() != XmlPullParser.END_TAG) {
            if (xml.eventType == XmlPullParser.END_DOCUMENT) cutShort()
            if (xml.eventType == XmlPullParser.START_TAG) each()
        }
    }

    /** The text of the current element with the text of everything inside it, as one line. */
    private fun text(xml: XmlPullParser): String {
        val out = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (xml.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    out.append(' ')
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT -> out.append(xml.text)
                XmlPullParser.END_DOCUMENT -> cutShort()
            }
        }
        return out.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** Half a page must not pass for a whole one: its last book would be listed without its files. */
    private fun cutShort(): Nothing = throw NotACatalogException("the page ends in the middle")

    private fun skip(xml: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (xml.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> cutShort()
            }
        }
    }

    /** An absolute http(s) address, or null for anything else: pictures given inline, mail, broken links. */
    internal fun resolve(base: String, href: String): String? {
        if (href.isBlank()) return null
        val resolved = try {
            URI(base).resolve(href.trim().replace(" ", "%20"))
        } catch (e: URISyntaxException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return resolved.toString().takeIf { resolved.scheme == "http" || resolved.scheme == "https" }
    }
}
