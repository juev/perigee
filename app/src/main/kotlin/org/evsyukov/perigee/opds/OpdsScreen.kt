package org.evsyukov.perigee.opds

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.launch
import org.evsyukov.perigee.PlainHttpWarning
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.Catalog
import org.evsyukov.perigee.isPlainHttp
import org.evsyukov.perigee.library.folderName
import org.evsyukov.perigee.ui.Centred
import org.evsyukov.perigee.ui.EmptyState
import org.evsyukov.perigee.ui.ShelfIcon

/**
 * The reader's OPDS catalogs. [onOpen] is given the file of a downloaded book
 * the reader chose to read at once; [onClose] leaves for the library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpdsScreen(onOpen: (File) -> Unit, onClose: () -> Unit, model: OpdsViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    /** The address the form to add a catalog starts with: a suggested catalog's, or none. */
    var addingAddress by rememberSaveable { mutableStateOf("") }
    /** The id of the catalog being changed, or null. */
    var changing by rememberSaveable { mutableStateOf<Long?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val page = state.page

    val back = {
        when {
            searching -> searching = false
            !model.back() -> onClose()
        }
    }
    BackHandler(onBack = back)
    // A catalog that was added closes the form it was added from. Only a new
    // addition does: the count seen before a rotation is not news after it.
    var addedSeen by rememberSaveable { mutableStateOf(state.added) }
    LaunchedEffect(state.added) {
        if (state.added != addedSeen) {
            addedSeen = state.added
            adding = false
            changing = null
        }
    }

    Scaffold(
        topBar = {
            Column {
                if (searching) {
                    SearchBar(
                        onSearch = {
                            searching = false
                            model.search(it)
                        },
                        onClose = { searching = false },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(
                                page?.title ?: state.catalog?.title ?: stringResource(R.string.catalogs_title),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                        },
                        actions = {
                            if (state.catalog == null) {
                                IconButton(
                                    onClick = {
                                        addingAddress = ""
                                        adding = true
                                    },
                                ) { Icon(Icons.Filled.Add, stringResource(R.string.catalog_add)) }
                            } else if (page?.feed?.searchable == true) {
                                IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, stringResource(R.string.action_search)) }
                            }
                        },
                    )
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("catalog-loading"))
            }
        },
    ) { padding ->
        val failure = state.failure
        when {
            state.catalog == null -> CatalogList(
                padding, state.catalogs, model::open, onChange = { changing = it.id }, onRemove = model::remove,
                onAdd = { address ->
                    addingAddress = address
                    adding = true
                },
            )
            failure != null && page == null -> Failed(padding, failure, model::retry)
            page != null -> Entries(padding, page, state, model, onOpen)
        }
    }

    val context = LocalContext.current
    val downloadsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            model.dismissAsking()
        } else {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            model.downloadInto(uri.toString(), folderName(context, uri))
        }
    }
    if (state.asking != null) {
        AlertDialog(
            onDismissRequest = model::dismissAsking,
            title = { Text(stringResource(R.string.downloads_ask_title)) },
            text = { Text(stringResource(R.string.downloads_ask_text)) },
            confirmButton = {
                TextButton(onClick = { downloadsPicker.launch(null) }) { Text(stringResource(R.string.downloads_choose_folder)) }
            },
            dismissButton = {
                TextButton(onClick = model::downloadIntoApp) { Text(stringResource(R.string.downloads_keep_in_app)) }
            },
        )
    }

    if (adding) {
        CatalogDialog(
            catalog = null,
            initialAddress = addingAddress,
            busy = state.adding,
            failure = state.addFailure,
            onSave = model::add,
            onClose = {
                adding = false
                model.forgetAddFailure()
            },
        )
    }
    val changed = state.catalogs.firstOrNull { it.id == changing }
    if (changed != null) {
        CatalogDialog(
            catalog = changed,
            initialAddress = changed.url,
            busy = state.adding,
            failure = state.addFailure,
            onSave = { address, user, password -> model.change(changed, address, user, password) },
            onClose = {
                changing = null
                model.forgetAddFailure()
            },
        )
    }
}

