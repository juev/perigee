package org.evsyukov.perigee.reader

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
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
import androidx.compose.ui.platform.LocalConfiguration
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
 * The orientation to ask the system for, as [ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE] and the rest,
 * for the screen to be turned to [rotation]. [naturalPortrait] is whether the device stands upright at
 * rotation 0, as phones do; a tablet lies on its side there.
 */
internal fun orientationFor(rotation: Int, naturalPortrait: Boolean): Int = if (naturalPortrait) {
    when (rotation) {
        Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
} else {
    when (rotation) {
        Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    // Locked where the screen is, and again once a rotation the reader asked for is done.
    val orientation = LocalConfiguration.current.orientation
    LaunchedEffect(activity, orientation) { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED }
    var wanted by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(activity) {
        var held = activity.display?.rotation ?: Surface.ROTATION_0
        val listener = object : OrientationEventListener(activity) {
            override fun onOrientationChanged(degrees: Int) {
                held = rotationFor(degrees, held)
                val shown = activity.display?.rotation ?: return
                // Upside down is not offered: nobody reads so, and its page, with the camera strip at the
                // bottom, is not the one the copy laid out for the screen upright fits.
                wanted = held.takeIf { it != shown && it != Surface.ROTATION_180 }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    var offered by remember { mutableStateOf(false) }
    // Set when the reader asked to turn; a turn that does not come, as when the device was turned back
    // meanwhile, must not leave the screen free to follow the device.
    var asked by remember { mutableStateOf(0) }
    LaunchedEffect(asked) {
        if (asked > 0) {
            delay(RELOCK_AFTER_MILLIS)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }
    }
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
                val target = wanted ?: return@SmallFloatingActionButton
                asked++
                val shown = activity.display?.rotation ?: Surface.ROTATION_0
                val portraitNow = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                val naturalPortrait = portraitNow == (shown == Surface.ROTATION_0 || shown == Surface.ROTATION_180)
                Log.d("Perigee.Reader", "turn asked from rotation $shown to $target")
                // The screen is turned the way the button showed; the new orientation is locked in turn.
                activity.requestedOrientation = orientationFor(target, naturalPortrait)
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
private const val RELOCK_AFTER_MILLIS = 2_000L
