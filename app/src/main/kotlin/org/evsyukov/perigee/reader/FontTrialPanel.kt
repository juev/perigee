package org.evsyukov.perigee.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.evsyukov.perigee.R

/**
 * The font trial over the page: each passage as the book would show it, with Next below; then the
 * speeds, the fastest font to take, and a word on how rough the result is.
 */
@Composable
internal fun FontTrialPanel(trial: FontTrialState, paper: Color, onNext: () -> Unit, onUse: (String) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(paper).testTag("font-trial")) {
        if (!trial.done) {
            val page = trial.page
            if (page == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Image(page.bitmap.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            }
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), tonalElevation = 3.dp) {
                Row(
                    Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.font_trial_step, trial.step + 1, trial.faces.size), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.font_trial_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.font_trial_close)) }
                    Button(onClick = onNext, enabled = page != null, modifier = Modifier.testTag("font-trial-next")) {
                        Text(stringResource(R.string.font_trial_next))
                    }
                }
            }
        } else {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.statusBarsPadding().navigationBarsPadding().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val fastest = trial.fastest
                    if (fastest == null) {
                        Text(stringResource(R.string.font_trial_too_short), style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Text(stringResource(R.string.font_trial_result_title), style = MaterialTheme.typography.titleMedium)
                        for ((face, speed) in trial.results.sortedByDescending { it.second }) {
                            Row(Modifier.fillMaxWidth().testTag("font-trial-result"), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(face)
                                Text(speed.toString())
                            }
                        }
                        Text(stringResource(R.string.font_trial_rough), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { onUse(fastest) }, modifier = Modifier.testTag("font-trial-use")) {
                            Text(stringResource(R.string.font_trial_use, fastest))
                        }
                    }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.font_trial_close)) }
                }
            }
        }
    }
}