@Composable
private fun failureText(failure: Failure): String = when (failure) {
    Failure.BadAddress -> stringResource(R.string.catalog_bad_address)
    Failure.Unauthorized -> stringResource(R.string.catalog_unauthorized)
    Failure.NotACatalog -> stringResource(R.string.catalog_not_a_catalog)
    Failure.Unreachable -> stringResource(R.string.catalog_unreachable)
    Failure.PasswordLost -> stringResource(R.string.catalog_password_lost)
    Failure.NotABook -> stringResource(R.string.catalog_not_a_book)
    Failure.TooLarge -> stringResource(R.string.catalog_too_large, fileSize(OpdsClient.BOOK_LIMIT))
}

@Composable
private fun fileSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

@Composable
private fun CatalogList(
    padding: PaddingValues,
    catalogs: List<Catalog>,
    onOpen: (Catalog) -> Unit,
    onChange: (Catalog) -> Unit,
    onRemove: (Catalog) -> Unit,
    /** Opens the form to add a catalog, with the address given filled in. */
    onAdd: (String) -> Unit,
) {
    val suggestions = suggestionsBesides(catalogs)
    if (catalogs.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
            Column {
                EmptyState(ShelfIcon, stringResource(R.string.catalogs_empty_title), text = stringResource(R.string.catalogs_empty)) {
                    Button(onClick = { onAdd("") }) { Text(stringResource(R.string.catalog_add)) }
                }
                Suggestions(suggestions, onAdd)
            }
        }
        return
    }
    Centred(padding) { inner ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = inner) {
            itemsIndexed(catalogs, key = { _, catalog -> catalog.id }) { _, catalog ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(catalog) }.padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(catalog.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            catalog.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onChange(catalog) }) {
                        Icon(Icons.Filled.Edit, stringResource(R.string.catalog_change_named, catalog.title))
                    }
                    IconButton(onClick = { onRemove(catalog) }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.catalog_remove, catalog.title))
                    }
                }
                HorizontalDivider()
            }
            item("suggested") { Suggestions(suggestions, onAdd) }
        }
    }
}

/** Public catalogs the reader has not added, each a tap away from the form to add it. */
@Composable
private fun Suggestions(suggestions: List<Suggestion>, onAdd: (String) -> Unit) {
    if (suggestions.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            stringResource(R.string.catalogs_suggested), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        )
        for (suggestion in suggestions) {
            Row(
                Modifier.fillMaxWidth().clickable { onAdd(suggestion.url) }.padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text(suggestion.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(suggestion.about), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onAdd(suggestion.url) }) {
                    Icon(Icons.Filled.Add, stringResource(R.string.catalog_add_named, suggestion.title))
                }
            }
        }
    }
}

