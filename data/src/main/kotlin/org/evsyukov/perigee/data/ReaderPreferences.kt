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

/** Page colour schemes, in the order the settings list them. */
enum class ColorScheme(val dark: Boolean) {
    Day(false),
    Night(true),
    /** Light text on pure black, for OLED screens: black pixels stay off. */
    Oled(true),
    Sepia(false),
}

enum class Alignment { Justify, Left, Center, Right }

/** How the reader likes books shown. One set for all books, as in ReadEra. Sizes are in dp. */
data class ReaderPrefs(
    val pageTurn: PageTurn = PageTurn.Horizontal,
    val tapScheme: TapScheme = TapScheme.LeftRight,
    val colorScheme: ColorScheme = ColorScheme.Day,
    /** The light scheme the day/night switch returns to from a dark one. */
    val dayScheme: ColorScheme = ColorScheme.Day,
    /** The dark scheme the day/night switch goes to from a light one. */
    val nightScheme: ColorScheme = ColorScheme.Night,
    /** The light around the screen chooses between [dayScheme] and [nightScheme]. */
    val schemeByLight: Boolean = false,
    val fontFace: String = "Roboto",
    val fontSize: Int = 18,
    val fontWeight: Int = 400,
    /** How dark letters are drawn, 0 as the font has them; the steps are `EngineSettings.CONTRASTS`. */
    val contrast: Int = 0,
    /** The opening letters of each word in bold. */
    val boldWordStarts: Boolean = false,
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
    /** Below a floor the gesture darkens the page instead of the backlight, against OLED flicker. */
    val dimBelowFlicker: Boolean = false,
    val dayNightByCorner: Boolean = true,
    val bookmarkByCorner: Boolean = true,
    /** How long the screen stays on after the last touch or turn: [SCREEN_ON_SYSTEM], [SCREEN_ON_ALWAYS] or minutes. */
    val screenOnMinutes: Int = 10,
    /** The page is tinted warm between [warmFromMinute] and [warmToMinute], minutes of the day. */
    val warmFilter: Boolean = false,
    val warmFromMinute: Int = 22 * 60,
    val warmToMinute: Int = 7 * 60,
    /** How warm the tint is, one of [WARM_STRENGTHS]. */
    val warmStrength: Int = 3,
) {
    /** Choosing a scheme by hand; it becomes the one the day/night switch goes to on its side, light or dark. */
    fun withScheme(scheme: ColorScheme): ReaderPrefs =
        if (scheme.dark) copy(colorScheme = scheme, nightScheme = scheme) else copy(colorScheme = scheme, dayScheme = scheme)

    /** The day/night switch: from a dark scheme to the last light one, and from a light one to the last dark one. */
    fun toggledNight(): ReaderPrefs =
        copy(colorScheme = if (colorScheme.dark) dayScheme else nightScheme)

    companion object {
        val FONT_SIZES = 10..40
        val LINE_SPACINGS = 80..200 step 10
        val PARAGRAPH_SPACINGS = 0..100 step 10

        /** The screen goes off when the system says. */
        const val SCREEN_ON_SYSTEM = 0

        /** The screen stays on while the book is open. */
        const val SCREEN_ON_ALWAYS = -1
        val SCREEN_ON_CHOICES = listOf(SCREEN_ON_SYSTEM, 2, 5, 10, 20, SCREEN_ON_ALWAYS)

        /** Times the warm filter can start or end at: each hour of the day, in minutes. */
        val WARM_TIMES = (0 until 24).map { it * 60 }
        val WARM_STRENGTHS = 1..5
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
            nightScheme = p[NIGHT_SCHEME].enumOr(d.nightScheme),
            schemeByLight = p[SCHEME_BY_LIGHT] ?: d.schemeByLight,
            fontFace = p[FONT_FACE] ?: d.fontFace,
            fontSize = p[FONT_SIZE] ?: d.fontSize,
            fontWeight = p[FONT_WEIGHT] ?: d.fontWeight,
            contrast = p[CONTRAST] ?: d.contrast,
            boldWordStarts = p[BOLD_WORD_STARTS] ?: d.boldWordStarts,
            lineSpacingPercent = p[LINE_SPACING] ?: d.lineSpacingPercent,
            alignment = p[ALIGNMENT].enumOr(d.alignment),
            hyphenation = p[HYPHENATION] ?: d.hyphenation,
            paragraphSpacingPercent = p[PARAGRAPH_SPACING] ?: d.paragraphSpacingPercent,
            paragraphIndent = p[PARAGRAPH_INDENT] ?: d.paragraphIndent,
            pageMargins = p[PAGE_MARGINS] ?: d.pageMargins,
            twoPagesInLandscape = p[TWO_PAGES] ?: d.twoPagesInLandscape,
            volumeKeysTurnPages = p[VOLUME_KEYS] ?: d.volumeKeysTurnPages,
            brightnessGesture = p[BRIGHTNESS_GESTURE] ?: d.brightnessGesture,
            dimBelowFlicker = p[DIM_BELOW_FLICKER] ?: d.dimBelowFlicker,
            brightness = p[BRIGHTNESS],
            dayNightByCorner = p[DAY_NIGHT_CORNER] ?: d.dayNightByCorner,
            bookmarkByCorner = p[BOOKMARK_CORNER] ?: d.bookmarkByCorner,
            screenOnMinutes = p[SCREEN_ON] ?: d.screenOnMinutes,
            warmFilter = p[WARM_FILTER] ?: d.warmFilter,
            warmFromMinute = p[WARM_FROM] ?: d.warmFromMinute,
            warmToMinute = p[WARM_TO] ?: d.warmToMinute,
            warmStrength = p[WARM_STRENGTH] ?: d.warmStrength,
        )
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, v: ReaderPrefs) {
        p[PAGE_TURN] = v.pageTurn.name
        p[TAP_SCHEME] = v.tapScheme.name
        p[COLOR_SCHEME] = v.colorScheme.name
        p[DAY_SCHEME] = v.dayScheme.name
        p[NIGHT_SCHEME] = v.nightScheme.name
        p[SCHEME_BY_LIGHT] = v.schemeByLight
        p[FONT_FACE] = v.fontFace
        p[FONT_SIZE] = v.fontSize
        p[FONT_WEIGHT] = v.fontWeight
        p[CONTRAST] = v.contrast
        p[BOLD_WORD_STARTS] = v.boldWordStarts
        p[LINE_SPACING] = v.lineSpacingPercent
        p[ALIGNMENT] = v.alignment.name
        p[HYPHENATION] = v.hyphenation
        p[PARAGRAPH_SPACING] = v.paragraphSpacingPercent
        p[PARAGRAPH_INDENT] = v.paragraphIndent
        p[PAGE_MARGINS] = v.pageMargins
        p[TWO_PAGES] = v.twoPagesInLandscape
        p[VOLUME_KEYS] = v.volumeKeysTurnPages
        p[BRIGHTNESS_GESTURE] = v.brightnessGesture
        p[DIM_BELOW_FLICKER] = v.dimBelowFlicker
        if (v.brightness == null) p.remove(BRIGHTNESS) else p[BRIGHTNESS] = v.brightness
        p[DAY_NIGHT_CORNER] = v.dayNightByCorner
        p[BOOKMARK_CORNER] = v.bookmarkByCorner
        p[SCREEN_ON] = v.screenOnMinutes
        p[WARM_FILTER] = v.warmFilter
        p[WARM_FROM] = v.warmFromMinute
        p[WARM_TO] = v.warmToMinute
        p[WARM_STRENGTH] = v.warmStrength
    }

    /** A value written by a newer version that this one does not know reads as the default. */
    private inline fun <reified E : Enum<E>> String?.enumOr(default: E): E =
        enumValues<E>().firstOrNull { it.name == this } ?: default

    companion object {
        private val PAGE_TURN = stringPreferencesKey("page_turn")
        private val TAP_SCHEME = stringPreferencesKey("tap_scheme")
        private val COLOR_SCHEME = stringPreferencesKey("color_scheme")
        private val DAY_SCHEME = stringPreferencesKey("day_scheme")
        private val NIGHT_SCHEME = stringPreferencesKey("night_scheme")
        private val SCHEME_BY_LIGHT = booleanPreferencesKey("scheme_by_light")
        private val FONT_FACE = stringPreferencesKey("font_face")
        private val FONT_SIZE = intPreferencesKey("font_size")
        private val FONT_WEIGHT = intPreferencesKey("font_weight")
        private val CONTRAST = intPreferencesKey("contrast")
        private val BOLD_WORD_STARTS = booleanPreferencesKey("bold_word_starts")
        private val LINE_SPACING = intPreferencesKey("line_spacing")
        private val ALIGNMENT = stringPreferencesKey("alignment")
        private val HYPHENATION = booleanPreferencesKey("hyphenation")
        private val PARAGRAPH_SPACING = intPreferencesKey("paragraph_spacing")
        private val PARAGRAPH_INDENT = booleanPreferencesKey("paragraph_indent")
        private val PAGE_MARGINS = booleanPreferencesKey("page_margins")
        private val TWO_PAGES = booleanPreferencesKey("two_pages")
        private val VOLUME_KEYS = booleanPreferencesKey("volume_keys")
        private val BRIGHTNESS_GESTURE = booleanPreferencesKey("brightness_gesture")
        private val DIM_BELOW_FLICKER = booleanPreferencesKey("dim_below_flicker")
        private val BRIGHTNESS = floatPreferencesKey("brightness")
        private val DAY_NIGHT_CORNER = booleanPreferencesKey("day_night_corner")
        private val BOOKMARK_CORNER = booleanPreferencesKey("bookmark_corner")
        private val SCREEN_ON = intPreferencesKey("screen_on_minutes")
        private val WARM_FILTER = booleanPreferencesKey("warm_filter")
        private val WARM_FROM = intPreferencesKey("warm_from_minute")
        private val WARM_TO = intPreferencesKey("warm_to_minute")
        private val WARM_STRENGTH = intPreferencesKey("warm_strength")

        @Volatile
        private var instance: ReaderPreferences? = null

        fun get(context: Context): ReaderPreferences = instance ?: synchronized(this) {
            instance ?: ReaderPreferences(context.applicationContext.readerStore).also { instance = it }
        }
    }
}
