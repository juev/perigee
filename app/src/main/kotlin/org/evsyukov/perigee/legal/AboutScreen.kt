package org.evsyukov.perigee.legal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.evsyukov.perigee.R
import org.evsyukov.perigee.ui.Centred

/**
 * What the app is and who made it: its name and version, the author and
 * where the source lies, each address drawn as a link and opened in the
 * browser, or a line saying no app can open it. [onLicenses] leaves for the
 * licenses, [onClose] for the library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onLicenses: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val browser = LocalUriHandler.current
    // No app may be able to open the address; the screen says so then, and the last try decides.
    var refused by remember { mutableStateOf(false) }
    val open = { url: String -> refused = runCatching { browser.openUri(url) }.isFailure }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_about)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Centred(padding) { inner ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(inner)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.about_what), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Entry(stringResource(R.string.about_author), { open(AUTHOR_URL) }) {
                    Text(AUTHOR, style = MaterialTheme.typography.bodyMedium)
                    Link(AUTHOR_URL)
                }
                Entry(stringResource(R.string.about_source), { open(SOURCE_URL) }) { Link(SOURCE_URL) }
                Entry(stringResource(R.string.action_licenses), onLicenses)
                // Below the rows, so that the licenses row does not move under the finger.
                if (refused) {
                    Text(
                        stringResource(R.string.about_open_failed),
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** A row that does [onClick]: what it is, and under it what it leads to, if that is worth saying. */
@Composable
private fun Entry(name: String, onClick: () -> Unit, detail: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        detail()
    }
}

/** [url] drawn as a link; the row around it is what is tapped, not the link itself. */
@Composable
private fun Link(url: String) {
    val link = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    Text(buildAnnotatedString { withStyle(link) { append(url) } }, style = MaterialTheme.typography.bodyMedium)
}

private const val AUTHOR = "Denis Evsyukov"
private const val AUTHOR_URL = "https://denis.evsyukov.org"
private const val SOURCE_URL = "https://github.com/juev/perigee"
