package org.evsyukov.perigee.opds

/** A file a catalog offers for a book. [url] is absolute; [type] is a media type. */
data class OpdsLink(val url: String, val type: String, val title: String = "") {
    /** The book format the app reads this file as, or "" when it reads none such. */
    val format: String get() = FORMATS[type.substringBefore(';').trim().lowercase()].orEmpty()

    private companion object {
        val FORMATS = mapOf(
            "application/epub+zip" to "epub",
            "application/x-fictionbook+xml" to "fb2",
            "application/x-fictionbook" to "fb2",
            "application/fb2+xml" to "fb2",
            "text/fb2+xml" to "fb2",
            "application/fb2+zip" to "fb2.zip",
            "application/x-zip-compressed-fb2" to "fb2.zip",
            "application/x-mobipocket-ebook" to "mobi",
            "text/plain" to "txt",
            "application/rtf" to "rtf",
            "text/rtf" to "rtf",
            "application/msword" to "doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            "application/vnd.oasis.opendocument.text" to "odt",
            "text/html" to "html",
        )
    }
}

/**
 * A line of a catalog page: a section to go into, a book to download, or both.
 * Addresses are absolute.
 */
data class OpdsEntry(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val summary: String = "",
    /** The page this entry leads to, if it is a section or has a page of its own. */
    val feed: String? = null,
    /** Files of the book, in the order the catalog lists them. */
    val downloads: List<OpdsLink> = emptyList(),
    /** A small picture of the cover, or the full one when the catalog has no small one. */
    val cover: String? = null,
)

/** A page of a catalog. */
data class OpdsFeed(
    val title: String,
    val entries: List<OpdsEntry>,
    /** The page that continues this one. */
    val next: String? = null,
    /** An address with `{searchTerms}` in place of what is looked for. May be relative to the page. */
    val searchTemplate: String? = null,
    /** An OpenSearch description that holds such a template, for catalogs that give no template outright. */
    val searchDescription: String? = null,
) {
    val searchable: Boolean get() = searchTemplate != null || searchDescription != null
}
