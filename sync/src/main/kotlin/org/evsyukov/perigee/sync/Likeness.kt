package org.evsyukov.perigee.sync

/** What a book says of itself, as far as two copies of it can be told to be one book by. */
internal data class Described(val title: String, val authors: List<String>, val series: String = "", val seriesNumber: String = "")

/**
 * Tells which books go by the same title and authors, whatever their files.
 * Letters are compared without their case, "ё" as "е", and anything that is
 * not a letter or a digit as a space between words.
 */
internal object Likeness {
    /** Text as it is compared: "  Война и  мир. " and "война и мир" are the same. */
    fun plain(text: String): String = buildString {
        var gap = false
        for (char in text.lowercase()) {
            if (char.isLetterOrDigit()) {
                if (gap && isNotEmpty()) append(' ')
                gap = false
                append(if (char == 'ё') 'е' else char)
            } else {
                gap = true
            }
        }
    }

    /** A name whichever way round it is written: "Толстой Лев" is "Лев Толстой". */
    fun name(author: String): String = plain(author).split(' ').sorted().joinToString(" ")

    /**
     * Whether two descriptions are of one book: the same title, an author in
     * common or none named by either, and not two different books of a series.
     * A book without a title is like no other.
     */
    fun alike(one: Described, other: Described): Boolean {
        val title = plain(one.title)
        if (title.isEmpty() || title != plain(other.title)) return false
        val mine = one.authors.map(::name).filter { it.isNotEmpty() }.toSet()
        val theirs = other.authors.map(::name).filter { it.isNotEmpty() }.toSet()
        if (mine.isEmpty() != theirs.isEmpty()) return false
        if (mine.isNotEmpty() && mine.none { it in theirs }) return false
        return sameSeries(one, other)
    }

    /** A series tells books apart only when both name one: a file often leaves it out. */
    private fun sameSeries(one: Described, other: Described): Boolean {
        val mine = plain(one.series)
        val theirs = plain(other.series)
        if (mine.isEmpty() || theirs.isEmpty()) return true
        if (mine != theirs) return false
        val first = one.seriesNumber.trim()
        val second = other.seriesNumber.trim()
        if (first.isEmpty() || second.isEmpty()) return true
        val a = first.toFloatOrNull()
        val b = second.toFloatOrNull()
        // "1" and "1.0" are one number.
        return if (a != null && b != null) a == b else first == second
    }

    /**
     * For each of [mine] the first of [theirs] that is like it, by the key it
     * was given with.
     */
    fun <K, V> pairs(mine: Map<K, Described>, theirs: List<Pair<V, Described>>): Map<K, V> {
        val byTitle = theirs.groupBy { plain(it.second.title) }
        val found = HashMap<K, V>()
        for ((key, book) in mine) {
            val like = byTitle[plain(book.title)]?.firstOrNull { alike(book, it.second) } ?: continue
            found[key] = like.first
        }
        return found
    }
}
