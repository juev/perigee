package org.evsyukov.perigee

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.legal.AboutScreen
import org.evsyukov.perigee.legal.LicensesScreen
import org.evsyukov.perigee.library.LibraryScreen
import org.evsyukov.perigee.library.describeKeptFile
import org.evsyukov.perigee.library.unpacked
import org.evsyukov.perigee.opds.OpdsScreen
import org.evsyukov.perigee.reader.ReaderScreen
import org.evsyukov.perigee.reader.VolumeKeyHost
import org.evsyukov.perigee.server.ServerScreen
import org.evsyukov.perigee.server.SyncWork
import org.evsyukov.perigee.ui.PerigeeTheme

class MainActivity : ComponentActivity(), VolumeKeyHost {
    override var onVolumeKey: ((down: Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PerigeeTheme {
                Surface(Modifier.fillMaxSize()) {
                    val scope = rememberCoroutineScope()
                    var bookPath by rememberSaveable { mutableStateOf<String?>(null) }
                    // Closing a book that came from a catalog or the server returns there.
                    var catalogs by rememberSaveable { mutableStateOf(false) }
                    var server by rememberSaveable { mutableStateOf(false) }
                    var about by rememberSaveable { mutableStateOf(false) }
                    var licenses by rememberSaveable { mutableStateOf(false) }
                    // Until the last book is looked up there is nothing to show, not even the library.
                    var ready by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        if (!ready) {
                            // The app starts on the book the reader left, as long as its file is still there.
                            bookPath = Library.get(this@MainActivity).lastOpened()?.path?.takeIf { File(it).isFile }
                            ready = true
                        }
                    }
                    // What other devices did since the app was last used is asked for at the start.
                    LaunchedEffect(Unit) { SyncWork.soon(this@MainActivity) }
                    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) scope.launch { bookPath = importBook(uri).path }
                    }
                    val path = bookPath
                    when {
                        !ready -> Unit
                        path == null && catalogs -> OpdsScreen(
                            onOpen = { bookPath = it.path },
                            onClose = { catalogs = false },
                        )
                        path == null && server -> ServerScreen(
                            onOpen = { bookPath = it.path },
                            onClose = { server = false },
                        )
                        // The licenses are read from the screen about the app and lead back to it.
                        path == null && licenses -> LicensesScreen(onClose = { licenses = false })
                        path == null && about -> AboutScreen(onLicenses = { licenses = true }, onClose = { about = false })
                        path == null -> LibraryScreen(
                            onOpen = { bookPath = it.path },
                            onOpenFile = { picker.launch(arrayOf("*/*")) },
                            onCatalogs = { catalogs = true },
                            onServer = { server = true },
                            onAbout = { about = true },
                        )
                        else -> ReaderScreen(File(path), onClose = { bookPath = null })
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handler = onVolumeKey
        if (handler != null && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            handler(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // The key was taken on the way down; letting it up through would beep.
        if (onVolumeKey != null && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * The engine opens files by path, so a picked document is copied into the
     * app's own storage, where it stays until the app is removed.
     */
    private suspend fun importBook(uri: Uri): File = withContext(Dispatchers.IO) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "book"
        val target = File(File(filesDir, "books").apply { mkdirs() }, File(name).name)
        contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        // Read for its cover, authors and series as a book of a folder is; one the engine cannot read is opened all the same.
        unpacked(target).also { describeKeptFile(this@MainActivity, Library.get(this@MainActivity), Covers.get(this@MainActivity), it) }
    }
}
