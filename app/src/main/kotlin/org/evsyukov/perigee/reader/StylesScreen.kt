package org.evsyukov.perigee.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.evsyukov.perigee.R

/**
 * The owner's CSS on the whole screen, applied after every style of the app's. Save hands the text
 * over and closes, so the page shows what it did; the arrow closes and keeps what was saved before.
 * Text with a brace, comment or string left open is not let through.
 *
 * [previous] is the overlay as it was before the last change: the menu puts it back in the field, to be
 * saved or edited again, so a stylesheet that made the book unreadable is one tap away from gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StylesScreen(css: String, previous: String? = null, onSave: (String) -> Unit, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(css) }
    var menu by remember { mutableStateOf(false) }
    var resetting by rememberSaveable { mutableStateOf(false) }
    val balanced = CssText.balanced(text)
    // A tap on the empty part of this screen must not reach the page behind it and turn the page; the
    // reader's tap handler does not look at which panel is open. A clickable with no look takes it.
    val scrim = remember { MutableInteractionSource() }
    // The reader hides the system bars: what is left to keep clear of is the camera cutout, and the keyboard.
    Scaffold(
        modifier = Modifier.fillMaxSize().clickable(interactionSource = scrim, indication = null) {},
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.styles_title)) },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                navigationIcon = {
                    IconButton(onClick = onClose, Modifier.testTag("styles-cancel")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            onSave(text)
                            onClose()
                        },
                        enabled = balanced,
                        modifier = Modifier.testTag("styles-save"),
                    ) { Text(stringResource(R.string.action_save)) }
                    Box {
                        IconButton(onClick = { menu = true }, Modifier.testTag("styles-more")) {
                            Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (previous != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.styles_previous)) },
                                    onClick = {
                                        menu = false
                                        text = previous
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.styles_reset)) },
                                onClick = {
                                    menu = false
                                    resetting = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                stringResource(R.string.styles_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!balanced) {
                Text(
                    stringResource(R.string.styles_unbalanced),
                    Modifier.padding(top = 4.dp).testTag("styles-error"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .testTag("styles-text"),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                isError = !balanced,
                // CSS, not prose: no capitals and no corrections from the keyboard.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
        }
    }
    if (resetting) {
        AlertDialog(
            onDismissRequest = { resetting = false },
            title = { Text(stringResource(R.string.styles_reset)) },
            text = { Text(stringResource(R.string.styles_reset_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetting = false
                        onSave("")
                        onClose()
                    },
                ) { Text(stringResource(R.string.styles_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { resetting = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
