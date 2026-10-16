package org.evsyukov.perigee.opds

import java.io.InputStream
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Reads OPDS 2.0 catalog pages (JSON) into the same shape as Atom ones. */
internal object OpdsJson {
    private const val OPDS = "application/opds+json"
    private const val PUBLICATION = "application/opds-publication+json"

    fun parse(input: InputStream, base: String): OpdsFeed {
        val root = try {
            JSONObject(input.bufferedReader().readText())
        } catch (e: JSONException) {
            // Half a page fails here too: JSON that ends in the middle does not parse.
            throw NotACatalogException("not a catalog page", e)
        }
        val links = links(root.optJSONArray("links"), base)
        val isFeed = root.has("navigation") || root.has("publications") || root.has("groups")
        if (!isFeed) {
            // A publication's own page, as some catalogs link to it.
            val single = root.optJSONObject("metadata")?.let { publication(root, base) }
                ?: throw NotACatalogException("not a catalog page")
            return OpdsFeed("", listOf(single))
        }
        val entries = ArrayList<OpdsEntry>()
        entries += navigation(root.optJSONArray("navigation"), base)
        entries += publications(root.optJSONArray("publications"), base)
        objects(root.optJSONArray("groups")).forEach { group ->
            val title = group.optJSONObject("metadata")?.let { text(it, "title") }.orEmpty()
            val self = links(group.optJSONArray("links"), base).firstOrNull { "self" in it.rels }?.url
            if (self != null && title.isNotEmpty()) {
                // A group that has a page of its own is a section: the list stays a list of places to go.
                entries += OpdsEntry(self, title, feed = self)
            } else {
                entries += navigation(group.optJSONArray("navigation"), base)
                entries += publications(group.optJSONArray("publications"), base)
            }
        }
        val search = links.filter { "search" in it.rels }
        return OpdsFeed(
            title = root.optJSONObject("metadata")?.let { text(it, "title") }.orEmpty(),
            entries = entries,
            next = links.firstOrNull { "next" in it.rels }?.url,
            searchTemplate = search.firstOrNull { it.templated && it.type.startsWith(OPDS) }?.raw,
            searchDescription = search.firstOrNull { it.type.startsWith(OpdsParser.OPEN_SEARCH) }?.url,
        )
    }

    /** A link as written: [url] resolved against the page, [raw] as it is, for templates. */
    private class Link(val raw: String, val url: String?, val type: String, val rels: List<String>, val title: String, val templated: Boolean)

    private fun links(array: JSONArray?, base: String): List<Link> = objects(array).mapNotNull { link ->
        val href = link.optString("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val templated = link.optBoolean("templated")
        Link(
            raw = href,
            url = if (templated) null else OpdsParser.resolve(base, href),
            type = link.optString("type"),
            rels = when (val rel = link.opt("rel")) {
                is JSONArray -> (0 until rel.length()).map { rel.optString(it) }
                is String -> listOf(rel)
                else -> emptyList()
            },
            title = text(link, "title"),
            templated = templated,
        )
    }.filter { it.templated || it.url != null }

    private fun navigation(array: JSONArray?, base: String): List<OpdsEntry> = links(array, base).mapNotNull { link ->
        val url = link.url ?: return@mapNotNull null
        OpdsEntry(url, link.title.ifEmpty { url }, feed = url)
    }

    private fun publications(array: JSONArray?, base: String): List<OpdsEntry> =
        objects(array).mapNotNull { publication(it, base) }

    private fun publication(publication: JSONObject, base: String): OpdsEntry? {
        val metadata = publication.optJSONObject("metadata") ?: return null
        val links = links(publication.optJSONArray("links"), base)
        val downloads = links.filter { link ->
            link.url != null && link.rels.any { it == OpdsParser.ACQUISITION || it == OpdsParser.OPEN_ACCESS }
        }.map { OpdsLink(it.url!!, it.type, it.title) }
        val feed = links.firstOrNull { link ->
            link.url != null && (link.type.startsWith(OPDS) || link.type.startsWith(PUBLICATION)) &&
                link.rels.none { it in OpdsParser.ASIDE || it.startsWith(OpdsParser.ACQUISITION) }
        }?.url
        val title = text(metadata, "title")
        if (title.isEmpty() && feed == null && downloads.isEmpty()) return null
        return OpdsEntry(
            id = metadata.optString("identifier"),
            title = title,
            authors = names(metadata.opt("author")),
            summary = text(metadata, "description").replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim(),
            feed = feed,
            downloads = downloads,
            cover = cover(publication.optJSONArray("images"), base),
        )
    }

    /** The smallest picture of the cover: what a line of the list shows. */
    private fun cover(images: JSONArray?, base: String): String? = objects(images)
        .mapNotNull { image -> OpdsParser.resolve(base, image.optString("href"))?.let { it to image.optInt("height", Int.MAX_VALUE) } }
        .minByOrNull { it.second }?.first

    /** Contributors as OPDS 2.0 writes them: a name, an object with one, or a list of either. */
    private fun names(value: Any?): List<String> = when (value) {
        is String -> listOf(value.trim())
        is JSONObject -> listOf(text(value, "name"))
        is JSONArray -> (0 until value.length()).flatMap { names(value.opt(it)) }
        else -> emptyList()
    }.filter { it.isNotEmpty() }

    /** A string of [obj], or the first of a language map such as `{"en": "…", "fr": "…"}`. */
    private fun text(obj: JSONObject, name: String): String = when (val value = obj.opt(name)) {
        is String -> value.trim()
        is JSONObject -> value.keys().asSequence().firstNotNullOfOrNull { value.opt(it) as? String }?.trim().orEmpty()
        else -> ""
    }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}
