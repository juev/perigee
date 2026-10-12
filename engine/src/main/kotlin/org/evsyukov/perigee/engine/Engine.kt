package org.evsyukov.perigee.engine

import android.content.Context
import java.io.File

/**
 * Process-wide crengine state: fonts, hyphenation dictionaries, document cache.
 * The engine reads its data from real files, so bundled assets are unpacked once.
 */
object Engine {
    private const val ASSET_ROOT = "crengine"
    private const val CACHE_BYTES = 64L * 1024 * 1024

    private var dataDir: File? = null

    /** DOM version given to newly opened books. */
    val domVersion: Int get() = NativeEngine.domVersion()

    @Synchronized
    fun init(context: Context): File {
        dataDir?.let { return it }
        val app = context.applicationContext
        val dir = unpackAssets(app)
        val cache = File(app.cacheDir, "crengine").apply { mkdirs() }
        NativeEngine.init(
            File(dir, "hyph").path + "/",
            cache.path,
            CACHE_BYTES,
            systemFonts(),
        )
        dataDir = dir
        return dir
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

    private fun systemFonts(): Array<String> =
        File("/system/fonts").listFiles { f -> f.extension.lowercase() in setOf("ttf", "otf") }
            .orEmpty()
            .map { it.path }
            .sorted()
            .toTypedArray()
}
