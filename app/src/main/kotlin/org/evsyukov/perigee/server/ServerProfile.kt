package org.evsyukov.perigee.server

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt
import org.evsyukov.perigee.R
import org.evsyukov.perigee.sync.RemoteAchievement
import org.evsyukov.perigee.sync.RemoteAchievementCategory
import org.evsyukov.perigee.sync.RemoteAchievements
import org.evsyukov.perigee.sync.RemoteProfile
import org.evsyukov.perigee.sync.RemoteReading
import org.evsyukov.perigee.sync.RemoteReadingDay
import org.evsyukov.perigee.sync.RemoteReadingGoal
import org.evsyukov.perigee.sync.RemoteReadingStreak
import org.evsyukov.perigee.sync.RemoteReadingSummary
import org.evsyukov.perigee.ui.Centred
import org.evsyukov.perigee.ui.EmptyState
import org.evsyukov.perigee.ui.ShelfIcon

/**
 * The reader's own page on the server: who they are there, what it has counted of their reading, and the
 * achievements it awards, in its own words. Every block is asked of the server and drawn as it answered;
 * one the server does not have is left out, and the rest of the page stays.
 */
@Composable
internal fun Profile(padding: PaddingValues, state: ServerState, model: ServerViewModel, list: LazyListState) {
    val page = state.profile
    Centred(padding) { inner ->
        LazyColumn(Modifier.fillMaxSize().testTag("server-profile"), state = list, contentPadding = inner) {
            page.failure?.let { failure -> item("failure") { Failure(failure, model::retryProfile) } }
            val profile = page.profile
            if (profile != null) item("reader") { Reader(profile, model) }
            val reading = page.reading
            val summary = reading?.summary
            if (summary != null) item("numbers") { Numbers(summary) }
            if (reading?.goal != null || reading?.streak != null) {
                item("year") { GoalAndStreak(reading?.goal, reading?.streak) }
            }
            if (reading != null && reading.daysKnown) item("days") { ReadingDays(reading.days) }
            val achievements = page.achievements
            when {
                achievements != null && achievements.categories.isNotEmpty() -> {
                    item("achievements") { AchievementsSummary(achievements) }
                    for (category in achievements.categories) {
                        item("category-${category.key}") { CategoryHeader(category) }
                        items(category.achievements, key = { "${category.key}/${it.key}" }) { achievement ->
                            AchievementRow(achievement)
                        }
                    }
                }
                // A server that answered and has nothing to award says so, rather than showing a heading alone.
                page.loaded && page.failure == null -> item("no-achievements") {
                    EmptyState(ShelfIcon, stringResource(R.string.server_profile_no_achievements))
                }
            }
        }
    }
}