@Composable
private fun Failed(padding: PaddingValues, failure: Failure, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(failureText(failure), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        if (failure != Failure.PasswordLost) OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun Entries(padding: PaddingValues, page: Page, state: OpdsState, model: OpdsViewModel, onOpen: (File) -> Unit) {
    Centred(padding) { inner ->
        LazyColumn(Modifier.fillMaxSize().testTag("catalog-entries"), contentPadding = inner) {
            state.failure?.let { failure ->
                item("failure") {
                    Text(
                        failureText(failure), Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
                    )
                }
            }
            if (page.entries.isEmpty() && !state.loading) {
                item("empty") { EmptyState(ShelfIcon, stringResource(R.string.catalog_page_empty)) }
            }
            // By place, not by id: catalogs do repeat ids within a page.
            itemsIndexed(page.entries) { index, entry ->
                // Reaching the last line asks for what follows it.
                if (index == page.entries.lastIndex && page.next != null) LaunchedEffect(page.next) { model.more() }
                EntryRow(entry, state, model, onOpen)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun EntryRow(entry: OpdsEntry, state: OpdsState, model: OpdsViewModel, onOpen: (File) -> Unit) {
    val leads = entry.feed != null
    Row(
        Modifier.fillMaxWidth().clickable(enabled = leads) { model.go(entry) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (entry.downloads.isNotEmpty() || entry.cover != null) {
            EntryCover(entry, model, Modifier.padding(end = 12.dp).size(width = 52.dp, height = 76.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (entry.authors.isNotEmpty()) {
                Text(entry.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (entry.summary.isNotEmpty()) {
                Text(
                    entry.summary, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val files = readableDownloads(entry)
            for (link in files) DownloadLine(entry, link, state.downloads[link.url], model, onOpen)
            if (entry.downloads.isNotEmpty() && files.isEmpty()) {
                Text(
                    stringResource(R.string.catalog_no_readable_file), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The files of [entry] the app reads: one per format, the catalog's first choice of each. */
internal fun readableDownloads(entry: OpdsEntry): List<OpdsLink> =
    entry.downloads.filter { it.format.isNotEmpty() }.distinctBy { it.format }

@Composable
private fun DownloadLine(entry: OpdsEntry, link: OpdsLink, download: Download?, model: OpdsViewModel, onOpen: (File) -> Unit) {
    val format = link.format.uppercase()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (download) {
            null -> TextButton(onClick = { model.download(entry, link) }) { Text(stringResource(R.string.catalog_download, format)) }
            is Download.Running -> {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.catalog_downloading, format), style = MaterialTheme.typography.bodySmall)
                    val total = download.total
                    if (total != null && total > 0) {
                        LinearProgressIndicator(
                            progress = { (download.received.toFloat() / total).coerceIn(0f, 1f) },
                            Modifier.fillMaxWidth().testTag("download-progress"),
                        )
                        Text(
                            stringResource(R.string.catalog_download_progress, fileSize(download.received), fileSize(total)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("download-progress"))
                        if (download.received > 0) {
                            Text(
                                fileSize(download.received), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                TextButton(onClick = { model.cancelDownload(link) }) { Text(stringResource(R.string.action_cancel)) }
            }
            is Download.Done -> {
                Text(stringResource(R.string.catalog_downloaded), style = MaterialTheme.typography.bodySmall)
                val scope = rememberCoroutineScope()
                TextButton(onClick = { scope.launch { model.fileOf(download.bookId)?.let(onOpen) } }) {
                    Text(stringResource(R.string.catalog_read))
                }
            }
            is Download.Failed -> {
                Text(failureText(download.why), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { model.download(entry, link) }) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
private fun EntryCover(entry: OpdsEntry, model: OpdsViewModel, modifier: Modifier) {
    val picture by produceState<ImageBitmap?>(null, entry.cover) { value = entry.cover?.let { model.cover(it) } }
    val shape = RoundedCornerShape(4.dp)
    val shown = picture
    if (shown != null) {
        Image(shown, null, modifier.clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
private fun SearchBar(onSearch: (String) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.catalog_search_hint)) },
        leadingIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.action_clear)) }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch(query) }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth().statusBarsPadding().focusRequester(focus).testTag("catalog-search"),
    )
}

/** The form a catalog is added with, or changed when [catalog] is given. */
@Composable
private fun CatalogDialog(
    catalog: Catalog?,
    initialAddress: String,
    busy: Boolean,
    failure: Failure?,
    onSave: (String, String, String) -> Unit,
    onClose: () -> Unit,
) {
    var address by rememberSaveable { mutableStateOf(initialAddress) }
    var user by rememberSaveable { mutableStateOf(catalog?.user.orEmpty()) }
    // Not saved with the screen: a password is not written out for a rotation's sake.
    var password by remember { mutableStateOf("") }
    var warning by remember { mutableStateOf(false) }
    if (warning) {
        PlainHttpWarning(
            onSend = {
                warning = false
                onSave(address, user, password)
            },
            onDismiss = { warning = false },
        )
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(if (catalog == null) R.string.catalog_add else R.string.catalog_change)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.catalog_address)) },
                    placeholder = { Text("https://…/opds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("catalog-address"),
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it }, singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.catalog_user)) },
                    modifier = Modifier.fillMaxWidth().testTag("catalog-user"),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.catalog_password)) },
                    // The saved password is never shown; left empty, it stays.
                    supportingText = if (catalog != null && catalog.user.isNotEmpty()) {
                        { Text(stringResource(R.string.catalog_password_kept)) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("catalog-password"),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failure != null) Text(failureText(failure), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && address.isNotBlank(),
                onClick = {
                    // A catalog open to everyone has nothing to give away.
                    if (isPlainHttp(address) && (user.isNotBlank() || password.isNotEmpty())) warning = true else onSave(address, user, password)
                },
            ) {
                Text(stringResource(if (catalog == null) R.string.catalog_check_and_add else R.string.catalog_check_and_save))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } },
    )
}
