package org.evsyukov.perigee.engine

/**
 * Where the engine reports what it does, for the app's diagnostic build: the settings applied, the
 * bitmaps drawn, and the stages of the native calls that lay out and draw. Only numbers, modes and
 * names made here are reported, never text of a book, its path or an xpointer.
 *
 * [sink] is null in every other build: nothing is reported, and a report costs a null check.
 */
object EngineTrace {
    fun interface Sink {
        /** [name] is the event, [fields] its `key=value` pairs separated by spaces. Must not throw. */
        fun event(name: String, fields: String)
    }

    private var loaded = false

    /** Set before the engine is first used, or later: the native library is told either way. */
    @Volatile
    var sink: Sink? = null
        set(value) = synchronized(this) {
            field = value
            if (loaded) NativeEngine.setTracing(value != null)
        }

    /** What the engine and the screen both call a bitmap: its identity hash, in practice one per bitmap. */
    fun idOf(bitmap: Any): String = Integer.toHexString(System.identityHashCode(bitmap))

    /** The native library is loaded: it reports its stages from now on if there is a sink. */
    internal fun loaded() = synchronized(this) {
        loaded = true
        if (sink != null) NativeEngine.setTracing(true)
    }

    /**
     * The stages of `applySettings` and `renderPage` in the native library, in the order of its `Stage`,
     * each with the names of the two numbers it comes with; null for a number that means nothing there.
     */
    private val stages = arrayOf(
        Triple("native.apply.begin", "aa", "hint"),
        Triple("native.apply.props", "aa_mode", "hint_mode"),
        Triple("native.apply.laid_out", null, null),
        Triple("native.apply.end", null, null),
        Triple("native.render.locked", "w", "h"),
        Triple("native.render.laid_out", null, null),
        Triple("native.render.drawn", null, null),
        Triple("native.render.unlocked", null, null),
    )

    internal fun stage(stage: Int, a: Int, b: Int) {
        val sink = sink ?: return
        val (name, first, second) = stages.getOrNull(stage) ?: Triple("native.stage_$stage", "a", "b")
        sink.event(name, listOfNotNull(first?.let { "$it=$a" }, second?.let { "$it=$b" }).joinToString(" "))
    }
}
