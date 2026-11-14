package org.evsyukov.perigee.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The available TTS speeds. */
private val TTS_SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/** Control bar for TTS reading: shows play/pause/stop/speed/voice and progress. */
@Composable
internal fun TtsBar(
    tts: TtsState,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onChangeVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(8.dp)
            .swallowTouches(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Play / Pause
            IconButton(onClick = onPlayPause, modifier = Modifier.size(36.dp)) {
                Text(
                    if (tts.playing) "⏸" else "▶",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            // Stop
            IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                Text("■", style = MaterialTheme.typography.titleMedium)
            }

            // Progress: which sentence out of how many.
            LinearProgressIndicator(
                progress = { if (tts.sentenceCount > 0) (tts.sentenceIndex + 1).toFloat() / tts.sentenceCount else 0f },
                modifier = Modifier.width(64.dp),
            )

            // Speed selector (cycles on tap)
            SpeedButton(
                speed = tts.speed,
                speeds = TTS_SPEEDS,
                onChange = onSpeedChange,
            )

            // Voice button (opens dialog to change voice)
            TextButton(onClick = onChangeVoice, modifier = Modifier.width(56.dp)) {
                Text(
                    if (tts.selectedVoice.isNotBlank()) tts.selectedVoice.substringAfterLast("-").take(6) else "🎙",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A button that cycles through speed choices on each tap. */
@Composable
private fun SpeedButton(speed: Float, speeds: List<Float>, onChange: (Float) -> Unit) {
    TextButton(
        onClick = {
            val i = speeds.indexOf(speed)
            onChange(speeds[(i + 1) % speeds.size])
        },
        modifier = Modifier.width(52.dp),
    ) {
        Text(
            "%.2g×".format(speed),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Swallows touches so they don't fall through to the page. */
private fun Modifier.swallowTouches(): Modifier =
    pointerInput(Unit) { detectTapGestures { } }