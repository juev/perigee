package org.evsyukov.perigee.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.Alignment as TextAlignment
import org.evsyukov.perigee.data.Bookmark
import org.evsyukov.perigee.data.ColorScheme
import org.evsyukov.perigee.data.PageTurn
import org.evsyukov.perigee.data.Quote
import org.evsyukov.perigee.data.ReaderPrefs
import org.evsyukov.perigee.data.TapScheme
import org.evsyukov.perigee.engine.EngineSettings

// ---------------------------------------------------------------- settings

/**
 * Reading settings, in the order and with the controls ReadEra has them. The sheet
 * keeps to the lower part of the screen and leaves the page undimmed above it, so
 * each change shows on the page while the settings are still open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(
    prefs: ReaderPrefs,
    fontFaces: List<String>,
    charsPerLine: Int?,
    lightSensor: Boolean,
    onFontTrial: () -> Unit,
    onStyles: () -> Unit,
    onChange: ((ReaderPrefs) -> ReaderPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        scrimColor = Color.Transparent,
        // The handle and the navigation bar's room are inside the height the sheet keeps to, below.
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        // The settings scroll inside the sheet rather than grow it over the page.
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * SETTINGS_SHARE)
                .testTag("settings-sheet"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BottomSheetDefaults.DragHandle()
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp)
                    .testTag("settings"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)
                Choice(
                    R.string.setting_page_turn, prefs.pageTurn, PageTurn.entries,
                    label = { stringResource(if (it == PageTurn.Horizontal) R.string.page_turn_horizontal else R.string.page_turn_vertical) },
                ) { chosen -> onChange { it.copy(pageTurn = chosen) } }
                Choice(
                    R.string.setting_color_scheme, prefs.colorScheme, ColorScheme.entries,
                    label = {
                        stringResource(
                            when (it) {
                                ColorScheme.Day -> R.string.scheme_day
                                ColorScheme.Night -> R.string.scheme_night
                                ColorScheme.Oled -> R.string.scheme_oled
                                ColorScheme.Sepia -> R.string.scheme_sepia
                            },
                        )
                    },
                ) { chosen -> onChange { it.withScheme(chosen) } }
                if (lightSensor) {
                    Toggle(R.string.setting_scheme_by_light, prefs.schemeByLight) { on -> onChange { it.copy(schemeByLight = on) } }
                }
                Choice(R.string.setting_font, prefs.fontFace, fontFaces, label = { it }) { chosen ->
                    onChange { it.copy(fontFace = chosen) }
                }
                // A book that names a font of its own: the reader decides which one is read.
                Toggle(R.string.setting_override_book_font, prefs.overrideBookFont) { on ->
                    onChange { it.copy(overrideBookFont = on) }
                }
                TextButton(onClick = onFontTrial, modifier = Modifier.testTag("font-trial-open")) {
                    Text(stringResource(R.string.font_trial_open))
                }
                Stepper(
                    R.string.setting_font_size, prefs.fontSize.toString(), "font-size",
                    onLess = { onChange { it.copy(fontSize = (it.fontSize - 1).coerceIn(ReaderPrefs.FONT_SIZES)) } },
                    onMore = { onChange { it.copy(fontSize = (it.fontSize + 1).coerceIn(ReaderPrefs.FONT_SIZES)) } },
                )
                WeightSlider(prefs.fontWeight) { chosen -> onChange { it.copy(fontWeight = chosen) } }
                // Darker strokes step by step, with no line moved; the page is painted again as the thumb is let go.
                StepSlider(R.string.setting_contrast, prefs.contrast, EngineSettings.CONTRASTS, "contrast") { chosen ->
                    onChange { it.copy(contrast = chosen) }
                }
                Toggle(R.string.setting_bold_word_starts, prefs.boldWordStarts) { on -> onChange { it.copy(boldWordStarts = on) } }
                Stepper(
                    R.string.setting_line_spacing, stringResource(R.string.percent, prefs.lineSpacingPercent), "line-spacing",
                    onLess = { onChange { it.copy(lineSpacingPercent = stepped(it.lineSpacingPercent, -1, ReaderPrefs.LINE_SPACINGS)) } },
                    onMore = { onChange { it.copy(lineSpacingPercent = stepped(it.lineSpacingPercent, 1, ReaderPrefs.LINE_SPACINGS)) } },
                )
                Choice(
                    R.string.setting_alignment, prefs.alignment, TextAlignment.entries,
                    label = {
                        stringResource(
                            when (it) {
                                TextAlignment.Justify -> R.string.align_justify
                                TextAlignment.Left -> R.string.align_left
                                TextAlignment.Center -> R.string.align_center
                                TextAlignment.Right -> R.string.align_right
                            },
                        )
                    },
                ) { chosen -> onChange { it.copy(alignment = chosen) } }
                Toggle(R.string.setting_hyphenation, prefs.hyphenation) { on -> onChange { it.copy(hyphenation = on) } }
                if (widensWordGaps(prefs)) {
                    Text(
                        stringResource(R.string.hint_justify_without_hyphenation),
                        Modifier.testTag("justify-hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Stepper(
                    R.string.setting_paragraph_spacing, stringResource(R.string.percent, prefs.paragraphSpacingPercent), "paragraph-spacing",
                    onLess = { onChange { it.copy(paragraphSpacingPercent = stepped(it.paragraphSpacingPercent, -1, ReaderPrefs.PARAGRAPH_SPACINGS)) } },
                    onMore = { onChange { it.copy(paragraphSpacingPercent = stepped(it.paragraphSpacingPercent, 1, ReaderPrefs.PARAGRAPH_SPACINGS)) } },
                )
                Toggle(R.string.setting_paragraph_indent, prefs.paragraphIndent) { on -> onChange { it.copy(paragraphIndent = on) } }
                Toggle(R.string.setting_page_margins, prefs.pageMargins) { on -> onChange { it.copy(pageMargins = on) } }
                if (charsPerLine != null) {
                    Text(
                        stringResource(R.string.chars_per_line, charsPerLine),
                        Modifier.testTag("chars-per-line"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Toggle(R.string.setting_two_pages, prefs.twoPagesInLandscape) { on -> onChange { it.copy(twoPagesInLandscape = on) } }

                Choice(
                    R.string.setting_tap_scheme, prefs.tapScheme, TapScheme.entries,
                    label = {
                        stringResource(
                            when (it) {
                                TapScheme.LeftRight -> R.string.tap_scheme_left_right
                                TapScheme.TopBottom -> R.string.tap_scheme_top_bottom
                                TapScheme.AnyForward -> R.string.tap_scheme_any_forward
                                TapScheme.Off -> R.string.tap_scheme_off
                            },
                        )
                    },
                ) { chosen -> onChange { it.copy(tapScheme = chosen) } }
                Toggle(R.string.setting_volume_keys, prefs.volumeKeysTurnPages) { on -> onChange { it.copy(volumeKeysTurnPages = on) } }
                Toggle(R.string.setting_brightness_gesture, prefs.brightnessGesture) { on ->
                    // Switching the gesture off hands brightness back to the system.
                    onChange { it.copy(brightnessGesture = on, brightness = if (on) it.brightness else null) }
                }
                if (prefs.brightnessGesture) {
                    Toggle(R.string.setting_dim_below_flicker, prefs.dimBelowFlicker) { on -> onChange { it.copy(dimBelowFlicker = on) } }
                }
                Toggle(R.string.setting_day_night_corner, prefs.dayNightByCorner) { on -> onChange { it.copy(dayNightByCorner = on) } }
                Toggle(R.string.setting_bookmark_corner, prefs.bookmarkByCorner) { on -> onChange { it.copy(bookmarkByCorner = on) } }
                Toggle(R.string.setting_warm_filter, prefs.warmFilter) { on -> onChange { it.copy(warmFilter = on) } }
                if (prefs.warmFilter) {
                    Choice(R.string.setting_warm_from, prefs.warmFromMinute, ReaderPrefs.WARM_TIMES, label = { clockTime(it) }) { chosen ->
                        onChange { it.copy(warmFromMinute = chosen) }
                    }
                    Choice(R.string.setting_warm_to, prefs.warmToMinute, ReaderPrefs.WARM_TIMES, label = { clockTime(it) }) { chosen ->
                        onChange { it.copy(warmToMinute = chosen) }
                    }
                    StepSlider(R.string.setting_warm_strength, prefs.warmStrength, ReaderPrefs.WARM_STRENGTHS, "warm-strength") { chosen ->
                        onChange { it.copy(warmStrength = chosen) }
                    }
                }
                Choice(
                    R.string.setting_screen_on, prefs.screenOnMinutes, ReaderPrefs.SCREEN_ON_CHOICES,
                    label = {
                        when (it) {
                            ReaderPrefs.SCREEN_ON_SYSTEM -> stringResource(R.string.screen_on_system)
                            ReaderPrefs.SCREEN_ON_ALWAYS -> stringResource(R.string.screen_on_always)
                            else -> pluralStringResource(R.plurals.screen_on_minutes, it, it)
                        }
                    },
                ) { chosen -> onChange { it.copy(screenOnMinutes = chosen) } }
                // The owner's CSS opens on a screen of its own: a sheet is no place to write in.
                Column(Modifier.fillMaxWidth().clickable(onClick = onStyles).padding(vertical = 6.dp).testTag("styles-open")) {
                    Text(stringResource(R.string.setting_styles), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(if (prefs.userCss.isBlank()) R.string.setting_styles_none else R.string.setting_styles_own),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The share of the screen height the settings take; the page shows above them. */
