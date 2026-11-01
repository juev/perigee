package org.evsyukov.perigee.server

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.launch
import org.evsyukov.perigee.PlainHttpWarning
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.Account
import org.evsyukov.perigee.data.Library
import org.evsyukov.perigee.isPlainHttp
import org.evsyukov.perigee.library.folderName
import org.evsyukov.perigee.sync.BookOrbit
import org.evsyukov.perigee.sync.DeviceChannel
import org.evsyukov.perigee.sync.ReadStatuses
import org.evsyukov.perigee.sync.RemoteBook
import org.evsyukov.perigee.sync.RemoteFile
import org.evsyukov.perigee.sync.SignInProvider
import org.evsyukov.perigee.ui.READABLE_WIDTH
import org.evsyukov.perigee.ui.ShelfIcon

/**
 * The bookOrbit server the reader keeps books on: once signed in, its home page's shelves, its catalogue and
 * the reader's collections, each a part of its own under the top bar. [onOpen] is given the file of a book of
 * the server the reader chose to read; [onClose] leaves for the library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(onOpen: (File) -> Unit, onClose: () -> Unit, model: ServerViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    val account = state.account
    val signedIn = account != null && !state.sessionOver
    val inCollection = state.section == Section.Collections && state.collection != null

    // Each part keeps its place while another is shown, and while a book's page lies over it.
    val homeList = rememberLazyListState()
    val catalogueList = rememberLazyListState()
    val collectionsList = rememberLazyListState()
    val collectionBooksList = rememberSaveable(state.collection?.collection?.id, saver = LazyListState.Saver) { LazyListState(0, 0) }

    /** One step back, as the arrow of the top bar goes: the way to the library is always this one. */
    fun stepBack(): Boolean {
        val collection = state.collection
        when {
            state.opened != null -> model.closeBook()
            searching -> searching = false
            inCollection && collection != null && collection.query.isNotEmpty() -> model.searchCollection("")
            inCollection -> model.closeCollection()
            state.section == Section.Catalogue && state.query.isNotEmpty() -> model.search("")
            else -> return false
        }
        return true
    }
    val back = { if (!stepBack()) onClose() }
    // The system's Back returns to the home page first, the part the screen opens with.
    BackHandler {
        when {
            stepBack() -> Unit
            signedIn && state.section != Section.Home -> model.show(Section.Home)
            else -> onClose()
        }
    }

    Scaffold(
        topBar = {
            Column {
                if (searching) {
                    SearchBar(
                        hint = stringResource(if (inCollection) R.string.server_collection_search_hint else R.string.server_search_hint),
                        onSearch = {
                            searching = false
                            if (inCollection) {
                                model.searchCollection(it)
                            } else {
                                model.show(Section.Catalogue)
                                model.search(it)
                            }
                        },
                        onClose = { searching = false },
                    )
                    // Where the phrase is looked for: the whole catalogue, or the open collection alone.
                    Text(
                        if (inCollection) {
                            stringResource(R.string.server_scope_collection, state.collection?.collection?.name.orEmpty())
                        } else {
                            stringResource(R.string.server_scope_catalogue)
                        },
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("server-search-scope"),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TopAppBar(
                        title = {
                            val collection = state.collection
                            val title = when {
                                inCollection && collection != null -> collection.query.ifEmpty { collection.collection.name }
                                state.section == Section.Catalogue && state.query.isNotEmpty() -> state.query
                                else -> stringResource(R.string.server_title)
                            }
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        navigationIcon = {
                            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                        },
                        actions = {
                            if (signedIn && (state.section != Section.Collections || inCollection)) {
                                IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, stringResource(R.string.action_search)) }
                            }
                            if (account != null) AccountMenu(account, model::signOut)
                        },
                    )
                }
                if (signedIn) Sections(state.section, model::show)
                val busy = when (state.section) {
                    Section.Catalogue -> state.loading
                    Section.Collections -> state.collection?.loading ?: state.collections.loading
                    Section.Home -> state.loading && state.channel == null
                }
                if (signedIn && busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("server-loading"))
            }
        },
    ) { padding ->
        when {
            !state.loaded -> Unit
            !signedIn -> SignIn(padding, state, model::signIn, model::signInThroughProvider, model::chooseProvider, model::dismissProviders)
            else -> when (state.section) {
                Section.Home -> Home(padding, state, model, homeList)
                Section.Catalogue -> Catalogue(padding, state, model, onOpen, catalogueList)
                Section.Collections -> Collections(padding, state, model, onOpen, collectionsList, collectionBooksList)
            }
        }
    }

    val context = LocalContext.current
    // The provider's page opens over the app, in the browser's tab; coming back from it closes the tab.
    LaunchedEffect(state.providerPage) {
        val page = state.providerPage ?: return@LaunchedEffect
        val opened = try {
            CustomTabsIntent.Builder().build().launchUrl(context, page.toUri())
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
        model.providerPageShown(opened)
    }
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
    // The book's page lies over the part it was opened from, which keeps its place and what it was looked up by underneath.
    if (signedIn) state.opened?.let { book -> ServerBookPage(book, state, model, onOpen) }
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
}

