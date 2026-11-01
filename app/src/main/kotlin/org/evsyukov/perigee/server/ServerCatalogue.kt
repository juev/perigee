package org.evsyukov.perigee.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.File
import org.evsyukov.perigee.R
import org.evsyukov.perigee.sync.CatalogueQuery
import org.evsyukov.perigee.sync.CatalogueSort
import org.evsyukov.perigee.sync.ReadStatuses
import org.evsyukov.perigee.ui.Centred
import org.evsyukov.perigee.ui.EmptyState
import org.evsyukov.perigee.ui.ShelfIcon

/**
 * The server's catalogue: all its books the app reads, or those its phrase and filters find, in the order
 * chosen, a part at a time. The filters show above the list, each to be taken off by itself.
 */
@Composable
internal fun Catalogue(padding: PaddingValues, state: ServerState, model: ServerViewModel, onOpen: (File) -> Unit, list: LazyListState) {
    var filtering by rememberSaveable { mutableStateOf(false) }
    Centred(padding) { inner ->
        LazyColumn(Modifier.fillMaxSize().testTag("server-books"), state = list, contentPadding = inner) {
            item("filters") {
                FilterLine(state.filters, onOpen = { filtering = true }, onChange = model::filter)
            }
            state.failure?.let { failure ->
                item("failure") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Failure(failure, model::retry)
                        if (failure == ServerFailure.Unsupported && state.filters != CatalogueQuery()) {
                            TextButton(onClick = { model.filter(CatalogueQuery()) }) { Text(stringResource(R.string.server_filters_clear)) }
                        }
                    }
                }
            }
            if (state.books.isEmpty() && !state.loading && state.failure == null) {
                item("empty") {
                    when {
                        state.query.isNotEmpty() -> EmptyState(
                            Icons.Filled.Search, stringResource(R.string.nothing_found), text = stringResource(R.string.nothing_found_for, state.query),
                        )
                        state.filters.filtered -> EmptyState(ShelfIcon, stringResource(R.string.server_filtered_empty)) {
                            OutlinedButton(onClick = { model.filter(CatalogueQuery()) }) { Text(stringResource(R.string.server_filters_clear)) }
                        }
                        else -> EmptyState(ShelfIcon, stringResource(R.string.catalog_page_empty))
                    }
                }
            }
            itemsIndexed(state.books, key = { _, book -> book.id }) { index, book ->
                // Reaching the last line asks for what follows it.
                if (index == state.books.lastIndex && state.more) LaunchedEffect(state.books.size) { model.more() }
                BookRow(book, state, model, onOpen, onClick = { model.openBook(book) })
                HorizontalDivider()
            }
        }
    }
    if (filtering) {
        LaunchedEffect(Unit) { model.loadLibraries() }
        FiltersSheet(
            state.filters, state.libraries, state.librariesFailure, onRetryLibraries = model::loadLibraries,
            onApply = {
                filtering = false
                model.filter(it)
            },
            onDismiss = { filtering = false },
        )
    }
}

@Composable
internal fun sortName(sort: CatalogueSort): String = stringResource(
    when (sort) {
        CatalogueSort.Title -> R.string.server_sort_title
        CatalogueSort.Author -> R.string.server_sort_author
        CatalogueSort.RecentlyAdded -> R.string.server_sort_added
        CatalogueSort.Series -> R.string.server_sort_series
    },
)

