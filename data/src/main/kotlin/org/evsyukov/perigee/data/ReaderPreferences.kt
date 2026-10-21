package org.evsyukov.perigee.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class PageTurn { Horizontal, Vertical }

/** Which touches turn the page. */
enum class TapScheme {
    /** Right part forward, left part back. */
    LeftRight,

    /** Bottom part forward, top part back. */
    TopBottom,

    /** Any part forward. */
    AnyForward,

    /** Touches do not turn pages; swipes do. */
    Off,
}

enum class ColorScheme { Day, Night, Sepia }

enum class Alignment { Justify, Left, Center, Right }

/** How the reader likes books shown. One set for all books, as in ReadEra. Sizes are in dp. */
data class ReaderPrefs(
    val pageTurn: PageTurn = PageTurn.Horizontal,
    val tapScheme: TapScheme = TapScheme.LeftRight,
    val colorScheme: ColorScheme = ColorScheme.Day,
    /** The scheme the day/night switch returns to from night. */
    val dayScheme: ColorScheme = ColorScheme.Day,
    val fontFace: String = "Roboto",
    val fontSize: Int = 18,
    val fontWeight: Int = 400,
    val lineSpacingPercent: Int = 120,
    val alignment: Alignment = Alignment.Justify,
    val hyphenation: Boolean = true,
    val paragraphSpacingPercent: Int = 20,
    val paragraphIndent: Boolean = true,
    /** Wide margins around the text; off leaves a narrow strip. */
    val pageMargins: Boolean = false,
    val twoPagesInLandscape: Boolean = true,
    val volumeKeysTurnPages: Boolean = true,
    val brightnessGesture: Boolean = false,
    /** Screen brightness 0..1 set by the gesture; null follows the system. */
    val brightness: Float? = null,
    val dayNightByCorner: Boolean = true,
    val bookmarkByCorner: Boolean = true,
    /** How long the screen stays on after the last touch or turn: [SCREEN_ON_SYSTEM], [SCREEN_ON_ALWAYS] or minutes. */
    val screenOnMinutes: Int = 10,
) {
    /** Choosing a scheme by hand; a light one becomes what night switches back to. */
    fun withScheme(scheme: ColorScheme): ReaderPrefs =
        copy(colorScheme = scheme, dayScheme = if (scheme == ColorScheme.Night) dayScheme else scheme)

    /** The day/night switch. */
    fun toggledNight(): ReaderPrefs =
        copy(colorScheme = if (colorScheme == ColorScheme.Night) dayScheme else ColorScheme.Night)

    companion object {
        val FONT_SIZES = 10..40
        val LINE_SPACINGS = 80..200 step 10
        val PARAGRAPH_SPACINGS = 0..100 step 10

        /** The screen goes off when the system says. */
        const val SCREEN_ON_SYSTEM = 0

        /** The screen stays on while the book is open. */
        const val SCREEN_ON_ALWAYS = -1
        val SCREEN_ON_CHOICES = listOf(SCREEN_ON_SYSTEM, 2, 5, 10, 20, SCREEN_ON_ALWAYS)
    }
}

private val Context.readerStore: DataStore<Preferences> by preferencesDataStore("reader")

class ReaderPreferences private constructor(private val store: DataStore<Preferences>) {
    val prefs: Flow<ReaderPrefs> = store.data.map(::read)

    suspend fun update(change: (ReaderPrefs) -> ReaderPrefs) {
        store.edit { stored -> write(stored, change(read(stored))) }
    }

