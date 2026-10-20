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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.evsyukov.perigee.R
import org.evsyukov.perigee.ui.Centred

/**
 * What the app is and who made it: its name and version, the author and
 * where the source lies, each address opened in the browser. [onLicenses]
 * leaves for the licenses, [onClose] for the library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onLicenses: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    val browser = LocalUriHandler.current

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
                // No app may be able to open the address; the screen stays as it is then.
                Entry(stringResource(R.string.about_author), "$AUTHOR <$AUTHOR_URL>") { runCatching { browser.openUri(AUTHOR_URL) } }
                Entry(stringResource(R.string.about_source), SOURCE_URL) { runCatching { browser.openUri(SOURCE_URL) } }
                Entry(stringResource(R.string.action_licenses), null, onLicenses)
            }
        }
    }
}

/** A row that does [onClick]: what it is, and under it what it leads to, if that is worth saying. */
@Composable
private fun Entry(name: String, detail: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val AUTHOR = "Denis Evsyukov"
private const val AUTHOR_URL = "https://denis.evsyukov.org"
private const val SOURCE_URL = "https://github.com/juev/perigee"
