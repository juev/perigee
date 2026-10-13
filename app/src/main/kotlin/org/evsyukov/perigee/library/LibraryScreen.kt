package org.evsyukov.perigee.library

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.BookCollection
import org.evsyukov.perigee.data.BookSort
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.data.Folder
import org.evsyukov.perigee.data.ReadingStatus
import org.evsyukov.perigee.data.Shelf

/**
 * The reader's books by shelf. [onOpen] is given the file of the book that
 * was picked; [onOpenFile] asks for a single file from outside the library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (File) -> Unit, onOpenFile: () -> Unit, model: LibraryViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showFolders by rememberSaveable { mutableStateOf(false) }
    var collectionsOf by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf<Naming?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Without this the folder is ours only until the app is put away.
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            model.addFolder(uri.toString(), folderName(context, uri))
        }
    }
    val unavailable = stringResource(R.string.book_unavailable)
    val open: (Book) -> Unit = { book ->
        scope.launch {
            val file = model.fileOf(book)
            if (file != null) onOpen(file) else snackbar.showSnackbar(unavailable)
        }
    }
    val stopSearch = {
        searching = false
        model.setQuery("")
    }
    BackHandler(enabled = searching || state.group != null) {
        if (searching) stopSearch() else model.openGroup(null)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                if (searching) {
                    SearchBar(state.query, model::setQuery, stopSearch)
                } else {
                    TopAppBar(
                        title = { Text(state.group?.let { groupName(it) } ?: stringResource(R.string.library_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            if (state.group != null) {
                                IconButton(onClick = { model.openGroup(null) }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Filled.Search, stringResource(R.string.action_search))
                            }
                            SortButton(state.sort, model::setSort)
                            LibraryMenu(onFolders = { showFolders = true }, onRescan = model::rescan, onOpenFile = onOpenFile)
                        },
                    )
                }
                if (state.scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("scanning"))
                }
                if (state.group == null) Shelves(state.shelf, model::setShelf)
            }
        },
    ) { padding ->
        val listing = state.listing
        when {
            !state.loaded -> Unit
            state.empty && !state.shelf.grouped -> EmptyLibrary(
                padding, state.scanning, onAddFolder = { folderPicker.launch(null) }, onOpenFile = onOpenFile,
            )
            listing is Listing.Groups -> Groups(
                padding, state.shelf, listing.groups,
                onOpen = model::openGroup,
                onNew = { naming = Naming.New },
                onRename = { naming = Naming.Rename(it.key.toLong(), it.name) },
                onRemove = { model.removeCollection(it.key.toLong()) },
            )
            listing is Listing.Books -> Books(
                padding, listing.books, model.covers,
                onOpen = open,
                onFavorite = { model.setFavorite(it, !it.favorite) },
                onStatus = model::setStatus,
                onCollections = { collectionsOf = it.id },
            )
        }
    }

    if (showFolders) {
        FoldersDialog(
            state.folders,
            onAdd = { folderPicker.launch(null) },
            onRemove = { folder ->
                model.removeFolder(folder)
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(folder.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            },
            onClose = { showFolders = false },
        )
    }
    val shelved = (state.listing as? Listing.Books)?.books?.firstOrNull { it.id == collectionsOf }
    if (shelved != null) {
        CollectionsDialog(
            state.collections,
            memberOf = state.members.filter { it.bookId == shelved.id }.mapTo(HashSet()) { it.collectionId },
            onSet = { collection, member -> model.setInCollection(collection, shelved, member) },
            onCreate = { name -> model.createCollection(name, shelved) },
            onClose = { collectionsOf = null },
        )
    }
    naming?.let { asked ->
        NameDialog(
            title = stringResource(if (asked is Naming.Rename) R.string.action_rename else R.string.collection_new),
            initial = (asked as? Naming.Rename)?.name.orEmpty(),
            onDone = { name ->
                when (asked) {
                    Naming.New -> model.createCollection(name)
                    is Naming.Rename -> model.renameCollection(asked.id, name)
                }
                naming = null
            },
            onClose = { naming = null },
        )
    }
}

/** A collection being named. */
private sealed interface Naming {
    data object New : Naming
    data class Rename(val id: Long, val name: String) : Naming
}

@Composable
private fun groupName(group: Group): String = group.name.ifEmpty { stringResource(R.string.unknown_author) }

