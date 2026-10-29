package org.evsyukov.perigee.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import java.time.LocalTime
import kotlinx.coroutines.delay
import org.evsyukov.perigee.data.ReaderPrefs

/**
 * The warm filter: the page is multiplied by a warm colour during the hours the reader chose. It is a
 * matter of comfort only: blue-light filtering has no proven effect on eye strain or sleep, and the
 * app does not claim one.
 */
internal object Warmth {
    /** The tint at the strongest step: the page keeps its red and loses some green and more blue. */
    private const val FULL_GREEN = 178
    private const val FULL_BLUE = 102

    /** Whether [minute] of the day falls between [from] and [to]; the hours may run over midnight. */
    fun isOn(from: Int, to: Int, minute: Int): Boolean =
        if (from <= to) minute in from until to else minute >= from || minute < to

    /** The colour the page is multiplied by at [minute] of the day; null when the filter is off then. */
    fun tintAt(prefs: ReaderPrefs, minute: Int): Color? {
        if (!prefs.warmFilter || !isOn(prefs.warmFromMinute, prefs.warmToMinute, minute)) return null
        val share = prefs.warmStrength.coerceIn(ReaderPrefs.WARM_STRENGTHS).toFloat() / ReaderPrefs.WARM_STRENGTHS.last
        fun channel(full: Int) = (255 - share * (255 - full)) / 255f
        return Color(1f, channel(FULL_GREEN), channel(FULL_BLUE))
    }
}

/** The minute of the day, kept current while it is looked at. */
@Composable
internal fun minuteOfDay(): State<Int> = produceState(LocalTime.now().toSecondOfDay() / 60) {
    while (true) {
        val now = LocalTime.now()
        delay((60 - now.second) * 1000L - now.nano / 1_000_000)
        value = LocalTime.now().toSecondOfDay() / 60
    }
}
