package org.evsyukov.perigee.contract

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.sync.OrbitArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reader's own page on a real bookOrbit server: who they are there, what it awards them and what it has
 * counted of their reading. Run against the version the app is written for and the latest; every part of the
 * page is compared with what the server answers to the app's own session, so a block that quietly came back
 * empty is told from one the server really has nothing for.
 */
@RunWith(AndroidJUnit4::class)
class ProfileContractTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: BookOrbitServer
    private lateinit var device: ContractDevice
    private val area get() = device.orbit.area

    @Before
    fun connect() {
        server = ContractServer.connect()
        device = reader(context)
    }

    @Test
    fun theReadersAccountIsTheOneItSignedInAs() = runBlocking<Unit> {
        val raw = server.jwt("GET", "/auth/me").json()
        val profile = area.profile()

        assertEquals(BookOrbitServer.USERNAME, profile.username)
        assertEquals(raw.getLong("id"), profile.id)
        assertTrue("the server shows a name: $profile", profile.name.isNotEmpty())
        assertEquals("a picture is asked for only when the server names one", !raw.isNull("avatarUrl"), profile.hasPicture)
    }

    @Test
    fun theAchievementsAreTheServersCatalogueWithTheReadersOwnProgress() = runBlocking<Unit> {
        val raw = server.jwt("GET", "/achievements").json()
        val achievements = area.achievements()
        assertNotNull("this server awards achievements", achievements)
        val catalogue = checkNotNull(achievements)

        assertEquals(raw.getInt("totalEarned"), catalogue.earned)
        assertEquals(raw.getInt("totalAvailable"), catalogue.total)
        assertEquals("no category is left out or made up", catalogue.total, catalogue.categories.sumOf { it.total })
        assertEquals(catalogue.earned, catalogue.categories.sumOf { it.earned })
        assertTrue("the reader has not earned more than there are", catalogue.earned <= catalogue.total)

        for (category in catalogue.categories) {
            assertTrue("a category says what it is: ${category.key}", category.label.isNotEmpty())
            assertTrue("what is in it is counted: ${category.key}", category.achievements.isNotEmpty())
            for (item in category.achievements) {
                assertTrue("an achievement says what it is: ${item.key}", item.name.isNotEmpty())
                assertTrue("its rarity is a word of the server: $item", item.rarity.isNotEmpty())
                if (!item.earned) assertTrue("nothing is awarded on a day it was not earned: $item", item.awardedAt.isEmpty())
                val progress = item.progress
                val threshold = item.threshold
                if (progress != null && threshold != null) assertTrue("a count is not past its own end: $item", progress <= threshold)
            }
        }
    }

    @Test
    fun theReadingIsTheServersOwnNumbersAndCalendar() = runBlocking<Unit> {
        val rawSummary = server.jwt("GET", "/user-statistics/summary").json()
        val rawDays = server.jwt("GET", "/user-statistics/reading-heatmap?days=${OrbitArea.HEATMAP_DAYS}").array()
        val reading = area.reading()

        val summary = reading.summary
        assertNotNull("the summary is on every version the app is made for", summary)
        assertEquals(rawSummary.getInt("trackedBooks"), summary?.tracked)
        assertEquals(rawSummary.getInt("completedBooks"), summary?.completed)
        assertEquals(rawSummary.getInt("inProgressBooks"), summary?.inProgress)
        assertEquals(rawSummary.getDouble("meanProgressPercent"), summary?.meanProgressPercent ?: 0.0, 0.01)

        assertTrue("the calendar is there: $reading", reading.daysKnown)
        assertEquals("a day apiece for the days it names", rawDays.length(), reading.days.size)
        assertTrue(
            "every day is the server's own: $reading",
            reading.days.all { runCatching { LocalDate.parse(it.day) }.isSuccess && it.seconds >= 0 },
        )

        // The goal and the streak are the dashboard's widgets; a server with none of them may leave either out,
        // but one it gives is the shape the app draws.
        reading.goal?.let { goal ->
            assertTrue("this year's goal is a number of books: $goal", (goal.books ?: 0) >= 0)
            assertTrue("of which some are read: $goal", goal.read >= 0)
        }
        reading.streak?.let { streak ->
            assertTrue("a streak of days: $streak", streak.current >= 0 && streak.longest >= streak.current)
            assertEquals("the last seven days are seven", 7, streak.lastSevenDays.size)
        }
    }

    private companion object {
        private var shared: ContractDevice? = null

        /** Signed in once for the whole class: the server takes five sign-ins a minute. */
        @Synchronized
        fun reader(context: Context): ContractDevice = shared ?: ContractDevice(context).also { device ->
            runBlocking { device.signIn() }
            shared = device
        }
    }
}
