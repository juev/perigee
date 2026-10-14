package org.evsyukov.perigee.legal

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.evsyukov.perigee.R
import org.evsyukov.perigee.ui.Centred

/** A license text that comes with the app: [name] as the reader sees it, [asset] where it lies. */
data class LicenseText(val name: String, val asset: String)

/**
 * The texts under `legal/licenses`, named by their files: the build puts
 * there one file for each component the app is built from.
 */
fun componentLicenses(context: Context): List<LicenseText> =
    context.assets.list(LICENSES).orEmpty().sortedBy { it.lowercase() }
        .map { LicenseText(it.substringBeforeLast('.'), "$LICENSES/$it") }

/** A text cut where it has an empty line, so that a long license is laid out as it is scrolled to. */
fun paragraphs(text: String): List<String> =
    text.replace("\r\n", "\n").replace("\u000c", "").split(Regex("\n[ \t]*\n")).map { it.trim('\n') }.filter { it.isNotBlank() }

/**
 * What the app is and what it is built from: the notice with the terms of the
 * app itself, then the license of every component. [onClose] leaves for the library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    // The license being read: its name and where its text lies.
    var name by rememberSaveable { mutableStateOf<String?>(null) }
    var asset by rememberSaveable { mutableStateOf<String?>(null) }
    val back = { if (asset != null) asset = null else onClose() }
    BackHandler(onBack = back)

    val shown = asset ?: NOTICE
    val text by produceState(emptyList<String>(), shown) {
        value = withContext(Dispatchers.IO) { paragraphs(context.assets.open(shown).bufferedReader().use { it.readText() }) }
    }
    val components by produceState(emptyList<LicenseText>()) {
        value = withContext(Dispatchers.IO) { componentLicenses(context) }
    }
    val gpl = LicenseText(stringResource(R.string.licenses_gpl), GPL)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name ?: stringResource(R.string.licenses_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        // Keyed by the text, so that a license opens at its top, not where the list before it was left.
        androidx.compose.runtime.key(shown) {
            Centred(padding) { inner ->
                LazyColumn(Modifier.fillMaxSize().testTag("legal-text"), contentPadding = inner) {
                    items(text) { paragraph ->
                        Text(
                            paragraph, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    if (asset == null) {
                        item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
                        items(listOf(gpl) + components) { license ->
                            Text(
                                license.name, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    name = license.name
                                    asset = license.asset
                                }.padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val NOTICE = "legal/NOTICE"
private const val GPL = "legal/LICENSE"
private const val LICENSES = "legal/licenses"
