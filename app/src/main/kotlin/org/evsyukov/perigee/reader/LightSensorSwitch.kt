package org.evsyukov.perigee.reader

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Chooses between the light and the dark scheme by the light around the screen, so that the page is not
 * much brighter than the room. Below [DARK_LUX] the page goes dark, above [LIGHT_LUX] light, and between
 * the two nothing changes; a level has to hold for [holdMillis] first, so a passing shadow flips nothing.
 * A scheme chosen by hand pauses the switch until [resume].
 */
internal class LightSensorSwitch(
    private val scope: CoroutineScope,
    private val holdMillis: Long = HOLD_MILLIS,
    private val onSide: (dark: Boolean) -> Unit,
) {
    private var decided: Boolean? = null
    private var pending: Boolean? = null
    private var job: Job? = null
    private var paused = false

    /** A reading of the light sensor, in lux. */
    fun onLux(lux: Float) {
        if (paused) return
        val wants = when {
            lux < DARK_LUX -> true
            lux > LIGHT_LUX -> false
            else -> null
        }
        if (wants == pending) return
        job?.cancel()
        pending = null
        if (wants == null || wants == decided) return
        pending = wants
        job = scope.launch {
            delay(holdMillis)
            pending = null
            decided = wants
            onSide(wants)
        }
    }

    /** The reader chose a scheme by hand: the light around decides nothing until [resume]. */
    fun pause() {
        paused = true
        job?.cancel()
        pending = null
    }

    /** A book is opened: the light around decides again, from the next reading on. */
    fun resume() {
        paused = false
        decided = null
    }

    companion object {
        const val DARK_LUX = 10f
        const val LIGHT_LUX = 40f
        const val HOLD_MILLIS = 5_000L

        fun available(context: Context): Boolean =
            context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
    }
}

/** Passes the light sensor's readings on while [enabled]; nothing is listened to otherwise. */
@Composable
internal fun LightSensor(enabled: Boolean, onLux: (Float) -> Unit) {
    val context = LocalContext.current
    val current = rememberUpdatedState(onLux)
    DisposableEffect(enabled) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (!enabled || sensor == null) return@DisposableEffect onDispose {}
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = current.value(event.values[0])
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { manager.unregisterListener(listener) }
    }
}
