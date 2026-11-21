package org.evsyukov.perigee.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.evsyukov.perigee.R

/** A preview belongs to the open reader, not to saved preferences or navigation state. */
internal data class ReadingSetupDraft(val fontSize: Int, val changedSize: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingSetupSheet(fontSize: Int, onSize: (Int) -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        scrimColor = Color.Transparent,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.55f).dp)
                .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("reading-setup-sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.reading_setup_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.reading_setup_size_hint))
            Stepper(R.string.setting_font_size, fontSize.toString(), "reading-setup-font-size", { onSize(-1) }, { onSize(1) })
            TextButton(onClick = onCancel, modifier = Modifier.testTag("reading-setup-cancel")) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}
