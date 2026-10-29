package org.evsyukov.perigee.reader

/**
 * "Darker without flicker": many OLED screens flicker at low backlight, and how much that bothers a
 * reader varies widely. With the option on, the brightness gesture lowers the window's brightness only
 * down to [FLOOR] of its range; below that the backlight stays there and the app darkens the page
 * itself, up to [MAX_DIM]. Community practice for readers sensitive to the flicker, not research.
 */
internal object Dimming {
    const val FLOOR = 0.3f
    const val MAX_DIM = 0.8f

    /**
     * The window brightness and the share of the page's light the app takes away for a gesture [level];
     * [lowest] is the lowest level the gesture reaches. A null level leaves both to the system.
     */
    fun split(level: Float?, enabled: Boolean, lowest: Float): Pair<Float?, Float> {
        if (level == null || !enabled || level >= FLOOR) return level to 0f
        return FLOOR to MAX_DIM * ((FLOOR - level) / (FLOOR - lowest)).coerceIn(0f, 1f)
    }
}
