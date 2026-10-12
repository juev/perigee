package org.evsyukov.perigee.contract

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.evsyukov.perigee.engine.EngineDocument
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Server behaviour the sync design has to work around. Each test pins a limit
 * observed on bookOrbit 3.3.0; a failure means the server changed and the
 * workaround should be revisited.
 */
@RunWith(AndroidJUnit4::class)
class ServerLimitsContractTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: BookOrbitServer

    @Before
    fun connect() {
        server = ContractServer.connect()
    }

    @Test
    fun fb2ProgressIsStoredAsXPointerWithoutCfi() {
        val book = server.bookByTitle("Contract Fb2")
        val file = File(context.cacheDir, "contract-${book.fileId}.fb2")
        server.download(book.fileId, file)
        val hash = BookOrbitServer.partialMd5(file)

        EngineDocument.open(context, file, 720, 1280, fontSize = 28).use { doc ->
            doc.goToPage(doc.pageCount / 2)
            val sent = doc.xpointer()
            val push = server.koreader(
                "PUT", "/koreader/syncs/progress",
                JSONObject()
                    .put("document", hash)
                    .put("percentage", doc.progress.toDouble())
                    .put("progress", sent)
                    .put("device", "Perigee")
                    .put("device_id", "perigee-fb2")
                    .put("timestamp", System.currentTimeMillis() / 1000),
            )
            assertEquals("push: ${push.body}", 200, push.code)

            // Another crengine device gets the position back verbatim…
            val pulled = server.koreader("GET", "/koreader/syncs/progress/$hash").json()
            assertEquals(sent, pulled.getString("progress"))

            // …but the web reader only gets a percentage: FB2 has no CFI on the server.
            val stored = server.jwt("GET", "/books/files/${book.fileId}/progress").json()
            assertTrue("no CFI for FB2, got ${stored.opt("cfi")}", stored.isNull("cfi"))
            assertEquals(doc.progress * 100.0, stored.getDouble("percentage"), 0.01)
        }
    }

    @Test
    fun repeatedAnnotationPostCreatesADuplicate() {
        val book = server.bookByTitle("Contract Plain")
        val body = JSONObject()
            .put("text", "duplicate probe ${System.currentTimeMillis()}")
            .put("cfi", "epubcfi(/6/2!/4/2/4,/1:0,/1:5)")
        val first = server.jwt("POST", "/books/${book.bookId}/annotations", body)
        val second = server.jwt("POST", "/books/${book.bookId}/annotations", body)
        assertEquals(201, first.code)
        assertEquals(201, second.code)
        // No idempotency key on this endpoint: a blind retry after a lost response duplicates.
        assertNotEquals(first.json().getInt("id"), second.json().getInt("id"))
    }
}
