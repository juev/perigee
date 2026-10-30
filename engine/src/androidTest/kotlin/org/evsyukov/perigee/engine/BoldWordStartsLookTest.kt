package org.evsyukov.perigee.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Research harness for the look of bold word starts, not a shipped test.
 *
 * Renders the same book under the looks being compared, saves one page per look and writes
 * `looks.csv` (page count and ink of the two halves of one long word) next to the pictures. Run it
 * on the emulator, then build a contact sheet from the PNGs:
 *
 * ```
 * JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_SERIAL=emulator-5554 \
 *   ./gradlew :engine:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=org.evsyukov.perigee.engine.BoldWordStartsLookTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class BoldWordStartsLookTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = File(context.cacheDir, "books").apply { mkdirs() }

    /** The look is process-wide: put the shipped one back for the tests that follow. */
    @Before
    @After
    fun resetLook() {
        NativeEngine.setBoldWordStartsLook(0, 700, 0, 0, 0, 1, 0, 100, 100, 100)
    }

    /** A word long enough for the bold and the regular parts to be told apart. */
    private val word = "Железнодорожное"

    @Test
    fun looksAreRenderedAndCounted() {
        val book = longBook(dir)
        val out = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) {
            "run with AGP's additional test output, as connectedAndroidTest does"
        }.let { File(it).apply { mkdirs() } }
        val rows = mutableListOf("name,pageCount,boldInk,tailInk,gap")
        for (look in LOOKS) {
            NativeEngine.setBoldWordStartsLook(
                look.mode, look.boldWeight, look.tailWeight, look.middlePercent,
                look.middleWeight, look.everyNth, look.minWordLength, look.boldSize, look.middleSize, look.tailSize,
            )
            EngineDocument.open(
                context, book, WIDTH, HEIGHT, settings.copy(boldWordStarts = look.boldWordStarts), DENSITY,
                cacheable = false,
            ).use { doc ->
                // LoadDocument lays the book out before the per-document flag is set; lay it out again
                // with the look, or pageCount stays the plain one.
                doc.apply(settings.copy(boldWordStarts = look.boldWordStarts), WIDTH, HEIGHT)
                val pageCount = doc.pageCount
                assertTrue("${look.name} has pages", pageCount > 0)
                val page = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
                doc.renderPage(page)
                val (_, rect) = doc.locate(word)
                val (_, nextRect) = doc.locate("сообщение")
                val middle = rect.left + rect.width() / 2
                val boldInk = inkIn(page, Rect(rect.left, rect.top, middle, rect.bottom))
                val tailInk = inkIn(page, Rect(middle, rect.top, rect.right, rect.bottom))
                val gap = nextRect.left - rect.right
                File(out, "${look.name}.png").outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
                rows += "${look.name},$pageCount,$boldInk,$tailInk,$gap"
            }
        }
        File(out, "looks.csv").writeText(rows.joinToString("\n"))
    }

    /**
     * The page count without the pictures: the same book and look in several fonts. Justified and ragged
     * text break their lines alike, so one alignment is enough; the first run had both and they matched.
     */
    @Test
    fun pageCountsAcrossFonts() {
        val book = longBook(dir)
        val out = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) {
            "run with AGP's additional test output, as connectedAndroidTest does"
        }.let { File(it).apply { mkdirs() } }
        val rows = mutableListOf("font,look,pageCount")
        for (font in listOf("Roboto", "Literata", "PT Serif")) {
            for (look in COUNT_LOOKS) {
                NativeEngine.setBoldWordStartsLook(
                    look.mode, look.boldWeight, look.tailWeight, look.middlePercent,
                    look.middleWeight, look.everyNth, look.minWordLength, look.boldSize, look.middleSize, look.tailSize,
                )
                val lookSettings = settings.copy(fontFace = font, boldWordStarts = look.boldWordStarts)
                EngineDocument.open(context, book, WIDTH, HEIGHT, lookSettings, DENSITY, cacheable = false).use { doc ->
                    doc.apply(lookSettings, WIDTH, HEIGHT)
                    rows += "$font,${look.name},${doc.pageCount}"
                }
            }
        }
        File(out, "page-counts.csv").writeText(rows.joinToString("\n"))
    }

    /**
     * A few pages in the static PT Serif: the engine can synthesise a lighter face, so a thin tail is
     * not limited to variable fonts. See how it looks before deciding whether to use it.
     */
    @Test
    fun ptSerifLooks() {
        val book = longBook(dir)
        val out = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) {
            "run with AGP's additional test output, as connectedAndroidTest does"
        }.let { File(it).apply { mkdirs() } }
        val names = setOf(
            "0-plain", "1-current", "2-one-letter", "3-one-third", "7-thin-tail",
            "12-one-third-600-thin-long", "15-one-letter-long", "16-one-third-600-long",
            "19-three-zone-medium", "20-size-gradient", "21-size-and-weights",
        )
        for (look in LOOKS.filter { it.name in names }) {
            NativeEngine.setBoldWordStartsLook(
                look.mode, look.boldWeight, look.tailWeight, look.middlePercent,
                look.middleWeight, look.everyNth, look.minWordLength, look.boldSize, look.middleSize, look.tailSize,
            )
            val lookSettings = settings.copy(fontFace = "PT Serif", boldWordStarts = look.boldWordStarts)
            EngineDocument.open(context, book, WIDTH, HEIGHT, lookSettings, DENSITY, cacheable = false).use { doc ->
                doc.apply(lookSettings, WIDTH, HEIGHT)
                val page = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
                doc.renderPage(page)
                File(out, "ptserif-${look.name}.png").outputStream().use {
                    page.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    /** Goes to the first match of [phrase] and returns it with its place on that page. */
    private fun EngineDocument.locate(phrase: String): Pair<SearchHit, Rect> {
        val hit = search(phrase).first()
        assertTrue(goToPage(hit.page))
        return hit to rects(hit.range.start, hit.range.end).first()
    }

    private fun inkIn(bitmap: Bitmap, rect: Rect): Int {
        var ink = 0
        for (y in rect.top.coerceAtLeast(0) until rect.bottom.coerceAtMost(bitmap.height)) {
            for (x in rect.left.coerceAtLeast(0) until rect.right.coerceAtMost(bitmap.width)) {
                val color = bitmap.getPixel(x, y)
                val lightness = ((color shr 16 and 0xFF) * 299 + (color shr 8 and 0xFF) * 587 + (color and 0xFF) * 114) / 1000
                if (lightness < 128) ink++
            }
        }
        return ink
    }

    /** The settings of a new reader: Roboto 18 dp, 120 % lines, narrow margins, justified with hyphens. */
    private val settings = EngineSettings(
        fontFace = "Roboto",
        fontSize = 36,
        fontWeight = 400,
        lineSpacingPercent = 120,
        marginHorizontal = 16,
        marginTop = 24,
        marginBottom = 40,
        textAlign = TextAlign.Justify,
        hyphenation = true,
        paragraphSpacingPercent = 20,
        paragraphIndent = true,
        boldWordStarts = true,
    )

    /**
     * A big enough Russian book for a page-count difference to show. The test books are generated,
     * so no third-party text is carried.
     */
    private fun longBook(dir: File): File {
        val sentence = "Железнодорожное сообщение восстанавливалось чрезвычайно медленно, " +
            "путешественники переговаривались вполголоса, разглядывая достопримечательности " +
            "провинциального городка и размышляя о непредвиденных обстоятельствах, " +
            "задержавших отправление пассажирского поезда. "
        val body = (1..500).joinToString("\n") { "<p>${sentence.repeat(2)}</p>" }
        return File(dir, "look.fb2").apply {
            writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
<description><title-info>
  <author><first-name>Тест</first-name><last-name>Автор</last-name></author>
  <book-title>Контрольная страница</book-title><lang>ru</lang>
</title-info></description>
<body>
<section>
$body
</section>
</body>
</FictionBook>""",
            )
        }
    }

    private data class Look(
        val name: String,
        val boldWordStarts: Boolean = true,
        val mode: Int = 0,
        val boldWeight: Int = 700,
        val tailWeight: Int = 0,
        val middlePercent: Int = 0,
        val middleWeight: Int = 0,
        val everyNth: Int = 1,
        val minWordLength: Int = 0,
        val boldSize: Int = 100,
        val middleSize: Int = 100,
        val tailSize: Int = 100,
    )

    private companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val DENSITY = 320

        // 0: current rule; 1: one letter; 2: a third; 3: two thirds.
        // tailWeight and middleWeight 0 mean the weight of the book's own text (400 here).
        val LOOKS = listOf(
            Look("0-plain", boldWordStarts = false),
            Look("1-current"),
            Look("2-one-letter", mode = 1),
            Look("3-one-third", mode = 2),
            Look("4-two-thirds", mode = 3),
            Look("5-semibold", boldWeight = 600),
            Look("6-heavy", boldWeight = 800),
            Look("7-thin-tail", tailWeight = 300),
            Look("8-thin-third", mode = 2, tailWeight = 300),
            Look("9-three-zones", middlePercent = 25, tailWeight = 300),
            Look("10-long-words", minWordLength = 5),
            Look("11-every-other", everyNth = 2),
            Look("12-one-third-600-thin-long", mode = 2, boldWeight = 600, tailWeight = 300, minWordLength = 5),
            Look("13-semibold-thin", boldWeight = 600, tailWeight = 300),
            Look("14-one-third-long", mode = 2, minWordLength = 5),
            Look("15-one-letter-long", mode = 1, minWordLength = 5),
            Look("16-one-third-600-long", mode = 2, boldWeight = 600, minWordLength = 5),
            Look("17-one-third-600", mode = 2, boldWeight = 600),
            Look("18-one-letter-600-long", mode = 1, boldWeight = 600, minWordLength = 5),
            Look("19-three-zone-medium", mode = 2, middlePercent = 33, middleWeight = 500, tailWeight = 300),
            Look("20-size-gradient", mode = 2, boldWeight = 400, middlePercent = 33, boldSize = 115, tailSize = 85),
            Look(
                "21-size-and-weights", mode = 2, middlePercent = 33, middleWeight = 500, tailWeight = 300,
                boldSize = 115, tailSize = 85,
            ),
        )

        val COUNT_LOOKS = LOOKS.filter {
            it.name in setOf(
                "0-plain", "1-current", "2-one-letter", "3-one-third", "5-semibold",
                "7-thin-tail", "10-long-words", "12-one-third-600-thin-long", "14-one-third-long",
                "15-one-letter-long", "16-one-third-600-long",
                "19-three-zone-medium", "20-size-gradient", "21-size-and-weights",
            )
        }
    }
}