    private fun read(p: Preferences): ReaderPrefs {
        val d = ReaderPrefs()
        return ReaderPrefs(
            pageTurn = p[PAGE_TURN].enumOr(d.pageTurn),
            tapScheme = p[TAP_SCHEME].enumOr(d.tapScheme),
            colorScheme = p[COLOR_SCHEME].enumOr(d.colorScheme),
            dayScheme = p[DAY_SCHEME].enumOr(d.dayScheme),
            fontFace = p[FONT_FACE] ?: d.fontFace,
            fontSize = p[FONT_SIZE] ?: d.fontSize,
            fontWeight = p[FONT_WEIGHT] ?: d.fontWeight,
            lineSpacingPercent = p[LINE_SPACING] ?: d.lineSpacingPercent,
            alignment = p[ALIGNMENT].enumOr(d.alignment),
            hyphenation = p[HYPHENATION] ?: d.hyphenation,
            paragraphSpacingPercent = p[PARAGRAPH_SPACING] ?: d.paragraphSpacingPercent,
            paragraphIndent = p[PARAGRAPH_INDENT] ?: d.paragraphIndent,
            pageMargins = p[PAGE_MARGINS] ?: d.pageMargins,
            twoPagesInLandscape = p[TWO_PAGES] ?: d.twoPagesInLandscape,
            volumeKeysTurnPages = p[VOLUME_KEYS] ?: d.volumeKeysTurnPages,
            brightnessGesture = p[BRIGHTNESS_GESTURE] ?: d.brightnessGesture,
            brightness = p[BRIGHTNESS],
            dayNightByCorner = p[DAY_NIGHT_CORNER] ?: d.dayNightByCorner,
            bookmarkByCorner = p[BOOKMARK_CORNER] ?: d.bookmarkByCorner,
            screenOnMinutes = p[SCREEN_ON] ?: d.screenOnMinutes,
        )
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, v: ReaderPrefs) {
        p[PAGE_TURN] = v.pageTurn.name
        p[TAP_SCHEME] = v.tapScheme.name
        p[COLOR_SCHEME] = v.colorScheme.name
        p[DAY_SCHEME] = v.dayScheme.name
        p[FONT_FACE] = v.fontFace
        p[FONT_SIZE] = v.fontSize
        p[FONT_WEIGHT] = v.fontWeight
        p[LINE_SPACING] = v.lineSpacingPercent
        p[ALIGNMENT] = v.alignment.name
        p[HYPHENATION] = v.hyphenation
        p[PARAGRAPH_SPACING] = v.paragraphSpacingPercent
        p[PARAGRAPH_INDENT] = v.paragraphIndent
        p[PAGE_MARGINS] = v.pageMargins
        p[TWO_PAGES] = v.twoPagesInLandscape
        p[VOLUME_KEYS] = v.volumeKeysTurnPages
        p[BRIGHTNESS_GESTURE] = v.brightnessGesture
        if (v.brightness == null) p.remove(BRIGHTNESS) else p[BRIGHTNESS] = v.brightness
        p[DAY_NIGHT_CORNER] = v.dayNightByCorner
        p[BOOKMARK_CORNER] = v.bookmarkByCorner
        p[SCREEN_ON] = v.screenOnMinutes
    }

    /** A value written by a newer version that this one does not know reads as the default. */
    private inline fun <reified E : Enum<E>> String?.enumOr(default: E): E =
        enumValues<E>().firstOrNull { it.name == this } ?: default

    companion object {
        private val PAGE_TURN = stringPreferencesKey("page_turn")
        private val TAP_SCHEME = stringPreferencesKey("tap_scheme")
        private val COLOR_SCHEME = stringPreferencesKey("color_scheme")
        private val DAY_SCHEME = stringPreferencesKey("day_scheme")
        private val FONT_FACE = stringPreferencesKey("font_face")
        private val FONT_SIZE = intPreferencesKey("font_size")
        private val FONT_WEIGHT = intPreferencesKey("font_weight")
        private val LINE_SPACING = intPreferencesKey("line_spacing")
        private val ALIGNMENT = stringPreferencesKey("alignment")
        private val HYPHENATION = booleanPreferencesKey("hyphenation")
        private val PARAGRAPH_SPACING = intPreferencesKey("paragraph_spacing")
        private val PARAGRAPH_INDENT = booleanPreferencesKey("paragraph_indent")
        private val PAGE_MARGINS = booleanPreferencesKey("page_margins")
        private val TWO_PAGES = booleanPreferencesKey("two_pages")
        private val VOLUME_KEYS = booleanPreferencesKey("volume_keys")
        private val BRIGHTNESS_GESTURE = booleanPreferencesKey("brightness_gesture")
        private val BRIGHTNESS = floatPreferencesKey("brightness")
        private val DAY_NIGHT_CORNER = booleanPreferencesKey("day_night_corner")
        private val BOOKMARK_CORNER = booleanPreferencesKey("bookmark_corner")
        private val SCREEN_ON = intPreferencesKey("screen_on_minutes")

        @Volatile
        private var instance: ReaderPreferences? = null

        fun get(context: Context): ReaderPreferences = instance ?: synchronized(this) {
            instance ?: ReaderPreferences(context.applicationContext.readerStore).also { instance = it }
        }
    }
}
