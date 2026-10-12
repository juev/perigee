package org.evsyukov.perigee

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.evsyukov.perigee.engine.Engine
import org.evsyukov.perigee.engine.EngineSettings

/**
 * A book on the whole screen. Tap the right or left third to turn the page,
 * the middle to open layout settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var settings by remember {
        mutableStateOf(
            with(density) {
                EngineSettings(fontSize = 18.dp.roundToPx(), marginHorizontal = 20.dp.roundToPx(), marginVertical = 28.dp.roundToPx())
            },
        )
    }
    var session by remember { mutableStateOf<ReaderSession?>(null) }
    var page by remember { mutableStateOf<PageState?>(null) }
    var failed by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight

        LaunchedEffect(file) {
            try {
                session = ReaderSession.open(context, file, width, height, settings).also { page = it.render(width, height) }
            } catch (e: IllegalArgumentException) {
                failed = true
            }
        }
        // Rotation and window resizing re-lay the book out at the new size.
        LaunchedEffect(width, height) {
            session?.let { page = it.render(width, height) }
        }
        DisposableEffect(file) {
            onDispose {
                // Closing waits for the engine thread, which outlives this composition.
                session?.let { open -> GlobalScope.launch { open.close() } }
            }
        }

        val shown = page
        when {
            failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.cannot_open))
                TextButton(onClick = onClose) { Text(stringResource(R.string.close_book)) }
            }
            shown != null -> Image(
                bitmap = shown.bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(settings.twoPages) {
                        val step = if (settings.twoPages) 2 else 1
                        detectTapGestures { tap ->
                            val open = session ?: return@detectTapGestures
                            when {
                                tap.x < size.width / 3 -> scope.launch { page = open.turn(-step, width, height) }
                                tap.x > size.width * 2 / 3 -> scope.launch { page = open.turn(step, width, height) }
                                else -> showSettings = true
                            }
                        }
                    },
            )
        }

        if (showSettings && shown != null) {
            ModalBottomSheet(onDismissRequest = { showSettings = false }) {
                SettingsPanel(
                    settings = settings,
                    fontFaces = remember { Engine.fontFaces(context) },
                    position = stringResource(R.string.page_of, shown.page + 1, shown.pageCount),
                    onChange = { changed ->
                        settings = changed
                        session?.let { open -> scope.launch { page = open.apply(changed, width, height) } }
                    },
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    settings: EngineSettings,
    fontFaces: List<String>,
    position: String,
    onChange: (EngineSettings) -> Unit,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(position)
            TextButton(onClick = onClose) { Text(stringResource(R.string.close_book)) }
        }

        var fontMenu by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { fontMenu = true }) {
                Text("${stringResource(R.string.setting_font)}: ${settings.fontFace}")
            }
            DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                for (face in fontFaces) {
                    DropdownMenuItem(text = { Text(face) }, onClick = {
                        fontMenu = false
                        onChange(settings.copy(fontFace = face))
                    })
                }
            }
        }

        // Sliders report every intermediate value; the book is re-laid out once, on release.
        val sizeDp = with(density) { settings.fontSize.toDp().value }
        SettingSlider(R.string.setting_font_size, sizeDp, 10f..40f) {
            onChange(settings.copy(fontSize = with(density) { it.dp.roundToPx() }))
        }
        SettingSlider(R.string.setting_line_spacing, settings.lineSpacingPercent.toFloat(), 80f..180f) {
            onChange(settings.copy(lineSpacingPercent = it.roundToInt()))
        }
        val marginDp = with(density) { settings.marginHorizontal.toDp().value }
        SettingSlider(R.string.setting_margins, marginDp, 0f..80f) {
            onChange(settings.copy(marginHorizontal = with(density) { it.dp.roundToPx() }))
        }

        SettingSwitch(R.string.setting_hyphenation, settings.hyphenation) { onChange(settings.copy(hyphenation = it)) }
        SettingSwitch(R.string.setting_justify, settings.justify) { onChange(settings.copy(justify = it)) }
        SettingSwitch(R.string.setting_two_pages, settings.twoPages) { onChange(settings.copy(twoPages = it)) }
    }
}

@Composable
private fun SettingSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, onCommit: (Float) -> Unit) {
    var dragged by remember(value) { mutableStateOf(value) }
    Text(stringResource(label, dragged.roundToInt()))
    Slider(value = dragged, onValueChange = { dragged = it }, valueRange = range, onValueChangeFinished = { onCommit(dragged) })
}

@Composable
private fun SettingSwitch(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
