package org.evsyukov.perigee.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import org.evsyukov.perigee.R
import org.evsyukov.perigee.sync.RemoteCollection
import org.evsyukov.perigee.ui.Centred
import org.evsyukov.perigee.ui.EmptyState
import org.evsyukov.perigee.ui.ShelfIcon

/**
 * The reader's collections on the server and those other readers share, or the books of the one opened.
 * Only the reader's own can be renamed or deleted; deleting one leaves its books where they are.
 */
@Composable
internal fun Collections(
    padding: PaddingValues,
    state: ServerState,
    model: ServerViewModel,
    onOpen: (File) -> Unit,
    list: LazyListState,
    books: LazyListState,
) {
    val open = state.collection
    if (open == null) CollectionList(padding, state, model, list) else CollectionBooks(padding, open, state, model, onOpen, books)
    state.collectionFailure?.let { failure ->
        AlertDialog(
            onDismissRequest = model::dismissCollectionFailure,
            text = { Text(failureText(failure)) },
            confirmButton = { TextButton(onClick = model::dismissCollectionFailure) { Text(stringResource(R.string.action_done)) } },
            modifier = Modifier.testTag("server-collection-failure"),
        )
    }
}

@Composable
private fun CollectionList(padding: PaddingValues, state: ServerState, model: ServerViewModel, list: LazyListState) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<RemoteCollection?>(null) }
    var deleting by remember { mutableStateOf<RemoteCollection?>(null) }
    val collections = state.collections
    Centred(padding) { inner ->
        LazyColumn(Modifier.fillMaxSize().testTag("server-collections"), state = list, contentPadding = inner) {
            item("new") {
                OutlinedButton(
                    enabled = !state.collectionBusy, onClick = { creating = true },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("server-collection-new"),
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.collection_new))
                }
            }
            collections.failure?.let { failure -> item("failure") { Failure(failure, model::loadCollections) } }
            if (collections.loaded && collections.list.isEmpty() && collections.failure == null) {
                item("empty") {
                    EmptyState(ShelfIcon, stringResource(R.string.server_collections_empty), text = stringResource(R.string.server_collections_empty_text))
                }
            }
            items(collections.list, key = { it.id }) { collection ->
                CollectionRow(
                    collection, busy = state.collectionBusy, onClick = { model.openCollection(collection) },
                    onRename = { renaming = collection }, onDelete = { deleting = collection },
                )
                HorizontalDivider()
            }
        }
    }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.collection_new), initial = "", action = stringResource(R.string.action_create),
            onDone = {
                creating = false
                model.createCollection(it)
            },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { collection ->
        NameDialog(
            title = stringResource(R.string.action_rename), initial = collection.name, action = stringResource(R.string.action_save),
            onDone = {
                renaming = null
                model.renameCollection(collection, it)
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { collection ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.server_collection_delete_title, collection.name)) },
            // Deleting is of the list alone: the reader is told so before anything goes.
            text = { Text(stringResource(R.string.server_collection_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        model.deleteCollection(collection)
                    },
                    modifier = Modifier.testTag("server-collection-delete-confirm"),
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Whose a collection is, and whether others see it. */
@Composable
internal fun ownership(collection: RemoteCollection): String = when {
    !collection.isOwner -> stringResource(R.string.server_collection_shared)
    collection.isPublic -> stringResource(R.string.server_collection_public)
    else -> stringResource(R.string.server_collection_private)
}

@Composable
private fun CollectionRow(collection: RemoteCollection, busy: Boolean, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
            .testTag("server-collection-${collection.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(collection.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                pluralStringResource(R.plurals.server_collection_books, collection.bookCount, collection.bookCount) + " · " + ownership(collection),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Another reader's collection is only to be looked through: nothing is offered that the server would refuse.
        if (collection.isOwner) {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(enabled = !busy, onClick = { open = true }) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.collection_actions, collection.name))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        onClick = {
                            open = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = {
                            open = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionBooks(
    padding: PaddingValues,
    view: CollectionView,
    state: ServerState,
    model: ServerViewModel,
    onOpen: (File) -> Unit,
    list: LazyListState,
) {
    Centred(padding) { inner ->
        LazyColumn(Modifier.fillMaxSize().testTag("server-collection-books"), state = list, contentPadding = inner) {
            item("about") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(view.collection.name, style = MaterialTheme.typography.titleMedium)
                    Text(ownership(view.collection), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            view.failure?.let { failure -> item("failure") { Failure(failure, model::retryCollection) } }
            // Empty only once the server has no more of it: a first part of books the app does not read is not "no books".
            if (view.loaded && view.books.isEmpty() && !view.more && !view.loading && view.failure == null) {
                item("empty") {
                    if (view.query.isEmpty()) {
                        EmptyState(ShelfIcon, stringResource(R.string.server_collection_empty))
                    } else {
                        EmptyState(Icons.Filled.Search, stringResource(R.string.nothing_found), text = stringResource(R.string.nothing_found_for, view.query))
                    }
                }
            }
            items(view.books, key = { it.id }) { book ->
                BookRow(book, state, model, onOpen, onClick = { model.openBook(book) })
                HorizontalDivider()
            }
            // Reaching the end asks for the part that follows, by the parts the server gave, not the books shown:
            // a part of only books the app does not read shows nothing, and the one after it is asked for all the same.
            if (view.more && view.failure == null) {
                item("more") {
                    LaunchedEffect(view.pages) { model.moreOfCollection() }
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp).testTag("server-collection-more"))
                }
            }
        }
    }
}

/** Asks for a collection's name: a new one, or another for one there is. */
@Composable
internal fun NameDialog(title: String, initial: String, action: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.collection_name)) },
                modifier = Modifier.fillMaxWidth().testTag("server-collection-name"),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onDone(name) }, modifier = Modifier.testTag("server-collection-name-done")) {
                Text(action)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
