package org.evsyukov.perigee

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

/**
 * Whether an address as the reader typed it is reached without encryption.
 * One without a scheme is not: it is taken as https.
 */
internal fun isPlainHttp(address: String): Boolean = address.trim().startsWith("http://", ignoreCase = true)

/** Asks before a user name and password are sent where anyone on the way can read them. */
@Composable
internal fun PlainHttpWarning(onSend: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plain_http_title)) },
        text = { Text(stringResource(R.string.plain_http_text)) },
        confirmButton = {
            TextButton(onClick = onSend, modifier = Modifier.testTag("plain-http-send")) { Text(stringResource(R.string.plain_http_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("plain-http-cancel")) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
