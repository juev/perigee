package org.evsyukov.perigee.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.evsyukov.perigee.R

/**
 * What became of the files handed to the app: each book that joined the
 * library with a button to read it, whatever shelf the library shows, and
 * each file that did not with the reason. Nothing is shown for an empty list.
 */
@Composable
fun ImportResults(results: List<Imported>, onRead: (path: String) -> Unit, onDismiss: () -> Unit) {
    if (results.isEmpty()) return
    val kept = results.count { it is Imported.Kept }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (kept) {
                    results.size -> pluralStringResource(R.plurals.import_added, kept, kept)
                    0 -> stringResource(R.string.import_none)
                    else -> stringResource(R.string.import_some, kept, results.size)
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (result in results) {
                    when (result) {
                        is Imported.Kept -> Row(Modifier.fillMaxWidth().testTag("imported"), verticalAlignment = Alignment.CenterVertically) {
                            Text(result.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { onRead(result.path) }) { Text(stringResource(R.string.import_read)) }
                        }
                        is Imported.Failed -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(result.name.ifEmpty { stringResource(R.string.import_unnamed) }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(importFailureText(result.failure)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_close)) } },
    )
}

private fun importFailureText(failure: ImportFailure): Int = when (failure) {
    ImportFailure.NoFile -> R.string.import_failed_no_file
    ImportFailure.Denied -> R.string.import_failed_denied
    ImportFailure.NotABook -> R.string.import_failed_not_a_book
    ImportFailure.Unreadable -> R.string.import_failed_unreadable
    ImportFailure.NotCopied -> R.string.import_failed_not_copied
}