@Composable
private fun shelfName(shelf: Shelf): String = stringResource(
    when (shelf) {
        Shelf.Reading -> R.string.shelf_reading
        Shelf.All -> R.string.shelf_all
        Shelf.Favorites -> R.string.shelf_favorites
        Shelf.ToRead -> R.string.shelf_to_read
        Shelf.Read -> R.string.shelf_read
        Shelf.Authors -> R.string.shelf_authors
        Shelf.Series -> R.string.shelf_series
        Shelf.Collections -> R.string.shelf_collections
    },
)

@Composable
private fun Shelves(current: Shelf, onPick: (Shelf) -> Unit) {
    LazyRow(
        Modifier.testTag("shelves"),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(Shelf.entries) { shelf ->
            FilterChip(selected = shelf == current, onClick = { onPick(shelf) }, label = { Text(shelfName(shelf)) })
        }
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search_library_hint)) },
        leadingIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, stringResource(R.string.action_clear)) }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth().statusBarsPadding().focusRequester(focus).testTag("library-search"),
    )
}

@Composable
private fun SortButton(current: BookSort, onPick: (BookSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.action_sort)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (sort in BookSort.entries) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                when (sort) {
                                    BookSort.Recent -> R.string.sort_recent
                                    BookSort.Title -> R.string.sort_title
                                    BookSort.Author -> R.string.sort_author
                                    BookSort.Added -> R.string.sort_added
                                },
                            ),
                        )
                    },
                    leadingIcon = { Tick(sort == current) },
                    onClick = {
                        open = false
                        onPick(sort)
                    },
                )
            }
        }
    }
}

/** A check mark, or the room one would take, so that the items of a menu line up. */
@Composable
private fun Tick(shown: Boolean) {
    if (shown) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp))
}

@Composable
private fun LibraryMenu(onFolders: () -> Unit, onRescan: () -> Unit, onOpenFile: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val items = listOf(
                R.string.action_folders to onFolders, R.string.action_rescan to onRescan, R.string.open_book to onOpenFile,
            )
            for ((label, action) in items) {
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(padding: PaddingValues, scanning: Boolean, onAddFolder: () -> Unit, onOpenFile: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(if (scanning) R.string.scanning else R.string.library_empty),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onAddFolder) { Text(stringResource(R.string.action_add_folder)) }
        OutlinedButton(onClick = onOpenFile) { Text(stringResource(R.string.open_book)) }
    }
}

