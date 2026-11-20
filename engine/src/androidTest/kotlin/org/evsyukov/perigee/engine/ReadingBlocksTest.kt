package org.evsyukov.perigee.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingBlocksTest {
    @Test
    fun aNoteBearingSpreadExposesEachMainFlowAnchorOnceInLeafOrder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paragraphs = (1..60).joinToString("") { i ->
            val note = if (i == 1) """Текст книги с разметкой.<a l:href="#n1" type="note">1</a> """ else ""
            "<p>Chapter 1, paragraph $i. PBEGIN${i}X $note" +
                "An example reading sentence in this synthetic book. ".repeat(4) + "PEND${i}X</p>"
        }
        val file = File.createTempFile("reading-blocks-", ".fb2", context.cacheDir)
        try {
            file.writeText(
                """<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description><title-info><book-title>Reading blocks</book-title><lang>en</lang></title-info></description>
<body><section><title><p>Chapter 1</p></title>$paragraphs</section></body>
<body name="notes"><section id="n1"><title><p>1</p></title><p>FOOTNOTECOBALT. A synthetic footnote.</p></section></body>
</FictionBook>""",
            )
            val reader = BookReader.open(context, file, 960, 900, EngineSettings(twoPages = true, hyphenation = false), densityDpi = 160)
            try {
                var bothLeaves = false
                val text = StringBuilder()
                val count = reader.current().pageCount
                for (index in 0 until count step 2) {
                    val page = reader.goToPage(index)
                    assertEquals(index, page.index)
                    val blocks = page.readingBlocks
                    assertEquals("spread $index must not expose the same anchor twice", blocks.size, blocks.map { it.anchor }.distinct().size)
                    // This fixture contains ordinary paragraphs, not boxes spanning the gutter.
                    val leaves = blocks.map { ((it.left + it.right) / 2 >= page.bitmap.width / 2) }
                    if (false in leaves && true in leaves) bothLeaves = true
                    assertTrue("left leaf must precede right leaf on spread $index", leaves.zipWithNext().none { (a, b) -> a && !b })
                    for (block in blocks) text.appendLine(block.text)
                }
                assertTrue("the fixture must exercise both leaves", bothLeaves)
                for (marker in listOf("PBEGIN", "PEND")) {
                    val actual = Regex("${marker}(\\d+)X").findAll(text).map { it.groupValues[1].toInt() }.toList()
                    assertEquals("paragraph $marker markers in document order", (1..60).toList(), actual)
                }
                assertTrue(text.contains("Текст книги с разметкой."))
            } finally {
                reader.close()
            }
        } finally {
            file.delete()
        }
    }
}
