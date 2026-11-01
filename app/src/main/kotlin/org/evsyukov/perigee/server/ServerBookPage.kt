package org.evsyukov.perigee.server

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import java.io.File
import org.evsyukov.perigee.R
import org.evsyukov.perigee.library.plainText
import org.evsyukov.perigee.sync.RemoteBook
import org.evsyukov.perigee.ui.READABLE_WIDTH

/**
 * A book of the server on a page of its own: its cover, who wrote it, where it stands in a
 * series, what the server knows of its editions and what it says the book is about, the
 * same downloads as its line in the list, what the reader keeps of it on the server, and the
 * books the server lists beside it. It lies over the part it was opened from; a book opened
 * from it lies over it in turn, and the way back returns through them to where the first was.
 * What the server tells of the book is in [state], asked for when the page is opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerBookPage(book: RemoteBook, state: ServerState, model: ServerViewModel, onOpen: (File) -> Unit) {
    val onClose = model::closeBook
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The page is a window of its own, and the clock and icons over it are not dark by themselves on a light page.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
            if (window != null) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag("server-book-page"),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.action_about_book)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                    },
                )
            },
        ) { padding ->
            // Each book's page keeps its own place: a book opened from it starts at its top, and the way back
            // returns to the place the page before was left at. A page gone back from is forgotten.
            val pages = rememberSaveableStateHolder()
            val live = state.trail.map { it.id } + book.id
            val kept = remember { HashSet<Long>() }
            SideEffect {
                (kept - live.toSet()).forEach(pages::removeState)
                kept.clear()
                kept += live
            }
            pages.SaveableStateProvider(book.id) {
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).testTag("server-book-scroll")
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // On a tablet the page keeps to a width its lines can be read across, in the middle.
                    Column(Modifier.widthIn(max = READABLE_WIDTH).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val faint = MaterialTheme.colorScheme.onSurfaceVariant
                        val detail = state.detail?.takeIf { it.id == book.id }
                        // A book suggested beside another came without its files: they are the ones its page was told of.
                        val bookFiles = if (book.filesKnown) book.files else detail?.files.orEmpty()
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            BookCover(book, model, Modifier.size(width = 120.dp, height = 180.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(book.title.ifEmpty { stringResource(R.string.server_untitled) }, style = MaterialTheme.typography.titleLarge)
                                val subtitle = detail?.subtitle.orEmpty()
                                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.titleSmall, color = faint)
                                for (author in book.authors) Text(author, style = MaterialTheme.typography.bodyLarge)
                                if (book.series.isNotEmpty()) {
                                    val series = if (book.seriesNumber.isEmpty()) {
                                        book.series
                                    } else {
                                        stringResource(R.string.series_line, book.series, book.seriesNumber)
                                    }
                                    Text(series, style = MaterialTheme.typography.bodyMedium, color = faint)
                                }
                                val context = LocalContext.current
                                val formats = readableFiles(bookFiles).map { file ->
                                    val format = file.format.uppercase()
                                    if (file.size > 0) "$format ${Formatter.formatShortFileSize(context, file.size)}" else format
                                }
                                val pages = detail?.pageCount?.let { pluralStringResource(R.plurals.server_book_pages, it, it) }
                                val isbn = detail?.isbn?.ifEmpty { null }?.let { stringResource(R.string.server_book_isbn, it) }
                                val known = formats + listOfNotNull(
                                    detail?.publisher?.ifEmpty { null }, detail?.publishedYear?.toString(), pages,
                                    detail?.language?.ifEmpty { null }, isbn,
                                )
                                if (known.isNotEmpty()) {
                                    Text(known.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = faint)
                                }
                            }
                        }
                        val failure = state.detailFailure
                        when {
                            state.detailLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            failure != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(failureText(failure), color = MaterialTheme.colorScheme.error)
                                OutlinedButton(onClick = model::retryDetail) { Text(stringResource(R.string.action_retry)) }
                            }
                            else -> {
                                val about = plainText(detail?.description.orEmpty())
                                if (about.isEmpty()) {
                                    Text(stringResource(R.string.book_no_description), style = MaterialTheme.typography.bodyMedium, color = faint)
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for (paragraph in about.split('\n')) Text(paragraph, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                        // The same as the book's line in the list, from the same state: a download begun here shows there too.
                        val files = readableFiles(bookFiles)
                        // In the library by a file of its, or, for a book suggested beside another, by the server's book itself.
                        val inLibrary = files.firstNotNullOfOrNull { state.onDevice[it.id] } ?: state.onDeviceByBook[book.id]
                        when {
                            // Reading it leaves the pages, as the library's page is left: the reader goes to the book, not back to it.
                            inLibrary != null -> InLibrary(inLibrary, model) { file ->
                                model.leaveBooks()
                                onOpen(file)
                            }
                            // Not known yet for a book suggested beside another; its page says so by loading.
                            !book.filesKnown && detail == null -> Unit
                            files.isEmpty() -> Text(
                                stringResource(R.string.catalog_no_readable_file), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> Column { for (file in files) DownloadLine(book, file, state.downloads[file.id], model) }
                        }
                        if (detail != null) PersonalSection(book, detail, state, model)
                        RelatedSections(state, model)
                    }
                }
            }
        }
        state.membership?.let { MembershipDialog(it, model) }
    }
}