internal const val SETTINGS_SHARE = 0.5f

/** The next or previous value of a stepped range, staying inside it. */
private fun stepped(value: Int, direction: Int, range: IntProgression): Int =
    (value + direction * range.step).coerceIn(range.first, range.last)

/** Justified lines with no word broken at their end stretch the gaps between words. */
internal fun widensWordGaps(prefs: ReaderPrefs): Boolean =
    prefs.alignment == TextAlignment.Justify && !prefs.hyphenation

@Composable
private fun SettingLabel(label: Int) {
    Text(
        stringResource(label).uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A labelled value that opens a list of the other values. */
@Composable
private fun <T> Choice(
    title: Int,
    value: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onChoose: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 6.dp)) {
        SettingLabel(title)
        Box {
            Text(label(value), style = MaterialTheme.typography.bodyLarge)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (option in options) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                label(option),
                                fontWeight = if (option == value) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            open = false
                            onChoose(option)
                        },
                    )
                }
            }
        }
    }
}

/** A value with a button on each side to make it smaller or larger. */
@Composable
private fun Stepper(label: Int, value: String, tag: String, onLess: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        SettingLabel(label)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val less = stringResource(R.string.action_decrease)
            val more = stringResource(R.string.action_increase)
            OutlinedButton(onClick = onLess, Modifier.testTag("$tag-less").semantics { contentDescription = less }) { Text("−") }
            Text(
                value,
                Modifier.weight(1f).testTag(tag),
                style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            OutlinedButton(onClick = onMore, Modifier.testTag("$tag-more").semantics { contentDescription = more }) { Text("+") }
        }
    }
}

