package org.evsyukov.perigee.library

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.evsyukov.perigee.MainActivity
import org.evsyukov.perigee.R
import org.evsyukov.perigee.ui.PerigeeTheme

/**
 * Where another app's "Share" with book files arrives. It stays over the
 * app that shared them, in that app's task, while the files are copied: the
 * right to read them is given to this activity and lasts while it does. Then
 * it hands what became of them to the reader's screen and brings the app's
 * own task to the front as it is — whatever is open there, as a file being
 * picked or a page of signing in, stays open — or starts the app when it is
 * not running. Back, or Cancel, calls the copying off and returns to the app
 * that shared the files.
 */
class ShareActivity : ComponentActivity() {
    private val shares: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val files = sharedFiles(intent)
        if (files == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
            finish()
            return
        }
        // Made anew, the activity has its model and the copying goes on; after the app died, it begins again.
        if (!shares.started) shares.take(files)
        setContent {
            PerigeeTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.share_adding), Modifier.padding(top = 16.dp))
                            TextButton(onClick = ::finish) { Text(stringResource(R.string.action_cancel)) }
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            // Handed over while the reader looks: a share finished while they were in another app waits for them.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                shares.done.first { it }
                showReader()
                finish()
            }
        }
    }

    private fun showReader() {
        val reader = ComponentName(this, MainActivity::class.java)
        val task = try {
            getSystemService(ActivityManager::class.java).appTasks.firstOrNull { it.taskInfo.baseActivity == reader }
        } catch (e: RuntimeException) {
            // A task that went away while it was looked at.
            null
        }
        try {
            if (task != null) return task.moveToFront()
        } catch (e: RuntimeException) {
            // Gone meanwhile: the app is started as below.
        }
        // As the launcher starts the app: the launcher, given the same intent, brings this task back, not a second one.
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(reader).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Copies the files of one share for [ShareActivity], whichever instance of it is shown. */
class ShareViewModel(application: Application) : AndroidViewModel(application) {
    private val importer = BookImport(application)
    private val _done = MutableStateFlow(false)

    /** The files are taken in, and what became of them is handed over to [SharedBooks]. */
    val done: StateFlow<Boolean> = _done.asStateFlow()

    var started = false
        private set

    fun take(uris: List<Uri>) {
        started = true
        viewModelScope.launch {
            SharedBooks.arrived(takeIn(importer, uris))
            _done.value = true
        }
    }
}

/**
 * Shares taken in, waiting for the reader's screen to show them: it takes
 * them once nothing is over it. Kept in memory only: the books are in the
 * library already, and what is lost with the app is that one is opened, or
 * the list of what became of them shown.
 */
object SharedBooks {
    private val waiting = MutableStateFlow<List<List<Imported>>>(emptyList())

    /** The shares waiting, oldest first. */
    val shares: StateFlow<List<List<Imported>>> = waiting.asStateFlow()

    fun arrived(taken: List<Imported>) = waiting.update { it + listOf(taken) }

    /** The shares waiting, which are no longer. */
    fun takeAll(): List<List<Imported>> = waiting.getAndUpdate { emptyList() }
}
