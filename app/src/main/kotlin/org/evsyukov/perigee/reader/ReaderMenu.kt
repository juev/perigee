package org.evsyukov.perigee.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.evsyukov.perigee.R

/** Colours a quote can have, 0xRRGGBB. The first is the default. */
internal val QUOTE_COLORS = listOf(0xFFE066, 0x9BE38A, 0x8FC8FF, 0xFFA8C5)

/** The bars over the page: the book and its tools on top, the position at the bottom. */
@Composable
internal fun ReaderMenu(
    state: ReaderState,
    onClose: () -> Unit,
    onSearch: () -> Unit,
    onContents: () -> Unit,
    onSettings: () -> Unit,
    onToggleBookmark: () -> Unit,
    onGoBack: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 4.dp) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(
                            state.book?.title.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val author = state.book?.author.orEmpty()
                        if (author.isNotEmpty()) {
                            Text(author, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, stringResource(R.string.action_search)) }
                    IconButton(onClick = onContents) {
                        Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.action_contents))
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.action_settings)) }
                }
                state.chapter?.let {
                    Text(
                        it.title,
                        Modifier.padding(start = 52.dp, end = 12.dp, bottom = 8.dp).testTag("chapter"),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // The menu stays composed while it fades out, even if the book is gone by then.
        val page = state.page
        if (page != null) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 4.dp) {
            Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onGoBack, enabled = state.canGoBack) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.action_go_back))
                    }
                    Text(
                        stringResource(R.string.page_of, page.index + 1, page.pageCount),
                        Modifier.weight(1f).testTag("position"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val marked = state.pageBookmark != null
                    IconButton(onClick = onToggleBookmark) {
                        Icon(
                            Icons.Filled.Star,
                            stringResource(if (marked) R.string.action_bookmark_remove else R.string.action_bookmark_add),
                            tint = if (marked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // The thumb follows the finger; the book moves once, when it is let go.
                var dragged by remember(page.index, page.pageCount) { mutableFloatStateOf(page.progress) }
                val label = stringResource(R.string.progress_line)
                Slider(
                    value = dragged,
                    onValueChange = { dragged = it },
                    onValueChangeFinished = { onSeek(dragged) },
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
        }
    }
}

/** Actions for the text being selected, or for the quote that was touched. */
@Composable
internal fun SelectionBars(state: ReaderState, viewModel: ReaderViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val selection = state.selection
    val touched = state.quoteTouch
    var noteFor by remember { mutableStateOf<NoteTarget?>(null) }

    val text = selection?.range?.text ?: touched?.quote?.text
    val copyLabel = stringResource(R.string.action_copy)
    val translateLabel = stringResource(R.string.action_translate)
    if (text != null) {
        Surface(
            modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(12.dp).testTag("selection-bar"),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            // On a narrow screen the actions do not all fit; the row scrolls.
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QUOTE_COLORS.forEachIndexed { index, color ->
                    val label = stringResource(R.string.quote_color, index + 1)
                    Box(
                        Modifier
                            .padding(4.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF000000 or color.toLong()))
                            .border(
                                if (touched?.quote?.color == color) 2.dp else 0.dp,
                                MaterialTheme.colorScheme.onSurface,
                                CircleShape,
                            )
                            .clickable {
                                if (touched != null) {
                                    viewModel.updateQuote(touched.quote, touched.quote.note, color)
                                } else {
                                    viewModel.quoteSelection(color)
                                }
                            }
                            .semantics { contentDescription = label },
                    )
                }
                IconButton(onClick = {
                    noteFor = if (touched != null) NoteTarget.Existing(touched) else NoteTarget.New
                }) { Icon(Icons.Filled.Edit, stringResource(R.string.action_note)) }
                IconButton(onClick = {
                    copy(context, text)
                    viewModel.clearSelection()
                }) { Text("⧉", Modifier.semantics { contentDescription = copyLabel }, style = MaterialTheme.typography.titleLarge) }
                IconButton(onClick = { share(context, text) }) { Icon(Icons.Filled.Share, stringResource(R.string.action_share)) }
                IconButton(onClick = { processText(context, text) }) {
                    Text("A文", Modifier.semantics { contentDescription = translateLabel }, style = MaterialTheme.typography.titleSmall)
                }
                if (touched != null) {
                    IconButton(onClick = { viewModel.removeQuote(touched.quote) }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.action_delete))
                    }
                } else {
                    IconButton(onClick = { webSearch(context, text) }) {
                        Icon(Icons.Filled.Search, stringResource(R.string.action_web_search))
                    }
                }
            }
        }
    }

    noteFor?.let { target ->
        val existing = (target as? NoteTarget.Existing)?.touch?.quote
        NoteDialog(
            initial = existing?.note.orEmpty(),
            onSave = { note ->
                if (existing != null) {
                    viewModel.updateQuote(existing, note, existing.color)
                } else {
                    viewModel.quoteSelection(QUOTE_COLORS.first(), note.takeIf { it.isNotBlank() })
                }
                noteFor = null
            },
            onDismiss = { noteFor = null },
        )
    }
}

private sealed interface NoteTarget {
    /** A note for the selection, which becomes a quote with it. */
    data object New : NoteTarget
    data class Existing(val touch: QuoteTouch) : NoteTarget
}

@Composable
private fun NoteDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_note)) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth().testTag("note-field"),
                placeholder = { Text(stringResource(R.string.note_hint)) },
                minLines = 3,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(note) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, text))
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

/** Hands the text to the apps that process selected text: translators and dictionaries. */
private fun processText(context: Context, text: String) {
    val process = Intent(Intent.ACTION_PROCESS_TEXT)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    context.startActivity(Intent.createChooser(process, null))
}

private fun webSearch(context: Context, text: String) {
    // A device without a search app has nothing to answer this.
    runCatching { context.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", text)) }
}
