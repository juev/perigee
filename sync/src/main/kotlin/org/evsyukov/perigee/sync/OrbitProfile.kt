package org.evsyukov.perigee.sync

/**
 * The reader's own page on the server, as its web pages have it: who they are there, the achievements it
 * awards and the reading it has counted. All of it comes through the reader's session, like the shelves and
 * a book's page; nothing here is kept, and an answer the server does not give leaves its own block empty.
 */

/** Who the reader is on the server. */
data class RemoteProfile(
    val id: Long,
    val username: String,
    /** The name the server shows; the user name when it has none of its own. */
    val name: String,
    val isSuperuser: Boolean,
    /** Whether the server says it has a picture of the reader to ask for. */
    val hasPicture: Boolean,
)

/** One of the server's achievements, and how far the reader is with it. */
data class RemoteAchievement(
    val key: String,
    val name: String,
    val description: String,
    /** The server's word for how rare it is: `common`, `rare`, `epic` or `legendary`. */
    val rarity: String,
    val earned: Boolean,
    /** The day it was awarded, as the server gives it (ISO 8601); empty for one not earned. */
    val awardedAt: String,
    /** How far the reader is towards it, of [threshold]; both null when the server does not count it. */
    val progress: Int?,
    val threshold: Int?,
)

/** The server's own grouping of its achievements, with how many of them the reader has. */
data class RemoteAchievementCategory(
    val key: String,
    val label: String,
    val earned: Int,
    val total: Int,
    val achievements: List<RemoteAchievement>,
)

/** Everything the server awards, as it names it and as far as the reader has got. */
data class RemoteAchievements(
    val earned: Int,
    val total: Int,
    val categories: List<RemoteAchievementCategory>,
)

/** The numbers the server has worked out of the reader's reading. */
data class RemoteReadingSummary(
    val tracked: Int,
    val inProgress: Int,
    val completed: Int,
    val meanProgressPercent: Double,
)

/** This year's goal, as the server's own widget has it; [books] is null when the reader set none. */
data class RemoteReadingGoal(val books: Int?, val read: Int, val year: Int)

/** Days read in a row, and which of the last seven were. */
data class RemoteReadingStreak(val current: Int, val longest: Int, val lastSevenDays: List<Boolean>)

/** A day with reading time on it, by the server's own day, as `2026-11-13`. */
data class RemoteReadingDay(val day: String, val seconds: Long)

/**
 * The reader's reading as the server counts it. A part the server does not answer — an older one without
 * the widget, a reader who set no goal — is null or empty, and the rest of the page stays; [daysKnown] tells
 * an empty half year from a server that has no calendar at all.
 */
data class RemoteReading(
    val summary: RemoteReadingSummary?,
    val goal: RemoteReadingGoal?,
    val streak: RemoteReadingStreak?,
    val days: List<RemoteReadingDay>,
    val daysKnown: Boolean,
)
