package org.evsyukov.perigee.engine

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Process-wide crengine state: fonts, hyphenation dictionaries, document cache.
 * The engine reads its data from real files, so bundled assets are unpacked once.
 */
object Engine {
    private const val ASSET_ROOT = "crengine"
    private const val CACHE_BYTES = 64L * 1024 * 1024

    /**
     * crengine is not thread-safe and keeps process-wide state (its string
     * allocator, the font manager), so every call into it, for every open book,
     * runs on this one thread. That also keeps layout off the main thread.
     */
    internal val thread: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "crengine") }.asCoroutineDispatcher()

    private var dataDir: File? = null
    private val readingFaces = sortedSetOf<String>()

    /** DOM version given to newly opened books. */
    val domVersion: Int get() = NativeEngine.domVersion()

    /** Font families offered for reading: the device's named families and the registered files. */
    suspend fun fontFaces(context: Context): List<String> = withContext(thread) { facesOnEngineThread(context) }

    /**
     * Adds font files, such as the ones shipped with the app or picked by the
     * reader, and returns the families now offered. Unreadable files are skipped.
     */
    suspend fun registerFonts(context: Context, files: List<File>): List<String> = withContext(thread) {
        init(context)
        addReadingFonts(files)
        readingFaces.toList()
    }

    internal fun facesOnEngineThread(context: Context): List<String> {
        init(context)
        return readingFaces.toList()
    }

    @Synchronized
    internal fun init(context: Context): File {
        dataDir?.let { return it }
        val app = context.applicationContext
        val dir = unpackAssets(app)
        val cache = File(app.cacheDir, "crengine").apply { mkdirs() }
        NativeEngine.init(File(dir, "hyph").path + "/", cache.path, CACHE_BYTES)
        val system = SystemFonts.find()
        addReadingFonts(system.reading)
        // Registered but not offered: fonts of single scripts.
        NativeEngine.registerFonts(system.scripts.map { it.path }.toTypedArray())
        dataDir = dir
        return dir
    }

    /** The families a set of files brings are the ones the engine did not list before. */
    private fun addReadingFonts(files: List<File>) {
        val before = NativeEngine.fontFaces().toSet()
        NativeEngine.registerFonts(files.map { it.path }.toTypedArray())
        readingFaces += NativeEngine.fontFaces().filterNot { it in before }
    }

    internal fun stylesheetFor(file: File): File {
        val dir = checkNotNull(dataDir) { "Engine.init was not called" }
        val name = when (file.extension.lowercase()) {
            "fb2", "fb3", "txt" -> "fb2.css"
            else -> "epub.css"
        }
        return File(dir, name)
    }

    private fun unpackAssets(context: Context): File {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val dir = File(context.filesDir, ASSET_ROOT)
        val stamp = File(dir, ".version")
        if (stamp.exists() && stamp.readText() == version.toString()) return dir
        dir.deleteRecursively()
        copyAssetDir(context, ASSET_ROOT, dir)
        stamp.writeText(version.toString())
        return dir
    }

    private fun copyAssetDir(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            return
        }
        target.mkdirs()
        for (child in children) copyAssetDir(context, "$assetPath/$child", File(target, child))
    }
}