/** A minute of the day as the clock shows it. */
private fun clockTime(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

/** A slider over a few whole steps; the choice is made when the thumb is let go. */
@Composable
private fun StepSlider(label: Int, value: Int, steps: IntRange, tag: String, onChoose: (Int) -> Unit) {
    var position by remember(value) { mutableFloatStateOf(value.coerceIn(steps).toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        SettingLabel(label)
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onChoose(position.roundToInt()) },
            valueRange = steps.first.toFloat()..steps.last.toFloat(),
            steps = steps.last - steps.first - 1,
            modifier = Modifier.testTag(tag),
        )
    }
}

/** Boldness: the slider walks the weights the engine can render. */
@Composable
private fun WeightSlider(weight: Int, onChoose: (Int) -> Unit) {
    val weights = EngineSettings.FONT_WEIGHTS
    var position by remember(weight) { mutableFloatStateOf(weights.indexOf(weight).coerceAtLeast(0).toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        SettingLabel(R.string.setting_font_weight)
        Slider(
            value = position,
            onValueChange = { position = it },
            // The book is re-laid out once, when the thumb is let go.
            onValueChangeFinished = { onChoose(weights[position.roundToInt()]) },
            valueRange = 0f..(weights.size - 1).toFloat(),
            steps = weights.size - 2,
            modifier = Modifier.testTag("font-weight"),
        )
    }
}

@Composable
private fun Toggle(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------- contents

/** Contents, bookmarks and quotes of the book, each a list that jumps to its place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentsSheet(
    state: ReaderState,
    onGoTo: (String) -> Unit,
    onRemoveBookmark: (Bookmark) -> Unit,
    onRemoveQuote: (Quote) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(R.string.tab_contents, R.string.tab_bookmarks, R.string.tab_quotes).forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) })
                }
            }
            when (tab) {
                0 -> ContentsList(state, onGoTo)
                1 -> MarksList(
                    state.bookmarks, R.string.empty_bookmarks, key = { it.id },
                    onClick = { onGoTo(it.xpointer) }, onRemove = onRemoveBookmark,
                ) { bookmark ->
                    bookmark.chapter?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    Text(bookmark.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                else -> MarksList(
                    state.quotes, R.string.empty_quotes, key = { it.id },
                    onClick = { onGoTo(it.start) }, onRemove = onRemoveQuote,
                ) { quote ->
                    quote.chapter?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF000000 or quote.color.toLong())))
                        if (quote.starred) {
                            Icon(
                                Icons.Filled.Star, stringResource(R.string.quote_starred), Modifier.padding(start = 4.dp).size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(quote.text, Modifier.padding(start = 8.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    quote.note?.let { Text(it, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun ContentsList(state: ReaderState, onGoTo: (String) -> Unit) {
    if (state.toc.isEmpty()) {
        Empty(R.string.empty_contents)
    } else {
        val current = state.chapter
        // The list opens at the chapter being read.
        val listState = rememberLazyListState(state.toc.indexOf(current).coerceAtLeast(0))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).testTag("contents"), state = listState) {
            items(state.toc) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onGoTo(entry.xpointer) }
                        .padding(start = (20 + 16 * (entry.depth - 1)).dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.title,
                        Modifier.weight(1f),
                        fontWeight = if (entry == current) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text((entry.page + 1).toString(), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun <T : Any> MarksList(
    marks: List<T>,
    empty: Int,
    key: (T) -> Any,
    onClick: (T) -> Unit,
    onRemove: (T) -> Unit,
    content: @Composable (T) -> Unit,
) {
    if (marks.isEmpty()) {
        Empty(empty)
    } else {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            items(marks, key = key) { mark ->
                Row(
                    Modifier.fillMaxWidth().clickable { onClick(mark) }.padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) { content(mark) }
                    IconButton(onClick = { onRemove(mark) }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
                }
            }
        }
    }
}

@Composable
private fun Empty(message: Int) {
    Text(
        stringResource(message),
        Modifier.fillMaxWidth().padding(24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------- search

/** A phrase to find and the places it occurs, each with the words around it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchSheet(
    search: SearchState,
    onSearch: (String) -> Unit,
    onGoTo: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(search.query) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().testTag("search-field"),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { onSearch(query) }) { Icon(Icons.Filled.Search, stringResource(R.string.action_search)) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            )
            val hits = search.hits
            when {
                search.running -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                hits == null -> Unit
                hits.isEmpty() -> Empty(R.string.search_nothing)
                else -> {
                    Text(
                        stringResource(R.string.search_found, hits.size),
                        Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("search-results")) {
                        items(hits) { hit ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onGoTo(hit.range.start) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    buildAnnotatedString {
                                        append(hit.before)
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(hit.range.text) }
                                        append(hit.after)
                                    },
                                    Modifier.weight(1f),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    stringResource(R.string.search_page, hit.page + 1),
                                    Modifier.width(64.dp).padding(start = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
