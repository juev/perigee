package org.evsyukov.perigee.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.evsyukov.perigee.R

private enum class Saving { Idle, Working, Saved, Failed }

/**
 * The diagnostic build's part of the screen about the app: what to do to catch the app closing, and the
 * action that saves the report where the reader picks, through the system's file picker. Saving again
 * after a failure is the same action; leaving the picker without a place changes nothing.
 */
@Composable
fun DiagnosticsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(Saving.Idle) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            saving = Saving.Working
            scope.launch { saving = if (Diagnostics.export(context, uri)) Saving.Saved else Saving.Failed }
        }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
        Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.titleMedium)
        for (line in listOf(
            R.string.diagnostics_about, R.string.diagnostics_step_font, R.string.diagnostics_step_book,
            R.string.diagnostics_step_smoothing, R.string.diagnostics_step_save, R.string.diagnostics_contents,
        )) {
            Text(stringResource(line), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = { picker.launch(Diagnostics.fileName()) },
            enabled = saving != Saving.Working,
            modifier = Modifier.padding(top = 12.dp),
        ) { Text(stringResource(R.string.action_save_diagnostics)) }
        val said = when (saving) {
            Saving.Idle -> null
            Saving.Working -> stringResource(R.string.diagnostics_saving)
            Saving.Saved -> stringResource(R.string.diagnostics_saved)
            Saving.Failed -> stringResource(R.string.diagnostics_save_failed)
        }
        if (said != null) {
            Text(
                said,
                Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = if (saving == Saving.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        Diagnostics.failure?.let {
            Text(
                stringResource(R.string.diagnostics_trace_stopped, it),
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
