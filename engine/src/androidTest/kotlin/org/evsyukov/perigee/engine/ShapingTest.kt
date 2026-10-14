package org.evsyukov.perigee.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Text is shaped as fully as its script needs, and no more: full shaping lays a long book out five times slower. */
@RunWith(AndroidJUnit4::class)
class ShapingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "shaping").apply { mkdirs() }
    private val opened = mutableListOf<EngineDocument>()

    @After
    fun close() = opened.forEach { it.close() }

    private fun book(language: String, text: String): EngineDocument {
        val file = File(dir, "$language.fb2")
        file.writeText(
            """<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">""" +
                """<description><title-info><book-title>$language</book-title><lang>$language</lang></title-info></description>""" +
                """<body><section><p>$text</p></section></body></FictionBook>""",
        )
        return EngineDocument.open(context, file, 720, 1280).also { opened += it }
    }

    @Test
    fun scriptsOfSeparateLettersAreShapedWithoutLigatures() {
        assertFalse(book("ru", "Съешь же ещё этих мягких французских булок").fullShaping)
        assertFalse(book("en", "The office staff fluffed the final affidavit").fullShaping)
        assertFalse(book("en-GB", "Fifty fine flowers").fullShaping)
    }

    @Test
    fun scriptsWhoseLettersJoinAreShapedInFull() {
        assertTrue(book("ar", "مرحبا بالعالم").fullShaping)
        assertTrue(book("he", "שלום עולם").fullShaping)
        assertTrue(book("hi-IN", "नमस्ते दुनिया").fullShaping)
        assertTrue(book("th", "สวัสดีชาวโลก").fullShaping)
    }
}
