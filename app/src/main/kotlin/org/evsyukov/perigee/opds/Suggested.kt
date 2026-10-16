package org.evsyukov.perigee.opds

import androidx.annotation.StringRes
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.Catalog

/** A public catalog offered in the list until the reader adds it. */
internal data class Suggestion(val title: String, val url: String, @param:StringRes val about: Int)

/**
 * Catalogs open to everyone that give books away. Each was checked on
 * 2026-10-16 to answer without a password and offer files the app reads.
 */
internal val SUGGESTED = listOf(
    Suggestion("Project Gutenberg", "https://www.gutenberg.org/ebooks.opds/", R.string.suggested_gutenberg),
)

/** The suggestions not among [catalogs] yet. */
internal fun suggestionsBesides(catalogs: List<Catalog>): List<Suggestion> =
    SUGGESTED.filter { suggestion -> catalogs.none { it.url == suggestion.url } }