/** The reader on the server: their picture, or their initials while it has none, and the name it shows. */
@Composable
private fun Reader(profile: RemoteProfile, model: ServerViewModel) {
    val picture by produceState<ImageBitmap?>(null, profile.id) { value = model.picture(profile) }
    val shown = picture
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (shown != null) {
            Image(
                shown, stringResource(R.string.server_profile_picture),
                Modifier.size(56.dp).clip(CircleShape), contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initialsOf(profile.name), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.testTag("server-profile-initials"),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("server-profile-name"),
            )
            if (profile.username.isNotEmpty()) {
                Text(
                    "@${profile.username}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What the server has counted of the reader's reading: the books it keeps track of, and how far they got. */
@Composable
private fun Numbers(summary: RemoteReadingSummary) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("server-profile-numbers"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Number(summary.tracked, stringResource(R.string.server_profile_tracked), Modifier.weight(1f))
        Number(summary.inProgress, stringResource(R.string.server_profile_in_progress), Modifier.weight(1f))
        Number(summary.completed, stringResource(R.string.server_profile_completed), Modifier.weight(1f))
    }
    Text(
        stringResource(R.string.server_profile_mean_progress, summary.meanProgressPercent.roundToInt()),
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Number(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$value", style = MaterialTheme.typography.titleLarge)
        Text(
            label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** This year's goal and the reader's streak, each only when the server's widgets gave it. */
@Composable
private fun GoalAndStreak(goal: RemoteReadingGoal?, streak: RemoteReadingStreak?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (goal != null) {
            Block(Modifier.weight(1f).testTag("server-profile-goal")) {
                Text(
                    stringResource(R.string.server_profile_goal, goal.year), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val books = goal.books
                Text(
                    if (books == null) {
                        stringResource(R.string.server_profile_no_goal)
                    } else {
                        stringResource(R.string.server_profile_goal_value, goal.read, books)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (books != null && books > 0) {
                    LinearProgressIndicator(
                        progress = { (goal.read.toFloat() / books).coerceIn(0f, 1f) },
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
        if (streak != null) {
            Block(Modifier.weight(1f).testTag("server-profile-streak")) {
                Text(
                    stringResource(R.string.server_profile_streak), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(pluralStringResource(R.plurals.server_profile_streak_days, streak.current, streak.current), style = MaterialTheme.typography.titleMedium)
                Text(
                    pluralStringResource(R.plurals.server_profile_longest, streak.longest, streak.longest),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SevenDays(streak.lastSevenDays)
            }
        }
    }
}

/** Which of the last seven days were read: a dot each, filled for a day with reading on it. */
@Composable
private fun SevenDays(days: List<Boolean>) {
    val read = days.count { it }
    val description = pluralStringResource(R.plurals.server_profile_days_read, read, read)
    Row(
        Modifier.padding(top = 4.dp).semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (day in days) {
            Box(
                Modifier.size(10.dp).clip(CircleShape).background(
                    if (day) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }
    }
}

/** Half a year of reading days, a square a day, in weeks: the shape of the reader's year. */
@Composable
private fun ReadingDays(days: List<RemoteReadingDay>) {
    val weeks = remember(days) { weeksOf(days) }
    val read = days.count { it.seconds > 0 }
    val description = pluralStringResource(R.plurals.server_profile_days_read, read, read)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.server_profile_days), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.server_profile_days_note), Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (read == 0) {
            Text(
                stringResource(R.string.server_profile_days_none), Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // The squares are as large as the column can hold all of them: a narrow screen keeps them small
            // rather than cutting the half year off.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val side = ((maxWidth - CELL_GAP * (WEEKS - 1)) / WEEKS).coerceIn(4.dp, 12.dp)
                Row(
                    Modifier.padding(top = 8.dp).testTag("server-profile-days").semantics { contentDescription = description },
                    horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
                ) {
                    for (week in weeks) {
                        Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                            for ((_, seconds) in week) {
                                Box(Modifier.size(side).clip(RoundedCornerShape(2.dp)).background(shadeOf(seconds)))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A day with less than the first of the shades on it is a day the server counted as read a little. */
@Composable
private fun shadeOf(seconds: Long): Color = when {
    seconds <= 0 -> MaterialTheme.colorScheme.surfaceVariant
    seconds < 15 * 60 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    seconds < 60 * 60 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun AchievementsSummary(achievements: RemoteAchievements) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("server-profile-achievements"),
    ) {
        Text(stringResource(R.string.server_profile_achievements), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.server_profile_achievements_count, achievements.earned, achievements.total),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (achievements.total > 0) {
            LinearProgressIndicator(
                progress = { (achievements.earned.toFloat() / achievements.total).coerceIn(0f, 1f) },
                Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun CategoryHeader(category: RemoteAchievementCategory) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                category.label, Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.server_profile_achievement_progress, category.earned, category.total),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (category.total > 0) {
            LinearProgressIndicator(
                progress = { (category.earned.toFloat() / category.total).coerceIn(0f, 1f) },
                Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

/** One achievement: what it is, and either the day it was earned or how far the reader is towards it. */
@Composable
private fun AchievementRow(achievement: RemoteAchievement) {
    val progress = achievement.progress
    val threshold = achievement.threshold
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(achievement.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            when {
                achievement.earned -> {
                    val day = dayOf(achievement.awardedAt)
                    if (day.isNotEmpty()) {
                        Text(
                            stringResource(R.string.server_profile_earned_on, day),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                progress != null && threshold != null -> Text(
                    stringResource(R.string.server_profile_achievement_progress, progress, threshold),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (achievement.description.isNotEmpty()) {
            Text(
                achievement.description, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
        if (!achievement.earned && progress != null && threshold != null && threshold > 0) {
            LinearProgressIndicator(
                progress = { (progress.toFloat() / threshold).coerceIn(0f, 1f) },
                Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Block(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

/** Weeks of the reader's calendar in the app's own week, so that the squares of a week stand in a column. */
private const val WEEKS = 26

/** The space between two squares of the calendar, and between two of its weeks. */
private val CELL_GAP = 2.dp

/**
 * The last [WEEKS] weeks of [days], Monday first, each week a column of seven days: a day the server did not
 * name has no time on it. The weeks end with the week of the last day the server named, so a calendar that
 * stops short of today is not filled to the edge with empty ones.
 */
internal fun weeksOf(days: List<RemoteReadingDay>): List<List<Pair<String, Long>>> {
    val byDay = days.associate { it.day to it.seconds }
    val last = days.mapNotNull { runCatching { LocalDate.parse(it.day) }.getOrNull() }.maxOrNull() ?: LocalDate.now()
    val end = last.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    return (0 until WEEKS).map { week ->
        (0 until 7).map { day ->
            val date = end.minusWeeks((WEEKS - 1 - week).toLong()).minusDays((6 - day).toLong())
            date.toString() to (byDay[date.toString()] ?: 0L)
        }
    }
}

/** Up to two letters to stand for a reader whose picture the server does not have. */
internal fun initialsOf(name: String): String =
    name.split(' ', '.', '-', '_').filter { it.isNotBlank() }.take(2)
        .map { it.first().uppercaseChar() }.joinToString("")

/** The day the server names, as this device writes a date; empty when it names no day. */
internal fun dayOf(stamp: String): String {
    val day = stamp.take(10)
    if (day.length < 10) return ""
    return runCatching { LocalDate.parse(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrDefault(day)
}
