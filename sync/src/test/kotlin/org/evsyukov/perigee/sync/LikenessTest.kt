package org.evsyukov.perigee.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which books are taken for one book by what they say of themselves. */
class LikenessTest {
    private fun book(title: String, vararg authors: String, series: String = "", number: String = "") =
        Described(title, authors.toList(), series, number)

    @Test
    fun caseSpacesPunctuationAndYoDoNotTellTitlesApart() {
        assertEquals("война и мир", Likeness.plain("  Война и  мир. "))
        assertEquals("елка", Likeness.plain("Ёлка"))
        assertEquals("", Likeness.plain(" … "))
        assertTrue(Likeness.alike(book("Всё ещё  здесь!", "Пётр Иванов"), book("все еще здесь", "Петр ИВАНОВ")))
        assertFalse(Likeness.alike(book("Война и мир", "Лев Толстой"), book("Война и мир. Том 2", "Лев Толстой")))
    }

    @Test
    fun authorIsTheSameWhicheverWayRoundTheNameIsWritten() {
        assertTrue(Likeness.alike(book("Title", "Толстой Лев"), book("Title", "Лев Толстой")))
        assertTrue(Likeness.alike(book("Title", "Tolstoy, Leo"), book("Title", "Leo Tolstoy")))
        assertFalse(Likeness.alike(book("Title", "Лев Толстой"), book("Title", "Алексей Толстой")))
    }

    @Test
    fun oneAuthorInCommonIsEnoughAndNoneNamedByBothIsToo() {
        assertTrue(Likeness.alike(book("Title", "First Author", "Second Author"), book("Title", "Second Author")))
        assertTrue(Likeness.alike(book("Title"), book("Title")))
        assertFalse("an author named by one only", Likeness.alike(book("Title", "An Author"), book("Title")))
        assertFalse(Likeness.alike(book("Title"), book("Title", "An Author")))
    }

    @Test
    fun bookWithoutATitleIsLikeNoOther() {
        assertFalse(Likeness.alike(book("", "An Author"), book("", "An Author")))
        assertFalse(Likeness.alike(book(" - ", "An Author"), book("…", "An Author")))
    }

    @Test
    fun seriesTellsBooksApartOnlyWhenBothNameOne() {
        val plain = book("Title", "An Author")
        assertTrue(Likeness.alike(plain, book("Title", "An Author", series = "Saga", number = "2")))
        assertTrue(Likeness.alike(book("Title", "An Author", series = "The  Saga", number = "1"), book("Title", "An Author", series = "the saga", number = "1.0")))
        assertTrue(Likeness.alike(book("Title", "An Author", series = "Saga"), book("Title", "An Author", series = "Saga", number = "3")))
        assertFalse(Likeness.alike(book("Title", "An Author", series = "Saga", number = "1"), book("Title", "An Author", series = "Saga", number = "2")))
        assertFalse(Likeness.alike(book("Title", "An Author", series = "Saga"), book("Title", "An Author", series = "Other Saga")))
    }

    @Test
    fun eachBookIsPairedWithTheFirstThatIsLikeIt() {
        val mine = mapOf(
            "a" to book("Война и мир", "Лев Толстой"), "b" to book("Nowhere Else", "An Author"), "c" to book("Shared", "An Author"),
        )
        val theirs = listOf(
            7 to book("Shared", "Somebody Else"), 8 to book("ВОЙНА И МИР", "Толстой Лев"), 9 to book("Shared", "An Author"),
            10 to book("shared", "An Author"),
        )
        assertEquals(mapOf("a" to 8, "c" to 9), Likeness.pairs(mine, theirs))
    }
}
