package org.evsyukov.perigee.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.evsyukov.perigee.R
import org.evsyukov.perigee.sync.CatalogueQuery
import org.evsyukov.perigee.sync.ReadStatuses
import org.evsyukov.perigee.sync.Related
import org.evsyukov.perigee.sync.RemoteBook
import org.evsyukov.perigee.sync.RemoteBookDetail

/**
 * What the reader keeps of a book on the server: their status in the server's words, stars, a note and the
 * collections it is in. Each change is shown once the server confirmed it; until then the page says it is on
 * its way, and a refusal says why. The book does not have to be downloaded for any of it.
 */
@Composable
internal fun PersonalSection(book: RemoteBook, detail: RemoteBookDetail, state: ServerState, model: ServerViewModel) {
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().testTag("server-personal"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.server_personal), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        if (book.id in state.unsentOnDevice) {
            Text(stringResource(R.string.server_local_unsent), style = MaterialTheme.typography.bodySmall, color = faint)
        }
        // What is on its way to the server for this book, wherever the reader went meanwhile.
        val saving = state.saving(book.id)
        StatusLine(detail.status, Personal.Status in saving, model::setStatus)
        Stars(detail.rating, Personal.Rating in saving, model::rate)
        Note(detail.note, Personal.Note in saving, model::writeNote)
        TextButton(onClick = model::chooseCollections, modifier = Modifier.testTag("server-book-collections")) {
            Text(stringResource(R.string.action_collections))
        }
        if (saving.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.server_saving), style = MaterialTheme.typography.bodySmall, color = faint)
            }
        }
        state.personalFailure?.takeIf { it.bookId == book.id }?.let { failure ->
            Text(
                failureText(failure.why), Modifier.testTag("server-personal-failure"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (detail.genres.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(detail.genres) { genre ->
                    AssistChip(
                        onClick = {
                            // The catalogue of the genre: the book's pages close, the catalogue shows what the genre has.
                            model.leaveBooks()
                            model.show(Section.Catalogue)
                            model.filter(CatalogueQuery(genre = genre))
                        },
                        label = { Text(genre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(status: String, saving: Boolean, onSet: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.server_status_label, statusName(status)), Modifier.weight(1f).testTag("server-status"))
        Box {
            TextButton(enabled = !saving, onClick = { open = true }, modifier = Modifier.testTag("server-status-change")) {
                Text(stringResource(R.string.server_set_status))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                // A status of a later server the reader cannot choose stays as it is until they choose another.
                for (choice in ReadStatuses.CHOOSABLE) {
                    DropdownMenuItem(
                        text = { Text(statusName(choice)) },
                        enabled = choice != status,
                        onClick = {
                            open = false
                            onSet(choice)
                        },
                        modifier = Modifier.testTag("server-status-$choice"),
                    )
                }
            }
        }
    }
}

@Composable
private fun Stars(rating: Int?, saving: Boolean, onRate: (Int?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (stars in 1..5) {
            val name = stringResource(R.string.server_rate, pluralStringResource(R.plurals.rate_stars, stars, stars))
            IconButton(enabled = !saving, onClick = { onRate(if (stars == rating) null else stars) }, modifier = Modifier.testTag("server-star-$stars")) {
                Icon(
                    Icons.Filled.Star, name,
                    tint = if (rating != null && stars <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        if (rating != null) {
            TextButton(enabled = !saving, onClick = { onRate(null) }) { Text(stringResource(R.string.server_rating_clear)) }
        }
    }
}

@Composable
private fun Note(note: String?, saving: Boolean, onWrite: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.server_note), style = MaterialTheme.typography.titleSmall)
        Text(
            note ?: stringResource(R.string.server_note_none), Modifier.testTag("server-note"),
            style = MaterialTheme.typography.bodyMedium,
            color = if (note == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        TextButton(enabled = !saving, onClick = { editing = true }, modifier = Modifier.testTag("server-note-edit")) {
            Text(stringResource(if (note == null) R.string.server_note_write else R.string.server_note_edit))
        }
    }
    if (editing) {
        var text by remember { mutableStateOf(note.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.server_note)) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, minLines = 3,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("server-note-text"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editing = false
                        onWrite(text)
                    },
                    modifier = Modifier.testTag("server-note-save"),
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** The reader's own collections, each said to have the book or not, to put it in or take it out; and a new one. */
@Composable
internal fun MembershipDialog(membership: Membership, model: ServerViewModel) {
    // Kept until a collection is made of it: a name the server did not take is there to be changed.
    var name by remember(membership.created) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = model::closeMembership,
        title = { Text(stringResource(R.string.server_collections_mine)) },
        text = {
            LazyColumn(Modifier.testTag("server-membership")) {
                if (membership.loading) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                membership.failure?.let { failure ->
                    item("failure") {
                        Text(
                            failureText(failure), Modifier.testTag("server-membership-failure"),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (!membership.loading && membership.owned.isEmpty()) {
                    item("none") { Text(stringResource(R.string.server_collections_none_owned), style = MaterialTheme.typography.bodySmall) }
                }
                items(membership.owned, key = { it.id }) { collection ->
                    val member = collection.id in membership.memberOf
                    val changing = collection.id in membership.busy
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !changing) { model.setInCollection(collection, !member) }
                            .testTag("server-membership-${collection.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (changing) {
                            CircularProgressIndicator(Modifier.padding(14.dp).size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Checkbox(checked = member, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                        }
                        Text(collection.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item("new") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it }, singleLine = true, enabled = !membership.creating,
                            label = { Text(stringResource(R.string.collection_new)) },
                            modifier = Modifier.weight(1f).testTag("server-membership-new"),
                        )
                        if (membership.creating) {
                            CircularProgressIndicator(Modifier.padding(14.dp).size(20.dp).testTag("server-membership-creating"), strokeWidth = 2.dp)
                        } else {
                            TextButton(
                                enabled = name.isNotBlank(),
                                onClick = { model.createCollection(name, withOpenBook = true) },
                                modifier = Modifier.testTag("server-membership-create"),
                            ) { Text(stringResource(R.string.action_create)) }
                        }
                    }
                }
                membership.createFailure?.let { failure ->
                    item("create-failure") {
                        Text(
                            failureText(failure), Modifier.testTag("server-membership-create-failure"),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item("note") {
                    Text(
                        stringResource(R.string.server_membership_note), Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = model::closeMembership) { Text(stringResource(R.string.action_done)) } },
    )
}

/**
 * The books the server lists beside the book: like it, by its author, of its series. Each opens its own page
 * over this one, and the way back returns here. Nothing listed is not a failure of the book.
 */
@Composable
internal fun RelatedSections(state: ServerState, model: ServerViewModel) {
    for (kind in Related.entries) {
        val related = state.related[kind] ?: continue
        Column(Modifier.fillMaxWidth().testTag("server-related-${kind.path}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    when (kind) {
                        Related.Similar -> R.string.server_related_similar
                        Related.Author -> R.string.server_related_author
                        Related.Series -> R.string.server_related_series
                    },
                ),
                Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
            )
            val faint = MaterialTheme.colorScheme.onSurfaceVariant
            when (related) {
                RelatedState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is RelatedState.Shown -> if (related.books.isEmpty()) {
                    Text(stringResource(R.string.server_related_none), style = MaterialTheme.typography.bodySmall, color = faint)
                } else {
                    LazyRow(contentPadding = PaddingValues(0.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(related.books, key = { it.id }) { book -> BookTile(book, state, model) { model.openRelated(book) } }
                    }
                }
                is RelatedState.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(failureText(related.why), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { model.retryRelated(kind) }) { Text(stringResource(R.string.action_retry)) }
                }
                RelatedState.Unsupported ->
                    Text(stringResource(R.string.server_related_unsupported), style = MaterialTheme.typography.bodySmall, color = faint)
            }
        }
    }
}