/** The filters in force, each taken off by a tap on it, and the way to all of them. */
@Composable
private fun FilterLine(filters: CatalogueQuery, onOpen: () -> Unit, onChange: (CatalogueQuery) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().testTag("server-filters"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item("all") {
            FilterChip(
                selected = filters.filtered, onClick = onOpen, label = { Text(stringResource(R.string.server_filters)) },
                modifier = Modifier.testTag("server-filters-open"),
            )
        }
        item("sort") { FilterChip(selected = filters.sort != CatalogueSort.Title, onClick = onOpen, label = { Text(sortName(filters.sort)) }) }
        val active = listOfNotNull(
            filters.status.ifEmpty { null }?.let { Triple(R.string.server_filter_status, it, filters.copy(status = "")) },
            filters.author.ifEmpty { null }?.let { Triple(R.string.server_filter_author, it, filters.copy(author = "")) },
            filters.series.ifEmpty { null }?.let { Triple(R.string.server_filter_series, it, filters.copy(series = "")) },
            filters.genre.ifEmpty { null }?.let { Triple(R.string.server_filter_genre, it, filters.copy(genre = "")) },
            filters.library.ifEmpty { null }?.let { Triple(R.string.server_filter_library, it, filters.copy(library = "")) },
        )
        for ((name, value, without) in active) {
            item("filter-$name") {
                val shown = if (name == R.string.server_filter_status) statusName(value) else value
                InputChip(
                    selected = true, onClick = { onChange(without) },
                    label = { Text("${stringResource(name)}: $shown") },
                    trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.action_clear)) },
                )
            }
        }
    }
}

/** All of the catalogue's filters and its order, to be chosen and then put in force together. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FiltersSheet(
    filters: CatalogueQuery,
    libraries: List<String>,
    librariesFailure: ServerFailure?,
    onRetryLibraries: () -> Unit,
    onApply: (CatalogueQuery) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(filters) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .testTag("server-filters-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.server_filters), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.server_filter_status), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = chosen.status.isEmpty(), onClick = { chosen = chosen.copy(status = "") }, label = { Text(stringResource(R.string.server_filter_any)) })
                for (status in ReadStatuses.CHOOSABLE) {
                    FilterChip(selected = chosen.status == status, onClick = { chosen = chosen.copy(status = status) }, label = { Text(statusName(status)) })
                }
            }
            OutlinedTextField(
                value = chosen.author, onValueChange = { chosen = chosen.copy(author = it) }, singleLine = true,
                label = { Text(stringResource(R.string.server_filter_author)) }, modifier = Modifier.fillMaxWidth().testTag("server-filter-author"),
            )
            OutlinedTextField(
                value = chosen.series, onValueChange = { chosen = chosen.copy(series = it) }, singleLine = true,
                label = { Text(stringResource(R.string.server_filter_series)) }, modifier = Modifier.fillMaxWidth().testTag("server-filter-series"),
            )
            OutlinedTextField(
                value = chosen.genre, onValueChange = { chosen = chosen.copy(genre = it) }, singleLine = true,
                label = { Text(stringResource(R.string.server_filter_genre)) },
                supportingText = { Text(stringResource(R.string.server_filter_genre_hint)) },
                modifier = Modifier.fillMaxWidth().testTag("server-filter-genre"),
            )
            // Only the libraries the server says the reader sees; none is offered when it does not say.
            if (libraries.isNotEmpty() || chosen.library.isNotEmpty()) {
                Text(stringResource(R.string.server_filter_library), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = chosen.library.isEmpty(), onClick = { chosen = chosen.copy(library = "") }, label = { Text(stringResource(R.string.server_filter_any)) })
                    for (name in (libraries + chosen.library).filter { it.isNotEmpty() }.distinct()) {
                        FilterChip(selected = chosen.library == name, onClick = { chosen = chosen.copy(library = name) }, label = { Text(name) })
                    }
                }
            }
            // A server without the list says so; one that did not answer can be asked again.
            librariesFailure?.let { failure ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.server_filter_library) + ": " + failureText(failure), Modifier.weight(1f).testTag("server-libraries-failure"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failure == ServerFailure.Unsupported) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                    if (failure != ServerFailure.Unsupported) {
                        TextButton(onClick = onRetryLibraries) { Text(stringResource(R.string.action_retry)) }
                    }
                }
            }
            Text(stringResource(R.string.server_sort), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sort in CatalogueSort.entries) {
                    FilterChip(selected = chosen.sort == sort, onClick = { chosen = chosen.copy(sort = sort) }, label = { Text(sortName(sort)) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { chosen = CatalogueQuery() }) { Text(stringResource(R.string.server_filters_clear)) }
                Button(onClick = { onApply(chosen) }, modifier = Modifier.testTag("server-filters-apply")) { Text(stringResource(R.string.action_apply)) }
            }
        }
    }
}
