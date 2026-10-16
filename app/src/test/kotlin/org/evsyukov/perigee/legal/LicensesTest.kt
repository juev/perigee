package org.evsyukov.perigee.legal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LicensesTest {
    /** The tree the app is built from; tests run in the directory of their module. */
    private val root = File("..").canonicalFile

    @Test
    fun textIsCutAtEmptyLines() {
        assertEquals(listOf("One\ntwo", "  Three", "Four"), paragraphs("One\r\ntwo\r\n\r\n\n  Three\n \t\nFour\n"))
        assertEquals(emptyList<String>(), paragraphs("\n\n"))
    }

    /**
     * A library added to the engine's build is given to the reader with the
     * app: its license has to come along and the notice has to name it.
     */
    @Test
    fun everyLibraryTheEngineIsBuiltFromHasItsLicenseAndIsNamedInTheNotice() {
        val fetched = Regex("""perigee_fetch\((\w+)""")
            .findAll(File(root, "engine/src/main/cpp/cmake/deps.cmake").readText()).map { it.groupValues[1] }.toList()
        assertTrue("the build recipe was read", fetched.size >= 10)
        val names = mapOf(
            "ZSTD" to "Zstandard", "PNG" to "libpng", "JPEG" to "libjpeg-turbo", "WEBP" to "libwebp",
            "FREETYPE" to "FreeType", "HARFBUZZ" to "HarfBuzz", "FRIBIDI" to "FriBidi", "UNIBREAK" to "libunibreak",
            "UTF8PROC" to "utf8proc", "XXHASH" to "xxHash",
        )
        val notice = File(root, "NOTICE").readText()
        for (library in fetched) {
            val name = names[library] ?: error("$library is built into the engine and has no license in the app")
            assertTrue("$name has a license text", File(root, "licenses/$name.txt").length() > 0)
            assertTrue("$name is named in the notice", notice.contains(name))
        }
    }

    /** A font that comes with the app comes with its license, and the notice names it. */
    @Test
    fun everyFontThatComesWithTheAppHasItsLicenseAndIsNamedInTheNotice() {
        val files = File(root, "engine/src/main/assets/crengine/fonts").list().orEmpty().filter { it.endsWith(".ttf") }
        assertTrue("the fonts were found", files.size >= 8)
        val families = mapOf(
            "Comfortaa" to "Comfortaa", "DroidSerif" to "Droid Serif", "Literata" to "Literata", "Merriweather" to "Merriweather",
            "NotoSerif" to "Noto Serif", "PTSans" to "PT Sans", "PTSerif" to "PT Serif", "Roboto" to "Roboto",
        )
        val notice = File(root, "NOTICE").readText()
        for (file in files) {
            val family = families[file.substringBefore('-')] ?: error("$file comes with the app and has no license in it")
            val license = if (family == "Droid Serif") "Apache-2.0" else family
            assertTrue("$family has a license text", File(root, "licenses/$license.txt").length() > 0)
            assertTrue("$family is named in the notice", notice.contains("\n$family ".toRegex()))
        }
    }

    /** The notice names the version of a library; the build is where the version is set. */
    @Test
    fun noticeNamesTheVersionsTheBuildFetches() {
        val recipe = File(root, "engine/src/main/cpp/cmake/deps.cmake").readText()
        val notice = File(root, "NOTICE").readText()
        val versions = mapOf(
            "Zstandard" to "zstd-", "libpng" to "libpng-", "libjpeg-turbo" to "libjpeg-turbo-", "libwebp" to "libwebp-",
            "HarfBuzz" to "harfbuzz-", "GNU FriBidi" to "fribidi-", "libunibreak" to "libunibreak-",
            "utf8proc" to "utf8proc-", "xxHash" to "xxHash-",
        )
        for ((name, prefix) in versions) {
            val version = Regex("""\s${Regex.escape(prefix)}(\d+(?:\.\d+)+)\)""").find(recipe)?.groupValues?.get(1)
                ?: error("no version of $name in the build recipe")
            assertTrue("the notice says $name $version", notice.contains("$name $version"))
        }
    }
}
