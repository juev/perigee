package org.evsyukov.perigee.reader

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import org.evsyukov.perigee.data.ReaderPrefs

/**
 * Keeps the screen on for [minutes] after the last of [activity], then lets the
 * system timeout take over. Runs until cancelled, and leaves the screen to the
 * system then.
 */
internal suspend fun keepScreenOn(minutes: Int, activity: Flow<Unit>, keepOn: (Boolean) -> Unit) {
    try {
        when (minutes) {
            ReaderPrefs.SCREEN_ON_SYSTEM -> keepOn(false)
            ReaderPrefs.SCREEN_ON_ALWAYS -> {
                keepOn(true)
                awaitCancellation()
            }
            // Opening the book counts as the first touch.
            else -> activity.onStart { emit(Unit) }.collectLatest {
                keepOn(true)
                delay(minutes * 60_000L)
                keepOn(false)
            }
        }
    } finally {
        keepOn(false)
    }
}

/** Keeps the screen on while the reader is on screen, by [minutes] after each of [activity]. */
@Composable
internal fun ScreenOn(minutes: Int, activity: Flow<Unit>) {
    val window = LocalActivity.current?.window
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(window, lifecycle, minutes, activity) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            keepScreenOn(minutes, activity) { on ->
                if (on) {
                    window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }
}
