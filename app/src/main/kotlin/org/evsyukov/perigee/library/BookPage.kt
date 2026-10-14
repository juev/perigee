package org.evsyukov.perigee.library

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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.Book
import org.evsyukov.perigee.data.Covers
import org.evsyukov.perigee.ui.READABLE_WIDTH

/**
 * A book's own page: its cover, who wrote it, where it stands in a series,
 * how far it is read and what it says it is about. It lies over the library,
 * and the way back returns there. [server] is where the book stands with the
 * server, when there is something to say.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookPage(
    book: Book, covers: Covers, server: String?, onRead: () -> Unit, onFavorite: () -> Unit, onClose: () -> Unit,
) {
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
            modifier = Modifier.fillMaxSize().testTag("book-page"),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.action_about_book)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                    },
                    actions = {
                        IconButton(onClick = onFavorite) {
                            if (book.favorite) {
                                Icon(Icons.Filled.Favorite, stringResource(R.string.favorite_remove, book.title), tint = MaterialTheme.colorScheme.primary)
                            } else {
                                Icon(Icons.Filled.FavoriteBorder, stringResource(R.string.favorite_add, book.title))
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // On a tablet the page keeps to a width its lines can be read across, in the middle.
                Column(Modifier.widthIn(max = READABLE_WIDTH).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val faint = MaterialTheme.colorScheme.onSurfaceVariant
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Cover(book, covers, Modifier.size(width = 120.dp, height = 180.dp), titled = true)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(book.title, style = MaterialTheme.typography.titleLarge)
                            for (author in book.author.lines().filter { it.isNotBlank() }) {
                                Text(author, style = MaterialTheme.typography.bodyLarge)
                            }
                            if (book.series.isNotEmpty()) {
                                val series = if (book.seriesNumber.isEmpty()) {
                                    book.series
                                } else {
                                    stringResource(R.string.series_line, book.series, book.seriesNumber)
                                }
                                Text(series, style = MaterialTheme.typography.bodyMedium, color = faint)
                            }
                            val read = if (book.openedAt > 0) stringResource(R.string.percent, (book.progress * 100).toInt()) else null
                            Text(
                                listOfNotNull(
                                    book.format.uppercase().ifEmpty { null }, book.language.ifEmpty { null }, read,
                                    book.rating?.let { "★".repeat(it) }, server,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium, color = faint,
                            )
                        }
                    }
                    Button(onClick = onRead, modifier = Modifier.fillMaxWidth().testTag("book-read")) {
                        Text(stringResource(if (book.openedAt > 0) R.string.book_read_on else R.string.book_read))
                    }
                    val about = book.description.orEmpty()
                    if (about.isEmpty()) {
                        Text(stringResource(R.string.book_no_description), style = MaterialTheme.typography.bodyMedium, color = faint)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (paragraph in about.lines()) Text(paragraph, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
