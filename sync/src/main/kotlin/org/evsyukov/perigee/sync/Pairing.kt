package org.evsyukov.perigee.sync

/**
 * Finds which of the server's marks a mark here is, when the devices' channel
 * did not say. The server's session lists marks by its own numbers and its own
 * places, so they are told apart by what the server was sent of them: a
 * quote's words, a bookmark's title. Marks that say the same are paired in the
 * order they were made, which is the order the server numbered them in.
 */
internal object Pairing {
    /**
     * Pairs each of [mine] with one of [theirs] that says the same, by [said]
     * of each side. Each is used once; what has no partner is left out.
     * [mine] and [theirs] are in the order they were made.
     */
    fun <M, T> pair(mine: List<M>, theirs: List<T>, said: (M) -> String, saidThere: (T) -> String): List<Pair<M, T>> {
        val waiting = theirs.groupByTo(HashMap()) { saidThere(it) }.mapValues { ArrayDeque(it.value) }
        return mine.mapNotNull { mark -> waiting[said(mark)]?.removeFirstOrNull()?.let { mark to it } }
    }

    /** A quote as the server keeps its words: without white space at the ends. */
    fun quoteWords(text: String, limit: Int): String = text.take(limit).trim()

    /**
     * The title the server gives a bookmark sent with these words and chapter:
     * the words, else the chapter, else a word of its own.
     */
    fun bookmarkTitle(words: String, chapter: String?, wordsLimit: Int, chapterLimit: Int): String =
        words.take(wordsLimit).trim().ifEmpty { chapter?.take(chapterLimit)?.trim().orEmpty() }.ifEmpty { "Bookmark" }
}