/** The tabs of the parts of the screen; on a wide screen they keep to the width the lists below keep to. */
@Composable
private fun Sections(section: Section, onShow: (Section) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        PrimaryTabRow(selectedTabIndex = section.ordinal, modifier = Modifier.widthIn(max = READABLE_WIDTH).testTag("server-sections")) {
            for (each in Section.entries) {
                Tab(
                    selected = each == section,
                    onClick = { onShow(each) },
                    text = { Text(stringResource(sectionName(each)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("server-section-${each.name.lowercase()}"),
                )
            }
        }
    }
}

internal fun sectionName(section: Section): Int = when (section) {
    Section.Home -> R.string.server_section_home
    Section.Catalogue -> R.string.server_section_catalogue
    Section.Collections -> R.string.server_section_collections
}

@Composable
internal fun failureText(failure: ServerFailure): String = stringResource(
    when (failure) {
        ServerFailure.BadAddress -> R.string.server_bad_address
        ServerFailure.NotAServer -> R.string.server_not_a_server
        ServerFailure.WrongPassword -> R.string.server_wrong_password
        ServerFailure.TooManyAttempts -> R.string.server_too_many_attempts
        ServerFailure.PasswordLoginDisabled -> R.string.server_password_login_disabled
        ServerFailure.Unreachable -> R.string.server_unreachable
        ServerFailure.NotAllowed -> R.string.server_not_allowed
        ServerFailure.NotABook -> R.string.catalog_not_a_book
        ServerFailure.NoProviders -> R.string.server_no_providers
        ServerFailure.SignInExpired -> R.string.server_sign_in_expired
        ServerFailure.SignInRefused -> R.string.server_sign_in_refused
        ServerFailure.NoBrowser -> R.string.server_no_browser
        ServerFailure.Unsupported -> R.string.server_unsupported_feature
        ServerFailure.NotFound -> R.string.server_not_found
        ServerFailure.NameTaken -> R.string.server_name_taken
        ServerFailure.NotSaved -> R.string.server_not_saved
    },
)

/** A status in the reader's words; one the app has no name for is shown as the server calls it. */
@Composable
internal fun statusName(status: String): String = when (status) {
    "", ReadStatuses.UNREAD -> stringResource(R.string.server_status_unread)
    ReadStatuses.WANT_TO_READ -> stringResource(R.string.server_status_want_to_read)
    ReadStatuses.READING -> stringResource(R.string.server_status_reading)
    ReadStatuses.ON_HOLD -> stringResource(R.string.server_status_on_hold)
    ReadStatuses.READ -> stringResource(R.string.server_status_read)
    ReadStatuses.ABANDONED -> stringResource(R.string.server_status_abandoned)
    "rereading" -> stringResource(R.string.server_status_rereading)
    "skimmed" -> stringResource(R.string.server_status_skimmed)
    else -> status.replace('_', ' ')
}

@Composable
private fun AccountMenu(account: Account, onSignOut: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.server_sign_out))
                        Text(
                            stringResource(R.string.server_signed_in_as, account.name, account.url.substringAfter("://")),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    open = false
                    onSignOut()
                },
            )
        }
    }
}

