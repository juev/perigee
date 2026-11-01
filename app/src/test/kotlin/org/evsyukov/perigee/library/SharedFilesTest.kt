package org.evsyukov.perigee.library

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Which files an intent shares with the app, as other apps and the system hand them over. */
@RunWith(RobolectricTestRunner::class)
class SharedFilesTest {
    private val book = Uri.parse("content://files.example/book/1")
    private val other = Uri.parse("content://files.example/book/2")

    @Test
    fun oneSharedFileIsTakenFromTheStream() {
        val intent = Intent(Intent.ACTION_SEND).setType("application/epub+zip").putExtra(Intent.EXTRA_STREAM, book)
        assertEquals(listOf(book), sharedFiles(intent))
    }

    @Test
    fun aFileTheSystemPutIntoTheClipAsWellIsTakenOnce() {
        val intent = Intent(Intent.ACTION_SEND).setType("application/epub+zip").putExtra(Intent.EXTRA_STREAM, book)
        intent.clipData = ClipData.newRawUri("", book)
        assertEquals(listOf(book), sharedFiles(intent))
    }

    @Test
    fun filesGivenOnlyInTheClipAreTaken() {
        val one = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
        one.clipData = ClipData.newRawUri("", book)
        assertEquals(listOf(book), sharedFiles(one))

        val several = Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/*")
        several.clipData = ClipData.newRawUri("", book).apply { addItem(ClipData.Item(other)) }
        assertEquals(listOf(book, other), sharedFiles(several))
    }

    @Test
    fun severalFilesAreTakenInTheirOrderEachOnce() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(book, other, book))
        intent.clipData = ClipData.newRawUri("", other).apply { addItem(ClipData.Item(book)) }
        assertEquals(listOf(book, other), sharedFiles(intent))
    }

    @Test
    fun aShareWithoutAFileTakesNone() {
        val text = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.org/a-book")
        text.clipData = ClipData.newPlainText("", "https://example.org/a-book")
        assertEquals(emptyList<Uri>(), sharedFiles(text))

        val misnamed = Intent(Intent.ACTION_SEND).setType("application/epub+zip").putExtra(Intent.EXTRA_STREAM, "content://files.example/book/1")
        assertEquals("a stream that is not an address", emptyList<Uri>(), sharedFiles(misnamed))

        val misgiven = Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/*").putExtra(Intent.EXTRA_STREAM, book)
        assertEquals("several files given as one", emptyList<Uri>(), sharedFiles(misgiven))
    }

    @Test
    fun anIntentThatSharesNothingIsNoShare() {
        assertNull(sharedFiles(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertNull(sharedFiles(Intent(Intent.ACTION_VIEW, book)))
    }
}
