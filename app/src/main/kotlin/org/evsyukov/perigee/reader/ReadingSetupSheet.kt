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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.ColorScheme
import org.evsyukov.perigee.data.ReaderPrefs

internal enum class ReadingSetupStep { Size, Layout, Spacing, Theme, Review }
internal enum class ReadingSetupError { Conflict, NameOrLimit, Save }

/** A preview belongs to the open reader, not to saved preferences or navigation state. */
internal data class ReadingSetupDraft(
    val before: ReaderPrefs,
    val chosen: ReaderPrefs = before,
    val step: ReadingSetupStep = ReadingSetupStep.Size,
    val name: String = "",
    val saving: Boolean = false,
    val error: ReadingSetupError? = null,
) {
    fun preview(stored: ReaderPrefs): ReaderPrefs = project(stored, before, chosen)

    fun edit(stored: ReaderPrefs, change: (ReaderPrefs) -> ReaderPrefs): ReadingSetupDraft {
        val visible = preview(stored)
        // Carry only this action's edits into the draft, not external changes visible in the preview.
        return copy(chosen = project(chosen, visible, change(visible)))
    }

    private fun project(base: ReaderPrefs, old: ReaderPrefs, new: ReaderPrefs): ReaderPrefs {
        fun <T> pick(original: T, selected: T, current: T) = if (original == selected) current else selected
        return base.copy(
            fontSize = pick(old.fontSize, new.fontSize, base.fontSize),
            pageMargins = pick(old.pageMargins, new.pageMargins, base.pageMargins),
            twoPagesInLandscape = pick(old.twoPagesInLandscape, new.twoPagesInLandscape, base.twoPagesInLandscape),
            lineSpacingPercent = pick(old.lineSpacingPercent, new.lineSpacingPercent, base.lineSpacingPercent),
            colorScheme = pick(old.colorScheme, new.colorScheme, base.colorScheme),
            dayScheme = pick(old.dayScheme, new.dayScheme, base.dayScheme),
            nightScheme = pick(old.nightScheme, new.nightScheme, base.nightScheme),
        )
    }
}