@Composable
private fun Groups(
    padding: PaddingValues,
    shelf: Shelf,
    groups: List<Group>,
    onOpen: (Group) -> Unit,
    onNew: () -> Unit,
    onRename: (Group) -> Unit,
    onRemove: (Group) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        if (shelf == Shelf.Collections) {
            item("new") {
                TextButton(onClick = onNew, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(Icons.Filled.Add, null)
                    Text(stringResource(R.string.collection_new), Modifier.padding(start = 8.dp))
                }
            }
        }
        if (groups.isEmpty()) {
            item("empty") { Nothing(R.string.shelf_empty) }
        }
        items(groups, key = { it.key }) { group ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(group) }.padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text(groupName(group), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        pluralStringResource(R.plurals.books_count, group.count, group.count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (shelf == Shelf.Collections) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { open = true }) {
                            Icon(Icons.Filled.MoreVert, stringResource(R.string.collection_actions, group.name))
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_rename)) },
                                onClick = {
                                    open = false
                                    onRename(group)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete)) },
                                onClick = {
                                    open = false
                                    onRemove(group)
                                },
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Nothing(text: Int) {
    Text(
        stringResource(text),
        Modifier.fillMaxWidth().padding(32.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Books(
    padding: PaddingValues,
    books: List<Book>,
    covers: Covers,
    onOpen: (Book) -> Unit,
    onFavorite: (Book) -> Unit,
    onStatus: (Book, ReadingStatus) -> Unit,
    onCollections: (Book) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().testTag("books"), contentPadding = padding) {
        if (books.isEmpty()) {
            item("empty") { Nothing(R.string.shelf_empty) }
        }
        items(books, key = { it.id }) { book ->
            BookRow(book, covers, onOpen, onFavorite, onStatus, onCollections)
        }
    }
}

@Composable
private fun BookRow(
    book: Book,
    covers: Covers,
    onOpen: (Book) -> Unit,
    onFavorite: (Book) -> Unit,
    onStatus: (Book, ReadingStatus) -> Unit,
    onCollections: (Book) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(book) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(book, covers, Modifier.size(width = 52.dp, height = 76.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.author.isNotEmpty()) {
                Text(book.authorLine, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val faint = MaterialTheme.colorScheme.onSurfaceVariant
            if (book.series.isNotEmpty()) {
                val series = if (book.seriesNumber.isEmpty()) {
                    book.series
                } else {
                    stringResource(R.string.series_line, book.series, book.seriesNumber)
                }
                Text(series, style = MaterialTheme.typography.bodySmall, color = faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val read = if (book.openedAt > 0) stringResource(R.string.percent, (book.progress * 100).toInt()) else null
            Text(
                listOfNotNull(read, book.format.uppercase().ifEmpty { null }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = faint,
            )
        }
        IconButton(onClick = { onFavorite(book) }) {
            if (book.favorite) {
                Icon(Icons.Filled.Favorite, stringResource(R.string.favorite_remove, book.title), tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Filled.FavoriteBorder, stringResource(R.string.favorite_add, book.title))
            }
        }
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Filled.MoreVert, stringResource(R.string.book_actions, book.title))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                val statuses = listOf(
                    ReadingStatus.ToRead to R.string.shelf_to_read,
                    ReadingStatus.Reading to R.string.shelf_reading,
                    ReadingStatus.Read to R.string.shelf_read,
                )
                for ((status, label) in statuses) {
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { Tick(book.status == status) },
                        onClick = {
                            open = false
                            onStatus(book, status)
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_collections)) },
                    leadingIcon = { Tick(false) },
                    onClick = {
                        open = false
                        onCollections(book)
                    },
                )
            }
        }
    }
}

// Covers scrolled past stay decoded for the way back.
private val coverCache = LruCache<String, ImageBitmap>(96)

@Composable
private fun Cover(book: Book, covers: Covers, modifier: Modifier) {
    val picture by produceState(coverCache.get(book.id), book.id, book.hasCover) {
        if (value == null && book.hasCover) {
            value = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(covers.file(book.id).path)?.asImageBitmap()
            }?.also { coverCache.put(book.id, it) }
        }
    }
    val shape = RoundedCornerShape(4.dp)
    val shown = picture
    if (shown != null) {
        Image(shown, null, modifier.clip(shape), contentScale = ContentScale.Crop)
    } else {
        // A book without a cover is told by its first letter, as on a plain binding.
        Box(modifier.clip(shape).background(PLAIN_COVERS[(book.id.hashCode() and 0x7FFFFFFF) % PLAIN_COVERS.size]), Alignment.Center) {
            Text(
                book.title.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
        }
    }
}

private val PLAIN_COVERS = listOf(
    Color(0xFF5D6D7E), Color(0xFF7D6608), Color(0xFF6C3483), Color(0xFF1E8449), Color(0xFF935116), Color(0xFF1F618D),
)

@Composable
private fun FoldersDialog(folders: List<Folder>, onAdd: () -> Unit, onRemove: (Folder) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.action_folders)) },
        text = {
            Column {
                if (folders.isEmpty()) Text(stringResource(R.string.folders_empty))
                for (folder in folders) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(folder.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { onRemove(folder) }) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.action_remove_folder, folder.name))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text(stringResource(R.string.action_add_folder)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_done)) } },
    )
}

@Composable
private fun CollectionsDialog(
    collections: List<BookCollection>,
    memberOf: Set<Long>,
    onSet: (BookCollection, Boolean) -> Unit,
    onCreate: (String) -> Unit,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.shelf_collections)) },
        text = {
            LazyColumn {
                items(collections, key = { it.id }) { collection ->
                    val member = collection.id in memberOf
                    Row(
                        Modifier.fillMaxWidth().clickable { onSet(collection, !member) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = member, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                        Text(collection.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item("new") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.collection_new)) },
                            modifier = Modifier.weight(1f).testTag("collection-name"),
                        )
                        IconButton(
                            enabled = name.isNotBlank(),
                            onClick = {
                                onCreate(name)
                                name = ""
                            },
                        ) { Icon(Icons.Filled.Add, stringResource(R.string.action_create)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_done)) } },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onClose: () -> Unit) {
    // The cursor starts after the name, where a correction is usually made.
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val name = field.text
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                label = { Text(stringResource(R.string.collection_name)) },
                modifier = Modifier.testTag("collection-name"),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onDone(name) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** What the system calls a picked folder; the last part of its address when it will not say. */
private fun folderName(context: Context, tree: Uri): String {
    val id = DocumentsContract.getTreeDocumentId(tree)
    val named = runCatching {
        context.contentResolver.query(
            DocumentsContract.buildDocumentUriUsingTree(tree, id),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
    return named?.takeIf { it.isNotBlank() } ?: id.substringAfterLast(':').substringAfterLast('/').ifEmpty { id }
}