@Composable
private fun SignIn(
    padding: PaddingValues,
    state: ServerState,
    onSignIn: (String, String, String) -> Unit,
    onProvider: (String) -> Unit,
    onChoose: (String, SignInProvider) -> Unit,
    onDismissProviders: () -> Unit,
) {
    val account = state.account
    var address by rememberSaveable { mutableStateOf(account?.url.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(account?.user.orEmpty()) }
    // Not saved with the screen: a password is not written out for a rotation's sake.
    var password by remember { mutableStateOf("") }
    var warning by remember { mutableStateOf(false) }
    val busy = state.signingIn
    if (warning) {
        PlainHttpWarning(
            onSend = {
                warning = false
                onSignIn(address, user, password)
            },
            onDismiss = { warning = false },
        )
    }
    if (state.providers.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismissProviders,
            title = { Text(stringResource(R.string.server_choose_provider)) },
            text = {
                Column {
                    for (provider in state.providers) {
                        TextButton(onClick = { onChoose(address, provider) }, modifier = Modifier.fillMaxWidth()) { Text(provider.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismissProviders) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    // On a wide screen the form keeps the width of a phone's, in the middle.
    Box(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 4.dp).size(72.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ShelfIcon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(
                stringResource(if (state.sessionOver) R.string.server_session_over else R.string.server_about),
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            OutlinedTextField(
                value = address, onValueChange = { address = it }, singleLine = true, enabled = !busy,
                label = { Text(stringResource(R.string.server_address)) },
                placeholder = { Text("https://…") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("server-address"),
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it }, singleLine = true, enabled = !busy,
                label = { Text(stringResource(R.string.server_user)) },
                modifier = Modifier.fillMaxWidth().testTag("server-user"),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true, enabled = !busy,
                label = { Text(stringResource(R.string.catalog_password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("server-password"),
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.signInFailure?.let { failure ->
                val text = if (failure == ServerFailure.SignInRefused && state.signInRefusal.isNotBlank()) {
                    stringResource(R.string.server_sign_in_refused_because, state.signInRefusal)
                } else {
                    failureText(failure)
                }
                Text(text, color = MaterialTheme.colorScheme.error)
            }
            Button(
                enabled = !busy && address.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
                onClick = { if (isPlainHttp(address)) warning = true else onSignIn(address, user, password) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("server-sign-in"),
            ) { Text(stringResource(R.string.server_sign_in)) }
            // The password is not asked for: the provider asks for what it wants on its own page.
            OutlinedButton(
                enabled = !busy && address.isNotBlank(),
                onClick = { onProvider(address) },
                modifier = Modifier.fillMaxWidth().testTag("server-sign-in-provider"),
            ) { Text(stringResource(R.string.server_sign_in_provider)) }
        }
    }
}

/** How the syncing of reading went last, and what is still to reach the server. */
@Composable
internal fun Synced(account: Account, unsent: Int) {
    val now = System.currentTimeMillis()
    fun ago(at: Long) = DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("server-sync")) {
        when {
            account.unreachableSince > 0 -> Text(
                stringResource(R.string.server_sync_unreachable, ago(account.unreachableSince)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
            account.syncedAt > 0 -> Text(
                stringResource(R.string.server_synced_at, ago(account.syncedAt)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (unsent > 0) {
            Text(
                pluralStringResource(R.plurals.server_unsent, unsent, unsent),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What stands between the reader and the syncing of their reading, and what they can do about it. */
@Composable
internal fun Channel(channel: DeviceChannel, state: ServerState, onPassword: (String) -> Unit, onNewPassword: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("server-channel")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (channel) {
                DeviceChannel.PasswordNeeded -> {
                    var password by remember { mutableStateOf("") }
                    Text(stringResource(R.string.server_channel_password_needed, state.account?.deviceUser.orEmpty()))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true, enabled = !state.channelBusy,
                        label = { Text(stringResource(R.string.server_channel_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("server-channel-password"),
                    )
                    if (state.channelPasswordWrong) {
                        Text(stringResource(R.string.server_channel_password_wrong), color = MaterialTheme.colorScheme.error)
                    }
                    Button(enabled = !state.channelBusy && password.isNotEmpty(), onClick = { onPassword(password) }) {
                        Text(stringResource(R.string.server_channel_connect))
                    }
                    Text(stringResource(R.string.server_channel_forgotten), style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        enabled = !state.channelBusy && password.length >= BookOrbit.MIN_DEVICE_PASSWORD,
                        onClick = { onNewPassword(password) },
                    ) { Text(stringResource(R.string.server_channel_set_new)) }
                }
                DeviceChannel.NotAllowed -> Text(stringResource(R.string.server_channel_not_allowed))
                DeviceChannel.SwitchedOff -> Text(stringResource(R.string.server_channel_switched_off))
                DeviceChannel.Unsupported ->
                    Text(stringResource(R.string.server_unsupported, state.account?.serverVersion.orEmpty().ifEmpty { "?" }))
                DeviceChannel.Ready -> Unit
            }
        }
    }
}

/** A book on a line of a list: its cover, title, authors, series, the reader's status, and its downloads or Read. */
@Composable
internal fun BookRow(book: RemoteBook, state: ServerState, model: ServerViewModel, onOpen: (File) -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        BookCover(book, model, Modifier.padding(end = 12.dp).size(width = 52.dp, height = 76.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(book.title.ifEmpty { stringResource(R.string.server_untitled) }, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (book.authors.isNotEmpty()) {
                Text(book.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (book.series.isNotEmpty()) {
                val series = if (book.seriesNumber.isEmpty()) book.series else stringResource(R.string.series_line, book.series, book.seriesNumber)
                Text(
                    series, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (book.status.isNotEmpty() && book.status != ReadStatuses.UNREAD) {
                Text(
                    statusName(book.status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val files = readableFiles(book)
            // A book that is in the library in one format is not offered in another as if it were not there.
            val kept = files.firstNotNullOfOrNull { state.onDevice[it.id] }
            when {
                kept != null -> InLibrary(kept, model, onOpen)
                files.isEmpty() -> Text(
                    stringResource(R.string.catalog_no_readable_file), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> for (file in files) DownloadLine(book, file, state.downloads[file.id], model)
            }
        }
    }
}

/** The files of [book] the app reads: one per format, the server's first of each. */
internal fun readableFiles(book: RemoteBook): List<RemoteFile> = readableFiles(book.files)

internal fun readableFiles(files: List<RemoteFile>): List<RemoteFile> =
    files.filter { it.format in Library.FORMATS }.distinctBy { it.format }

@Composable
internal fun InLibrary(bookId: String, model: ServerViewModel, onOpen: (File) -> Unit) {
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.catalog_downloaded), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { scope.launch { model.fileOf(bookId)?.let(onOpen) } }) { Text(stringResource(R.string.catalog_read)) }
    }
}

@Composable
internal fun DownloadLine(book: RemoteBook, file: RemoteFile, fetched: Fetched?, model: ServerViewModel) {
    val format = file.format.uppercase()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (fetched) {
            null -> TextButton(onClick = { model.download(book, file) }) { Text(stringResource(R.string.catalog_download, format)) }
            Fetched.Running -> {
                CircularProgressIndicator(Modifier.padding(vertical = 12.dp).size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.catalog_downloading, format), style = MaterialTheme.typography.bodySmall)
            }
            is Fetched.Failed -> {
                Text(failureText(fetched.why), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { model.download(book, file) }) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
internal fun BookCover(book: RemoteBook, model: ServerViewModel, modifier: Modifier) {
    val picture by produceState<ImageBitmap?>(null, book.id) { value = model.cover(book) }
    val shape = RoundedCornerShape(4.dp)
    val shown = picture
    if (shown != null) {
        Image(shown, null, modifier.clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
private fun SearchBar(hint: String, onSearch: (String) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        placeholder = { Text(hint) },
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
        modifier = Modifier.fillMaxWidth().statusBarsPadding().focusRequester(focus).testTag("server-search"),
    )
}

/**
 * A book on a shelf: its cover and title, and whether it is in the library. A tap opens its page, the same
 * as from a line of a list, with the same downloads.
 */
@Composable
internal fun BookTile(book: RemoteBook, state: ServerState, model: ServerViewModel, onClick: () -> Unit) {
    Column(
        Modifier.width(TILE_WIDTH).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BookCover(book, model, Modifier.size(width = TILE_WIDTH - 8.dp, height = (TILE_WIDTH - 8.dp) * 1.5f))
        Text(
            book.title.ifEmpty { stringResource(R.string.server_untitled) }, style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        // A book suggested beside another comes without files: it is known to be in the library by the server's book.
        if (book.files.any { state.onDevice[it.id] != null } || book.id in state.onDeviceByBook) {
            Text(
                stringResource(R.string.catalog_downloaded), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val TILE_WIDTH = 112.dp
