package org.evsyukov.perigee.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.evsyukov.perigee.R
import org.evsyukov.perigee.sync.DeviceChannel
import org.evsyukov.perigee.sync.ShelfKind
import org.evsyukov.perigee.ui.Centred
import org.evsyukov.perigee.ui.EmptyState
import org.evsyukov.perigee.ui.ShelfIcon

/**
 * The server's home page: how syncing stands, then its shelves, each a sample the server chose. A shelf
 * that is empty is not shown; one that could not be filled, or that the server has not, says so in its
 * own place and leaves the others as they are.
 */
@Composable
internal fun Home(padding: PaddingValues, state: ServerState, model: ServerViewModel, list: LazyListState) {
    Centred(padding) { inner ->
        LazyColumn(Modifier.fillMaxSize().testTag("server-home"), state = list, contentPadding = inner) {
            val channel = state.channel
            if (channel != null && channel != DeviceChannel.Ready) {
                item("channel") { Channel(channel, state, model::devicePassword, model::newDevicePassword) }
            }
            val account = state.account
            if (channel == DeviceChannel.Ready && account != null) item("sync") { Synced(account, state.unsent) }
            // The server not answering is the home page's business too; a filter of the catalogue it refuses is not.
            state.failure?.takeIf { it != ServerFailure.Unsupported }?.let { failure ->
                item("failure") { Failure(failure, model::retry) }
            }
            val shelves = ShelfKind.DEFAULT.map { it to state.shelves[it] }
            val answered = shelves.mapNotNull { it.second }.filter { it != ShelfState.Loading }
            when {
                answered.size == shelves.size && answered.all { it == ShelfState.Unsupported } -> item("unsupported") {
                    EmptyState(ShelfIcon, stringResource(R.string.server_home_unsupported)) {
                        OutlinedButton(onClick = { model.show(Section.Catalogue) }) { Text(stringResource(R.string.server_open_catalogue)) }
                    }
                }
                answered.size == shelves.size && answered.all { it is ShelfState.Shown && it.books.isEmpty() && it.leftOut == 0 } -> item("empty") {
                    EmptyState(ShelfIcon, stringResource(R.string.server_home_empty), text = stringResource(R.string.server_home_empty_text)) {
                        OutlinedButton(onClick = { model.show(Section.Catalogue) }) { Text(stringResource(R.string.server_open_catalogue)) }
                    }
                }
                else -> {
                    for ((kind, shelf) in shelves) {
                        // A shelf the server has, and has nothing on, is not shown at all.
                        if (shelf is ShelfState.Shown && shelf.books.isEmpty() && shelf.leftOut == 0) continue
                        item(kind.type) { ShelfRow(kind, shelf ?: ShelfState.Loading, state, model) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun shelfName(kind: ShelfKind): String = stringResource(
    when (kind) {
        ShelfKind.ContinueReading -> R.string.server_shelf_continue_reading
        ShelfKind.WantToRead -> R.string.server_shelf_want_to_read
        ShelfKind.RecentlyAdded -> R.string.server_shelf_recently_added
        ShelfKind.UpNext -> R.string.server_shelf_up_next
        ShelfKind.Random -> R.string.server_shelf_random
    },
)

@Composable
private fun ShelfRow(kind: ShelfKind, shelf: ShelfState, state: ServerState, model: ServerViewModel) {
    val name = shelfName(kind)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("shelf-${kind.type}")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            // Only a shelf that is a query of the catalogue has the whole of it to see; a sample has nothing more behind it.
            if (catalogueOf(kind) != null && shelf is ShelfState.Shown && shelf.books.isNotEmpty()) {
                val all = stringResource(R.string.server_see_all_of, name)
                TextButton(onClick = { model.seeAll(kind) }, modifier = Modifier.semantics { contentDescription = all }) {
                    Text(stringResource(R.string.server_see_all))
                }
            }
        }
        when (shelf) {
            ShelfState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            is ShelfState.Shown -> {
                if (shelf.books.isNotEmpty()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(shelf.books, key = { it.id }) { book -> BookTile(book, state, model) { model.openBook(book) } }
                    }
                }
                if (shelf.leftOut > 0) {
                    Text(
                        stringResource(R.string.server_shelf_left_out), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is ShelfState.Failed -> Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.server_shelf_failed), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { model.retryShelf(kind) }, modifier = Modifier.testTag("shelf-retry-${kind.type}")) {
                    Text(stringResource(R.string.action_retry))
                }
            }
            ShelfState.Unsupported -> Text(
                stringResource(R.string.server_shelf_unsupported), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What went wrong with the server as a whole, and a way to ask again. */
@Composable
internal fun Failure(failure: ServerFailure, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(failureText(failure), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}