private fun ColorScheme.setupLabel(): Int = when (this) {
    ColorScheme.Day -> R.string.scheme_day
    ColorScheme.Night -> R.string.scheme_night
    ColorScheme.Oled -> R.string.scheme_oled
    ColorScheme.Sepia -> R.string.scheme_sepia
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingSetupSheet(
    draft: ReadingSetupDraft,
    prefs: ReaderPrefs,
    onChange: ((ReaderPrefs) -> ReaderPrefs) -> Unit,
    onMove: (Int) -> Unit,
    onName: (String) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    val saving = rememberUpdatedState(draft.saving)
    val keyboard = LocalSoftwareKeyboardController.current
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = {
            !saving.value || it != SheetValue.Hidden
        }),
        scrimColor = Color.Transparent,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        val scroll = key(draft.step) { rememberScrollState() }
        Column(
            Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.55f).dp)
                .verticalScroll(scroll).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("reading-setup-sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.reading_setup_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            val title = when (draft.step) {
                ReadingSetupStep.Size -> R.string.setting_font_size
                ReadingSetupStep.Layout -> R.string.reading_setup_layout
                ReadingSetupStep.Spacing -> R.string.setting_line_spacing
                ReadingSetupStep.Theme -> R.string.setting_color_scheme
                ReadingSetupStep.Review -> R.string.reading_setup_review
            }
            Text(stringResource(R.string.reading_setup_step, draft.step.ordinal + 1, ReadingSetupStep.entries.size, stringResource(title)),
                Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium)
            when (draft.step) {
                ReadingSetupStep.Size -> {
                    Text(stringResource(R.string.reading_setup_size_hint))
                    Stepper(R.string.setting_font_size, prefs.fontSize.toString(), "reading-setup-font-size",
                        { onChange { it.copy(fontSize = (it.fontSize - 1).coerceIn(ReaderPrefs.FONT_SIZES)) } },
                        { onChange { it.copy(fontSize = (it.fontSize + 1).coerceIn(ReaderPrefs.FONT_SIZES)) } },
                    )
                }
                ReadingSetupStep.Layout -> {
                    Text(stringResource(R.string.reading_setup_layout_hint))
                    Toggle(R.string.setting_page_margins, prefs.pageMargins, "reading-setup-margins", nameSwitch = true) { enabled ->
                        onChange { it.copy(pageMargins = enabled) }
                    }
                    Toggle(R.string.setting_two_pages, prefs.twoPagesInLandscape, "reading-setup-landscape", nameSwitch = true) { enabled ->
                        onChange { it.copy(twoPagesInLandscape = enabled) }
                    }
                }
                ReadingSetupStep.Spacing -> {
                    Text(stringResource(R.string.reading_setup_spacing_hint))
                    Stepper(R.string.setting_line_spacing, stringResource(R.string.percent, prefs.lineSpacingPercent), "reading-setup-line-spacing",
                        { onChange { it.copy(lineSpacingPercent = stepped(it.lineSpacingPercent, -1, ReaderPrefs.LINE_SPACINGS)) } },
                        { onChange { it.copy(lineSpacingPercent = stepped(it.lineSpacingPercent, 1, ReaderPrefs.LINE_SPACINGS)) } },
                    )
                }
                ReadingSetupStep.Theme -> {
                    Text(stringResource(R.string.reading_setup_theme_hint))
                    for (scheme in ColorScheme.entries) {
                        FilterChip(selected = prefs.colorScheme == scheme, onClick = { onChange { it.withScheme(scheme) } },
                            label = { Text(stringResource(scheme.setupLabel())) },
                            modifier = Modifier.testTag("reading-setup-theme-${scheme.name.lowercase()}"))
                    }
                }
                ReadingSetupStep.Review -> {
                    Text(stringResource(R.string.reading_setup_review_hint))
                    Text(stringResource(R.string.reading_setup_value, stringResource(R.string.setting_font_size), prefs.fontSize.toString()))
                    Text(stringResource(R.string.reading_setup_value, stringResource(R.string.setting_line_spacing), stringResource(R.string.percent, prefs.lineSpacingPercent)))
                    Text(stringResource(if (prefs.pageMargins) R.string.reading_setup_wide_margins else R.string.reading_setup_narrow_margins))
                    Text(stringResource(if (prefs.twoPagesInLandscape) R.string.setting_two_pages else R.string.reading_setup_one_page))
                    Text(stringResource(R.string.reading_setup_value, stringResource(R.string.setting_color_scheme), stringResource(prefs.colorScheme.setupLabel())))
                    OutlinedTextField(draft.name, onName, modifier = Modifier.fillMaxWidth().testTag("reading-setup-name"),
                        enabled = !draft.saving, singleLine = true, label = { Text(stringResource(R.string.appearance_profile_name)) })
                }
            }
            draft.error?.let {
                Text(stringResource(when (it) {
                    ReadingSetupError.Conflict -> R.string.reading_setup_conflict
                    ReadingSetupError.NameOrLimit -> R.string.reading_setup_name_or_limit
                    ReadingSetupError.Save -> R.string.reading_setup_save_error
                }), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
            }
            if (draft.step == ReadingSetupStep.Review) {
                TextButton(onClick = { keyboard?.hide(); onFinish() }, enabled = !draft.saving && draft.name.isNotBlank(),
                    modifier = Modifier.testTag("reading-setup-finish")) { Text(stringResource(R.string.reading_setup_finish)) }
            } else {
                TextButton(onClick = { onMove(1) }, modifier = Modifier.testTag("reading-setup-next")) {
                    Text(stringResource(R.string.reading_setup_next))
                }
                TextButton(onClick = { onMove(1) }, modifier = Modifier.testTag("reading-setup-skip")) {
                    Text(stringResource(R.string.reading_setup_skip))
                }
            }
            if (draft.step != ReadingSetupStep.Size) {
                TextButton(onClick = { onMove(-1) }, enabled = !draft.saving, modifier = Modifier.testTag("reading-setup-back")) {
                    Text(stringResource(R.string.action_back))
                }
            }
            TextButton(onClick = onCancel, enabled = !draft.saving, modifier = Modifier.testTag("reading-setup-cancel")) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}
