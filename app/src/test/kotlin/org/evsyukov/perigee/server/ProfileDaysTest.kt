package org.evsyukov.perigee.server

import org.evsyukov.perigee.sync.RemoteReadingDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader's page's own arithmetic: the weeks its calendar draws, the letters that stand for a reader's name. */
class ProfileDaysTest {

    @Test
    fun everyWeekIsSevenDaysEndingOnTheWeekOfTheLastDayTheServerNamed() {
        val weeks = weeksOf(listOf(RemoteReadingDay("2026-11-09", 600), RemoteReadingDay("2026-11-11", 3600)))

        assertEquals("a half year of weeks", 26, weeks.size)
        assertTrue("a column each, a day in it", weeks.all { it.size == 7 })
        val last = weeks.last()
        assertEquals("the week runs to its Sunday, whatever the server's last day", "2026-11-15", last.last().first)
        assertEquals("and starts on its Monday", "2026-11-09", last.first().first)
        assertEquals("a day with time on it is in its own square", 3600L, last[2].second)
        assertEquals("a day without is empty, not missing", 0L, last[3].second)
        assertEquals("and the week before is a week before", "2026-11-02", weeks[24].first().first)

        val named = weeks.flatten().filter { it.second > 0 }
        assertEquals(listOf("2026-11-09" to 600L, "2026-11-11" to 3600L), named)
    }

    @Test
    fun aCalendarWithNoDaysIsWeeksOfEmptySquares() {
        val weeks = weeksOf(emptyList())
        assertEquals(26, weeks.size)
        assertTrue(weeks.all { week -> week.size == 7 && week.all { it.second == 0L } })
    }

    @Test
    fun initialsAreTheFirstLettersOfTheFirstTwoWords() {
        assertEquals("RN", initialsOf("Reader Name"))
        assertEquals("R", initialsOf("reader"))
        assertEquals("ИП", initialsOf("Иван Петров"))
        assertEquals("", initialsOf(""))
        assertEquals("no letters for no name", "", initialsOf("  "))
    }

    @Test
    fun aDayTheServerNamesIsWrittenAsTheDeviceWritesIt() {
        assertTrue(dayOf("").isEmpty())
        assertTrue(dayOf("2026-11").isEmpty())
        assertTrue(dayOf("2026-11-11T10:00:00.000Z").isNotEmpty())
    }
}
