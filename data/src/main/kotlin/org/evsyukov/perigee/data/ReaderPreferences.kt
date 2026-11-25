package org.evsyukov.perigee.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONException
import org.json.JSONObject

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

/** How the strokes of the letters are snapped to the pixel grid. */
enum class Hinting {
    /** The engine's own autohinter, as the app has always drawn. */
    Auto,

    /** The instructions the font carries. */
    Native,

    /** No snapping: the outlines as drawn, the softest edges. */
    Off,
}

/** How Read aloud speaks: the device's system TTS or an OpenAI-format server found by probing. */
enum class ReadAloudEngine { Device, External }

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
    /** The reader's face is drawn over a face the book names; off leaves the book its own. */
    val overrideBookFont: Boolean = true,
    val fontSize: Int = 18,
    val fontWeight: Int = 400,
    /** How dark letters are drawn, 0 as the font has them; the steps are `EngineSettings.CONTRASTS`. */
    val contrast: Int = 0,
    /** The opening third of words of five or more letters in 600, the rest in 300. */
    val boldWordStarts: Boolean = false,
    /** Grey edges on the strokes; off draws every pixel of a letter black or white. */
    val antialiasing: Boolean = true,
    val hinting: Hinting = Hinting.Auto,
    /** Full shaping with the face's ligatures for every book, not only for the scripts that need it. */
    val ligatures: Boolean = false,
    /** Letters placed between pixels, 0 (whole pixels) up to the last of [FRACTIONAL_STEPS]. */
    val fractionalPositioning: Int = 0,
    /** Code and preformatted text in percent of the size the engine gives it, one of [MONOSPACE_SIZES]. */
    val monospaceSizePercent: Int = 100,
    /** Footnotes at the size of the main text; off keeps them compact, smaller than the text. */
    val fullSizeFootnotes: Boolean = false,
    val lineSpacingPercent: Int = 120,
    val alignment: Alignment = Alignment.Justify,
    val hyphenation: Boolean = true,
    val paragraphSpacingPercent: Int = 20,
    val paragraphIndent: Boolean = true,
    /** Wide margins around the text; off leaves a narrow strip. */
    val pageMargins: Boolean = false,
    val twoPagesInLandscape: Boolean = true,
    val volumeKeysTurnPages: Boolean = true,
    val highlightSpokenText: Boolean = false,
    val readAloudEngine: ReadAloudEngine = ReadAloudEngine.Device,
    /** Base address of an OpenAI-format speech server; the endpoint, model and response format are found by probing. */
    val externalTtsUrl: String = "",
    /** Sent as `Authorization: Bearer` when not blank. */
    val externalTtsKey: String = "",
    /** The voice picked from the server's list; empty lets the server's first one speak. */
    val externalTtsVoice: String = "",
    /** The last successful probe as JSON: base, path, model, voice, kind, field. Empty means not detected. */
    val externalTtsDetected: String = "",
    val readingRuler: Boolean = false,
    /** Percent of available vertical travel (0 = top, 100 = bottom). */
    val rulerPosition: Int = 50,
    /** Percent of the visible page height. */
    val rulerHeight: Int = 10,
    val rulerDimOutside: Boolean = false,
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
    /** CSS the owner wrote, applied after everything the app sets; empty means the shipped look. */
    val userCss: String = "",
) {
    /** Choosing a scheme by hand; it becomes the one the day/night switch goes to on its side, light or dark. */
    fun withScheme(scheme: ColorScheme): ReaderPrefs =
        if (scheme.dark) copy(colorScheme = scheme, nightScheme = scheme) else copy(colorScheme = scheme, dayScheme = scheme)

    /** The day/night switch: from a dark scheme to the last light one, and from a light one to the last dark one. */
    fun toggledNight(): ReaderPrefs =
        copy(colorScheme = if (colorScheme.dark) dayScheme else nightScheme)

    companion object {
        /**
         * Up to 48 dp (issue #154): chosen with headroom after measuring every shipped face at up to
         * 640 dpi against the engine's 256 px cap. Not a clinical or arbitrary-book-font limit.
         */
        val FONT_SIZES = 10..48
        val LINE_SPACINGS = 80..200 step 10
        val PARAGRAPH_SPACINGS = 0..100 step 10
        val FRACTIONAL_STEPS = 0..3
        val MONOSPACE_SIZES = 60..140 step 10
        val RULER_POSITIONS = 0..100
        val RULER_HEIGHTS = 5..30
        const val RULER_STEP = 5

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

    /** Null means the stored catalog cannot be read by this version. */
    val appearanceProfiles: Flow<List<String>?> = store.data.map { p ->
        try { readProfiles(p).keys().asSequence().sorted().toList() }
        catch (_: JSONException) { null }
    }

    val canUndoAppearanceProfile: Flow<Boolean> = store.data.map { it[APPEARANCE_UNDO] != null }

    suspend fun renameAppearanceProfile(name: String, replacement: String) {
        val title = profileName(replacement)
        store.edit { stored ->
            val profiles = readProfiles(stored)
            val snapshot = profiles.getJSONObject(name)
            if (title != name) {
                require(!profiles.has(title))
                profiles.remove(name)
                writeProfiles(stored, profiles.put(title, snapshot))
            }
        }
    }

    suspend fun updateAppearanceProfile(name: String) {
        store.edit { stored ->
            val profiles = readProfiles(stored)
            require(profiles.has(name))
            writeProfiles(stored, profiles.put(name, captureAppearance(stored)))
        }
    }

    suspend fun deleteAppearanceProfile(name: String) {
        store.edit { stored ->
            val profiles = readProfiles(stored)
            require(profiles.has(name))
            profiles.remove(name)
            writeProfiles(stored, profiles)
        }
    }

    suspend fun undoAppearanceProfile(availableFaces: Set<String>): String? {
        var missingFace: String? = null
        store.edit { stored ->
            val raw = requireNotNull(stored[APPEARANCE_UNDO])
            if (raw.length > PROFILE_STORAGE_LIMIT) throw JSONException("Appearance snapshot too large")
            val envelope = JSONObject(raw)
            if (envelope.get("version") != PROFILE_VERSION) throw JSONException("Unsupported appearance snapshot")
            val snapshot = envelope.getJSONObject("snapshot")
            validateAppearance(snapshot)
            missingFace = restoreAppearance(stored, snapshot, availableFaces)
            stored.remove(APPEARANCE_UNDO)
        }
        return missingFace
    }

    suspend fun saveAppearanceProfile(name: String) {
        val title = profileName(name)
        store.edit { stored ->
            val profiles = readProfiles(stored)
            require(!profiles.has(title) && profiles.length() < PROFILE_LIMIT)
            writeProfiles(stored, profiles.put(title, captureAppearance(stored)))
        }
    }

    /** Applies the edited guide fields and captures a named profile in the same transaction. */
    suspend fun saveReadingSetup(name: String, before: ReaderPrefs, chosen: ReaderPrefs): Boolean {
        val title = profileName(name)
        var saved = false
        store.edit { stored ->
            val profiles = readProfiles(stored)
            require(!profiles.has(title) && profiles.length() < PROFILE_LIMIT)
            val current = read(stored)
            var conflict = false
            fun <T> field(original: T, selected: T, latest: T): T {
                if (selected == original) return latest
                if (latest != original && latest != selected) conflict = true
                return selected
            }
            val updated = current.copy(
                fontSize = field(before.fontSize, chosen.fontSize, current.fontSize),
                pageMargins = field(before.pageMargins, chosen.pageMargins, current.pageMargins),
                twoPagesInLandscape = field(before.twoPagesInLandscape, chosen.twoPagesInLandscape, current.twoPagesInLandscape),
                lineSpacingPercent = field(before.lineSpacingPercent, chosen.lineSpacingPercent, current.lineSpacingPercent),
                colorScheme = field(before.colorScheme, chosen.colorScheme, current.colorScheme),
                dayScheme = field(before.dayScheme, chosen.dayScheme, current.dayScheme),
                nightScheme = field(before.nightScheme, chosen.nightScheme, current.nightScheme),
            )
            if (!conflict) {
                // Validation failure aborts the DataStore transaction, including the active settings.
                val previous = captureAppearance(stored)
                validateAppearance(previous)
                write(stored, updated)
                writeProfiles(stored, profiles.put(title, captureAppearance(stored)))
                stored[APPEARANCE_UNDO] = JSONObject().put("version", PROFILE_VERSION).put("snapshot", previous).toString()
                saved = true
            }
        }
        return saved
    }

    /** Returns the requested family when it was unavailable and the current face was kept. */
    suspend fun applyAppearanceProfile(name: String, availableFaces: Set<String>): String? {
        var missingFace: String? = null
        store.edit { stored ->
            val profile = readProfiles(stored).getJSONObject(name)
            val before = captureAppearance(stored)
            validateAppearance(before)
            stored[APPEARANCE_UNDO] = JSONObject().put("version", PROFILE_VERSION).put("snapshot", before).toString()
            missingFace = restoreAppearance(stored, profile, availableFaces)
        }
        return missingFace
    }

    private fun profileName(name: String) = name.trim().also {
        require(it.isNotEmpty() && it.length <= PROFILE_NAME_LIMIT && it.none { c -> c.isISOControl() })
    }

    private fun captureAppearance(stored: Preferences): JSONObject {
        val active = mutablePreferencesOf().also { write(it, read(stored)) }.asMap()
        return JSONObject().apply {
            for (key in APPEARANCE_KEYS) put(key.name, checkNotNull(active[key]))
        }
    }

    private fun restoreAppearance(stored: MutablePreferences, profile: JSONObject, availableFaces: Set<String>): String? {
        var missingFace: String? = null
        for (key in APPEARANCE_KEYS) {
            when (val value = profile.get(key.name)) {
                is String -> if (key != FONT_FACE || value in availableFaces) {
                    stored[stringPreferencesKey(key.name)] = value
                } else missingFace = value
                is Int -> stored[intPreferencesKey(key.name)] = value
                is Boolean -> stored[booleanPreferencesKey(key.name)] = value
            }
        }
        return missingFace
    }

    private fun readProfiles(stored: Preferences): JSONObject {
        val raw = stored[APPEARANCE_PROFILES] ?: return JSONObject()
        if (raw.length > PROFILE_STORAGE_LIMIT) throw JSONException("Profile catalog too large")
        val envelope = JSONObject(raw)
        if (envelope.get("version") != PROFILE_VERSION) throw JSONException("Unsupported profile catalog")
        val profiles = envelope.getJSONObject("profiles")
        if (profiles.length() > PROFILE_LIMIT) throw JSONException("Too many profiles")
        for (name in profiles.keys()) {
            if (name.isBlank() || name.length > PROFILE_NAME_LIMIT || name.any { it.isISOControl() }) {
                throw JSONException("Invalid profile name")
            }
            validateAppearance(profiles.getJSONObject(name))
        }
        return profiles
    }

    private fun validateAppearance(profile: JSONObject) {
        val defaults = mutablePreferencesOf().also { write(it, ReaderPrefs()) }.asMap()
        for (key in APPEARANCE_KEYS) {
            val value = profile.get(key.name)
            if (defaults[key]?.javaClass != value.javaClass || !validAppearanceValue(key, value)) {
                throw JSONException("Invalid appearance field")
            }
        }
    }

    private fun validAppearanceValue(key: Preferences.Key<*>, value: Any): Boolean = when (key) {
        FONT_FACE -> (value as String).let { it.isNotBlank() && it.length <= 160 && it.none { c -> c.isISOControl() } }
        COLOR_SCHEME -> ColorScheme.entries.any { it.name == value }
        DAY_SCHEME -> ColorScheme.entries.any { !it.dark && it.name == value }
        NIGHT_SCHEME -> ColorScheme.entries.any { it.dark && it.name == value }
        ALIGNMENT -> Alignment.entries.any { it.name == value }
        HINTING -> Hinting.entries.any { it.name == value }
        FONT_SIZE -> value as Int in ReaderPrefs.FONT_SIZES
        FONT_WEIGHT -> value as Int in 100..950
        CONTRAST -> value as Int in 0..4
        FRACTIONAL_POSITIONING -> value as Int in ReaderPrefs.FRACTIONAL_STEPS
        MONOSPACE_SIZE -> value as Int in ReaderPrefs.MONOSPACE_SIZES
        LINE_SPACING -> value as Int in ReaderPrefs.LINE_SPACINGS
        PARAGRAPH_SPACING -> value as Int in ReaderPrefs.PARAGRAPH_SPACINGS
        else -> true
    }

    private fun writeProfiles(stored: MutablePreferences, profiles: JSONObject) {
        val raw = JSONObject().put("version", PROFILE_VERSION).put("profiles", profiles).toString()
        require(raw.length <= PROFILE_STORAGE_LIMIT)
        stored[APPEARANCE_PROFILES] = raw
        readProfiles(stored) // Validate the complete replacement inside the transaction.
    }

    /** The owner's CSS as it was before its last [USER_CSS_KEPT] changes, the latest first: a bad edit can be taken back. */
    val previousUserCss: Flow<List<String>> = store.data.map { p -> PREVIOUS_USER_CSS.mapNotNull { p[it] } }

    suspend fun update(change: (ReaderPrefs) -> ReaderPrefs) {
        store.edit { stored ->
            val old = read(stored)
            val new = change(old)
            write(stored, new)
            if (new.userCss != old.userCss && old.userCss.isNotBlank()) keepPrevious(stored, old.userCss)
        }
    }

    /** Each kept version moves a place down to make room for [css]; the oldest is dropped. */
    private fun keepPrevious(p: MutablePreferences, css: String) {
        for (i in PREVIOUS_USER_CSS.lastIndex downTo 1) {
            p[PREVIOUS_USER_CSS[i - 1]]?.let { p[PREVIOUS_USER_CSS[i]] = it }
        }
        p[PREVIOUS_USER_CSS[0]] = css
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
            overrideBookFont = p[OVERRIDE_BOOK_FONT] ?: d.overrideBookFont,
            fontSize = p[FONT_SIZE] ?: d.fontSize,
            fontWeight = p[FONT_WEIGHT] ?: d.fontWeight,
            contrast = p[CONTRAST] ?: d.contrast,
            boldWordStarts = p[BOLD_WORD_STARTS] ?: d.boldWordStarts,
            antialiasing = p[ANTIALIASING] ?: d.antialiasing,
            hinting = p[HINTING].enumOr(d.hinting),
            ligatures = p[LIGATURES] ?: d.ligatures,
            fractionalPositioning = p[FRACTIONAL_POSITIONING] ?: d.fractionalPositioning,
            monospaceSizePercent = p[MONOSPACE_SIZE] ?: d.monospaceSizePercent,
            fullSizeFootnotes = p[FULL_SIZE_FOOTNOTES] ?: d.fullSizeFootnotes,
            lineSpacingPercent = p[LINE_SPACING] ?: d.lineSpacingPercent,
            alignment = p[ALIGNMENT].enumOr(d.alignment),
            hyphenation = p[HYPHENATION] ?: d.hyphenation,
            paragraphSpacingPercent = p[PARAGRAPH_SPACING] ?: d.paragraphSpacingPercent,
            paragraphIndent = p[PARAGRAPH_INDENT] ?: d.paragraphIndent,
            pageMargins = p[PAGE_MARGINS] ?: d.pageMargins,
            twoPagesInLandscape = p[TWO_PAGES] ?: d.twoPagesInLandscape,
            volumeKeysTurnPages = p[VOLUME_KEYS] ?: d.volumeKeysTurnPages,
            highlightSpokenText = p[HIGHLIGHT_SPOKEN_TEXT] ?: d.highlightSpokenText,
            readAloudEngine = p[READ_ALOUD_ENGINE].enumOr(d.readAloudEngine),
            externalTtsUrl = p[EXTERNAL_TTS_URL] ?: d.externalTtsUrl,
            externalTtsKey = p[EXTERNAL_TTS_KEY] ?: d.externalTtsKey,
            externalTtsVoice = p[EXTERNAL_TTS_VOICE] ?: d.externalTtsVoice,
            externalTtsDetected = p[EXTERNAL_TTS_DETECTED] ?: d.externalTtsDetected,
            readingRuler = p[READING_RULER] ?: d.readingRuler,
            rulerPosition = (p[RULER_POSITION] ?: d.rulerPosition).coerceIn(ReaderPrefs.RULER_POSITIONS),
            rulerHeight = (p[RULER_HEIGHT] ?: d.rulerHeight).coerceIn(ReaderPrefs.RULER_HEIGHTS),
            rulerDimOutside = p[RULER_DIM_OUTSIDE] ?: d.rulerDimOutside,
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
            userCss = p[USER_CSS] ?: d.userCss,
        )
    }

    private fun write(p: MutablePreferences, v: ReaderPrefs) {
        p[PAGE_TURN] = v.pageTurn.name
        p[TAP_SCHEME] = v.tapScheme.name
        p[COLOR_SCHEME] = v.colorScheme.name
        p[DAY_SCHEME] = v.dayScheme.name
        p[NIGHT_SCHEME] = v.nightScheme.name
        p[SCHEME_BY_LIGHT] = v.schemeByLight
        p[FONT_FACE] = v.fontFace
        p[OVERRIDE_BOOK_FONT] = v.overrideBookFont
        p[FONT_SIZE] = v.fontSize
        p[FONT_WEIGHT] = v.fontWeight
        p[CONTRAST] = v.contrast
        p[BOLD_WORD_STARTS] = v.boldWordStarts
        p[ANTIALIASING] = v.antialiasing
        p[HINTING] = v.hinting.name
        p[LIGATURES] = v.ligatures
        p[FRACTIONAL_POSITIONING] = v.fractionalPositioning
        p[MONOSPACE_SIZE] = v.monospaceSizePercent
        p[FULL_SIZE_FOOTNOTES] = v.fullSizeFootnotes
        p[LINE_SPACING] = v.lineSpacingPercent
        p[ALIGNMENT] = v.alignment.name
        p[HYPHENATION] = v.hyphenation
        p[PARAGRAPH_SPACING] = v.paragraphSpacingPercent
        p[PARAGRAPH_INDENT] = v.paragraphIndent
        p[PAGE_MARGINS] = v.pageMargins
        p[TWO_PAGES] = v.twoPagesInLandscape
        p[VOLUME_KEYS] = v.volumeKeysTurnPages
        p[HIGHLIGHT_SPOKEN_TEXT] = v.highlightSpokenText
        p[READ_ALOUD_ENGINE] = v.readAloudEngine.name
        p[EXTERNAL_TTS_URL] = v.externalTtsUrl
        p[EXTERNAL_TTS_KEY] = v.externalTtsKey
        p[EXTERNAL_TTS_VOICE] = v.externalTtsVoice
        p[EXTERNAL_TTS_DETECTED] = v.externalTtsDetected
        p[READING_RULER] = v.readingRuler
        p[RULER_POSITION] = v.rulerPosition.coerceIn(ReaderPrefs.RULER_POSITIONS)
        p[RULER_HEIGHT] = v.rulerHeight.coerceIn(ReaderPrefs.RULER_HEIGHTS)
        p[RULER_DIM_OUTSIDE] = v.rulerDimOutside
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
        p[USER_CSS] = v.userCss
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
        private val OVERRIDE_BOOK_FONT = booleanPreferencesKey("override_book_font")
        private val FONT_SIZE = intPreferencesKey("font_size")
        private val FONT_WEIGHT = intPreferencesKey("font_weight")
        private val CONTRAST = intPreferencesKey("contrast")
        private val BOLD_WORD_STARTS = booleanPreferencesKey("bold_word_starts")
        private val ANTIALIASING = booleanPreferencesKey("antialiasing")
        private val HINTING = stringPreferencesKey("hinting")
        private val LIGATURES = booleanPreferencesKey("ligatures")
        private val FRACTIONAL_POSITIONING = intPreferencesKey("fractional_positioning")
        private val MONOSPACE_SIZE = intPreferencesKey("monospace_size_percent")
        // Absent in an installation from before the choice: it reads as compact, as notes were then.
        private val FULL_SIZE_FOOTNOTES = booleanPreferencesKey("full_size_footnotes")
        // "embedded_fonts", the switch for the fonts a book carries, is no longer read: those fonts are always
        // allowed, and a false an older version stored must not turn them off. The name is not to be reused.
        private val LINE_SPACING = intPreferencesKey("line_spacing")
        private val ALIGNMENT = stringPreferencesKey("alignment")
        private val HYPHENATION = booleanPreferencesKey("hyphenation")
        private val PARAGRAPH_SPACING = intPreferencesKey("paragraph_spacing")
        private val PARAGRAPH_INDENT = booleanPreferencesKey("paragraph_indent")
        private val PAGE_MARGINS = booleanPreferencesKey("page_margins")
        private val TWO_PAGES = booleanPreferencesKey("two_pages")
        private val VOLUME_KEYS = booleanPreferencesKey("volume_keys")
        private val HIGHLIGHT_SPOKEN_TEXT = booleanPreferencesKey("highlight_spoken_text")
        private val READ_ALOUD_ENGINE = stringPreferencesKey("read_aloud_engine")
        private val EXTERNAL_TTS_URL = stringPreferencesKey("external_tts_url")
        private val EXTERNAL_TTS_KEY = stringPreferencesKey("external_tts_key")
        private val EXTERNAL_TTS_VOICE = stringPreferencesKey("external_tts_voice")
        private val EXTERNAL_TTS_DETECTED = stringPreferencesKey("external_tts_detected")
        private val READING_RULER = booleanPreferencesKey("reading_ruler")
        private val RULER_POSITION = intPreferencesKey("ruler_position")
        private val RULER_HEIGHT = intPreferencesKey("ruler_height")
        private val RULER_DIM_OUTSIDE = booleanPreferencesKey("ruler_dim_outside")
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
        private val USER_CSS = stringPreferencesKey("user_css")

        const val PROFILE_LIMIT = 20
        const val PROFILE_NAME_LIMIT = 60
        private const val PROFILE_VERSION = 1
        private const val PROFILE_STORAGE_LIMIT = 32_768
        private val APPEARANCE_PROFILES = stringPreferencesKey("appearance_profiles")
        private val APPEARANCE_UNDO = stringPreferencesKey("appearance_profile_undo")
        private val APPEARANCE_KEYS = listOf(
            COLOR_SCHEME, DAY_SCHEME, NIGHT_SCHEME, FONT_FACE, OVERRIDE_BOOK_FONT, FONT_SIZE, FONT_WEIGHT,
            CONTRAST, BOLD_WORD_STARTS, ANTIALIASING, HINTING, LIGATURES, FRACTIONAL_POSITIONING, MONOSPACE_SIZE,
            FULL_SIZE_FOOTNOTES, LINE_SPACING, ALIGNMENT, HYPHENATION, PARAGRAPH_SPACING, PARAGRAPH_INDENT,
            PAGE_MARGINS, TWO_PAGES,
        )

        /** How many versions of the owner's CSS before the current one are kept. */
        const val USER_CSS_KEPT = 3
        private val PREVIOUS_USER_CSS = (1..USER_CSS_KEPT).map { stringPreferencesKey("user_css_previous_$it") }

        @Volatile
        private var instance: ReaderPreferences? = null

        fun get(context: Context): ReaderPreferences = instance ?: synchronized(this) {
            instance ?: ReaderPreferences(context.applicationContext.readerStore).also { instance = it }
        }
    }
}
