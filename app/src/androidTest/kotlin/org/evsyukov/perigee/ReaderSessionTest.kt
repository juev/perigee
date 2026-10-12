package org.evsyukov.perigee

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.evsyukov.perigee.engine.EngineSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSessionTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * A rotation closes the session of the old screen while the new screen is
     * already opening the same book. Overlapping the two used to corrupt the
     * engine's allocator and kill the process.
     */
    @Test
    fun closingOneSessionWhileOpeningAnotherIsSafe() = runBlocking(Dispatchers.Default) {
        val file = book()
        var current = ReaderSession.open(context, file, 720, 1280, EngineSettings())
        repeat(30) {
            val closing = current
            val closed = launch { closing.close() }
            val opened = async { ReaderSession.open(context, file, 1280, 720, EngineSettings()) }
            current = opened.await()
            closed.join()
            assertTrue(current.render(1280, 720).pageCount > 1)
        }
        current.close()
    }

    private fun book(): File {
        val paragraphs = (1..600).joinToString("\n") {
            "<p>Абзац $it. Железнодорожное сообщение восстанавливалось чрезвычайно медленно, " +
                "путешественники переговаривались вполголоса и разглядывали достопримечательности.</p>"
        }
        return File(context.cacheDir, "session-test.fb2").apply {
            writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
<description><title-info><book-title>Session</book-title><lang>ru</lang></title-info></description>
<body><section><title><p>Глава</p></title>
$paragraphs
</section></body>
</FictionBook>""",
            )
        }
    }
}
