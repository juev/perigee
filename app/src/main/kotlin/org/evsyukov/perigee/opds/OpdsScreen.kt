package org.evsyukov.perigee.opds

import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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

/**
 * The reader's OPDS catalogs. [onOpen] is given the file of a downloaded book
 * the reader chose to read at once; [onClose] leaves for the library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpdsScreen(onOpen: (File) -> Unit, onClose: () -> Unit, model: OpdsViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
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
                                IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, stringResource(R.string.catalog_add)) }
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
            state.catalog == null -> CatalogList(padding, state.catalogs, model::open, model::remove, onAdd = { adding = true })
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
        AddCatalogDialog(
            busy = state.adding,
            failure = state.addFailure,
            onAdd = model::add,
            onClose = {
                adding = false
                model.forgetAddFailure()
            },
        )
    }
}

@Composable
private fun failureText(failure: Failure): String = stringResource(
    when (failure) {
        Failure.BadAddress -> R.string.catalog_bad_address
        Failure.Unauthorized -> R.string.catalog_unauthorized
        Failure.NotACatalog -> R.string.catalog_not_a_catalog
        Failure.Unreachable -> R.string.catalog_unreachable
        Failure.PasswordLost -> R.string.catalog_password_lost
        Failure.NotABook -> R.string.catalog_not_a_book
    },
)

@Composable
private fun CatalogList(
    padding: PaddingValues,
    catalogs: List<Catalog>,
    onOpen: (Catalog) -> Unit,
    onRemove: (Catalog) -> Unit,
    onAdd: () -> Unit,
) {
    if (catalogs.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.catalogs_empty), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onAdd) { Text(stringResource(R.string.catalog_add)) }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
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
                IconButton(onClick = { onRemove(catalog) }) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.catalog_remove, catalog.title))
                }
            }
            HorizontalDivider()
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
    LazyColumn(Modifier.fillMaxSize().testTag("catalog-entries"), contentPadding = padding) {
        state.failure?.let { failure ->
            item("failure") {
                Text(
                    failureText(failure), Modifier.fillMaxWidth().padding(16.dp),
                    color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
                )
            }
        }
        if (page.entries.isEmpty() && !state.loading) {
            item("empty") {
                Text(
                    stringResource(R.string.catalog_page_empty), Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            // One file per format the app reads; the catalog's first choice of each.
            val files = entry.downloads.filter { it.format.isNotEmpty() }.distinctBy { it.format }
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

@Composable
private fun DownloadLine(entry: OpdsEntry, link: OpdsLink, download: Download?, model: OpdsViewModel, onOpen: (File) -> Unit) {
    val format = link.format.uppercase()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (download) {
            null -> TextButton(onClick = { model.download(entry, link) }) { Text(stringResource(R.string.catalog_download, format)) }
            Download.Running -> {
                CircularProgressIndicator(Modifier.padding(vertical = 12.dp).size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.catalog_downloading, format), style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun AddCatalogDialog(busy: Boolean, failure: Failure?, onAdd: (String, String, String) -> Unit, onClose: () -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    // Not saved with the screen: a password is not written out for a rotation's sake.
    var password by remember { mutableStateOf("") }
    var warning by remember { mutableStateOf(false) }
    if (warning) {
        PlainHttpWarning(
            onSend = {
                warning = false
                onAdd(address, user, password)
            },
            onDismiss = { warning = false },
        )
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.catalog_add)) },
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
                    if (isPlainHttp(address) && (user.isNotBlank() || password.isNotEmpty())) warning = true else onAdd(address, user, password)
                },
            ) {
                Text(stringResource(R.string.catalog_check_and_add))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } },
    )
}
