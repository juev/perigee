package org.evsyukov.perigee.reader

import android.content.pm.ActivityInfo
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import org.evsyukov.perigee.R

/**
 * The screen rotation, as [Surface.ROTATION_0] and the rest, that suits a
 * device held at [degrees] from its natural position, clockwise, as
 * [OrientationEventListener] tells it. A device held near halfway between two
 * positions, or lying flat, keeps [previous]: the screen must not be offered
 * to turn back and forth while the reader shifts in a chair.
 */
internal fun rotationFor(degrees: Int, previous: Int): Int {
    if (degrees < 0) return previous
    val d = degrees % 360
    fun near(centre: Int) = minOf((d - centre + 360) % 360, (centre - d + 360) % 360) <= NEAR_DEGREES
    return when {
        near(0) -> Surface.ROTATION_0
        // A device turned clockwise has its screen turned the other way to stay upright.
        near(90) -> Surface.ROTATION_270
        near(180) -> Surface.ROTATION_180
        near(270) -> Surface.ROTATION_90
        else -> previous
    }
}

/**
 * The screen keeps the orientation it has while a book is read, as in ReadEra.
 * When the reader turns the device, a button offers to turn the screen too;
 * laying a book out again for the new page takes a moment, and is not done
 * because the device tipped in the hand.
 */
@Composable
internal fun RotationOnRequest(modifier: Modifier) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose {
            // A rotation the reader asked for recreates the activity; the next one locks it again.
            if (!activity.isChangingConfigurations) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    var wanted by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(activity) {
        var held = activity.display?.rotation ?: Surface.ROTATION_0
        val listener = object : OrientationEventListener(activity) {
            override fun onOrientationChanged(degrees: Int) {
                held = rotationFor(degrees, held)
                val shown = activity.display?.rotation ?: return
                wanted = held.takeIf { it != shown }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    var offered by remember { mutableStateOf(false) }
    LaunchedEffect(wanted) {
        offered = false
        if (wanted != null) {
            // Held so for a moment, not passed through on the way.
            delay(OFFER_AFTER_MILLIS)
            offered = true
            delay(OFFER_FOR_MILLIS)
            offered = false
        }
    }
    AnimatedVisibility(offered, modifier, enter = fadeIn(), exit = fadeOut()) {
        SmallFloatingActionButton(
            onClick = {
                offered = false
                // The system turns the screen the way the device is held; the activity made anew locks it there.
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            },
            modifier = Modifier.testTag("rotate"),
        ) {
            Icon(Icons.Filled.Refresh, stringResource(R.string.action_rotate))
        }
    }
}

private const val NEAR_DEGREES = 30
private const val OFFER_AFTER_MILLIS = 400L
private const val OFFER_FOR_MILLIS = 4_000L
