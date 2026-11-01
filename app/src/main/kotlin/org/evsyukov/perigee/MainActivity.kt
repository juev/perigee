package org.evsyukov.perigee

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.legal.AboutScreen
import org.evsyukov.perigee.legal.LicensesScreen
import org.evsyukov.perigee.library.ImportResults
import org.evsyukov.perigee.library.ImportViewModel
import org.evsyukov.perigee.library.LibraryScreen
import org.evsyukov.perigee.library.SharedBooks
import org.evsyukov.perigee.opds.OpdsScreen
import org.evsyukov.perigee.reader.ReaderScreen
import org.evsyukov.perigee.reader.VolumeKeyHost
import org.evsyukov.perigee.server.ServerScreen
import org.evsyukov.perigee.server.SyncWork
import org.evsyukov.perigee.ui.PerigeeTheme

class MainActivity : ComponentActivity(), VolumeKeyHost {
    override var onVolumeKey: ((down: Boolean) -> Unit)? = null
    private val imports: ImportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Started to show a share, the app opens the book shared, not the one the reader left.
        val sharing = savedInstanceState == null && SharedBooks.shares.value.isNotEmpty()
        setContent {
            PerigeeTheme {
                Surface(Modifier.fillMaxSize()) {
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
                            val last = if (sharing) null else lastBook(this@MainActivity)
                            // A book shared or picked while it was looked up is open already, and stays.
                            if (!ready) {
                                bookPath = last
                                ready = true
                            }
                        }
                    }
                    LaunchedEffect(Unit) {
                        imports.toOpen.collect {
                            bookPath = it
                            ready = true
                        }
                    }
                    // A share is shown once nothing is over this screen: a file being picked, or a page of
                    // signing in, is left to the reader to finish first.
                    LaunchedEffect(Unit) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            SharedBooks.shares.collect { if (it.isNotEmpty()) SharedBooks.takeAll().forEach(imports::arrived) }
                        }
                    }
                    // What other devices did is asked for when the app comes in sight, and every minute it stays there.
                    LaunchedEffect(Unit) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { SyncWork.whileInSight(this@MainActivity) }
                    }
                    LaunchedEffect(Unit) { SyncWork.followAccount(this@MainActivity) }
                    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) imports.take(listOf(uri))
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
                    val imported by imports.results.collectAsStateWithLifecycle()
                    ImportResults(
                        imported,
                        onRead = {
                            imports.dismiss()
                            bookPath = it
                            ready = true
                        },
                        onDismiss = imports::dismiss,
                    )
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

    companion object {
        /** The file of the book the reader left, null when it is gone; the tests hold the looking up back. */
        internal var lastBook: suspend (Context) -> String? = { context ->
            Library.get(context).lastOpened()?.path?.takeIf { File(it).isFile }
        }
    }
}
