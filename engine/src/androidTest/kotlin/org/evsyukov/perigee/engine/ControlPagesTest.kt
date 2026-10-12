package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pages a person has looked at and accepted, kept as pictures. A change in the
 * engine, its data files or the way settings reach it that moves a line shows
 * up here. The pages are set in the font shipped with the tests, so they do
 * not depend on the fonts of the device.
 *
 * To accept a new look: run the tests, take the pictures from
 * `engine/build/outputs/connected_android_test_additional_output/` and put them
 * into `src/androidTest/assets/golden/`.
 */
@RunWith(AndroidJUnit4::class)
class ControlPagesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val dir: File = File(context.cacheDir, "books").apply { mkdirs() }

    // The reading settings of the reference screen: justified with hyphenation,
    // loose lines, a paragraph indent and narrow margins.
    private val settings = EngineSettings(
        fontFace = TestBooks.TEST_FONT,
        fontSize = 34,
        lineSpacingPercent = 120,
        marginHorizontal = 20,
        marginVertical = 28,
        textAlign = TextAlign.Justify,
        hyphenation = true,
        paragraphSpacingPercent = 20,
        paragraphIndent = true,
    )

    @Before
    fun registerTestFont() {
        runBlocking { Engine.registerFonts(context, TestBooks.fonts(context)) }
    }

    @Test
    fun russianPage() = check("russian", TestBooks.russianFb2(dir), settings)

    @Test
    fun englishPage() = check("english", TestBooks.epub(dir), settings)

    @Test
    fun sepiaPage() = check("russian-sepia", TestBooks.russianFb2(dir), settings.copy(colors = PageColors.Sepia))

    @Test
    fun raggedPage() =
        check("russian-ragged", TestBooks.russianFb2(dir), settings.copy(textAlign = TextAlign.Left, hyphenation = false))

    @Test
    fun twoPages() = check("english-two-pages", TestBooks.epub(dir), settings.copy(twoPages = true), HEIGHT, WIDTH)

    private fun check(name: String, book: File, settings: EngineSettings, width: Int = WIDTH, height: Int = HEIGHT) {
        val page = EngineDocument.open(context, book, width, height, settings, DENSITY).use { doc ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also(doc::renderPage)
        }
        save(name, page)
        val golden = try {
            instrumentation.context.assets.open("golden/$name.png").use(BitmapFactory::decodeStream)
        } catch (e: FileNotFoundException) {
            fail("no accepted picture for '$name' yet; the page as drawn now is in the test output")
            return
        }
        assertEquals("width of $name", golden.width, page.width)
        assertEquals("height of $name", golden.height, page.height)
        val differing = differingPixels(golden, page)
        assertTrue(
            "$name differs from the accepted picture in $differing pixels; the page as drawn now is in the test output",
            differing <= width * height / TOLERATED_SHARE,
        )
    }

    /** Pixels that differ visibly; rounding in anti-aliasing is not counted. */
    private fun differingPixels(a: Bitmap, b: Bitmap): Int {
        val size = a.width * a.height
        val left = IntArray(size).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val right = IntArray(size).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        return (0 until size).count { i ->
            (0..16 step 8).any { shift -> abs(((left[i] shr shift) and 0xFF) - ((right[i] shr shift) and 0xFF)) > 48 }
        }
    }

    /** AGP copies this directory off the device after the run. */
    private fun save(name: String, bitmap: Bitmap) {
        val out = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        File(out).mkdirs()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val DENSITY = 320

        // One pixel in a thousand may differ: a shifted line changes far more.
        const val TOLERATED_SHARE = 1000
    }
}
