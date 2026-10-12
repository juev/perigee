package org.evsyukov.perigee

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.evsyukov.perigee.engine.BookReader
import org.evsyukov.perigee.engine.Engine
import org.evsyukov.perigee.engine.EngineSettings
import org.evsyukov.perigee.engine.Page
import org.evsyukov.perigee.engine.TextAlign

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
    // Settings and the position survive a rotation, which recreates the screen.
    var settings by rememberSaveable(stateSaver = SettingsSaver) {
        mutableStateOf(
            with(density) {
                EngineSettings(fontSize = 18.dp.roundToPx(), marginHorizontal = 20.dp.roundToPx(), marginVertical = 28.dp.roundToPx())
            },
        )
    }
    var position by rememberSaveable { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<BookReader?>(null) }
    var page by remember { mutableStateOf<Page?>(null) }
    var failed by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)
    HideSystemBars()

    // The page keeps clear of the camera cutout, and by the same amount on the
    // opposite side, so the text stays centred; the strips stay page-coloured.
    val cutout = WindowInsets.displayCutout.asPaddingValues()
    val direction = LocalLayoutDirection.current
    val sideInset = maxOf(cutout.calculateLeftPadding(direction), cutout.calculateRightPadding(direction))
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF000000 or settings.colors.background.toLong()))
            .padding(start = sideInset, end = sideInset, top = cutout.calculateTopPadding(), bottom = cutout.calculateBottomPadding())
            .clipToBounds(),
    ) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // Two pages side by side only make sense on a screen wider than it is tall.
        fun twoUp(chosen: EngineSettings) = chosen.twoPages && width > height
        // The engine makes the gap between two pages as wide as one side margin, which
        // reads as too tight. The page is therefore laid out with doubled side margins
        // on a canvas wider than the screen, and the surplus is cropped off both edges:
        // the gap doubles while the outer margins stay as chosen.
        fun overdraw(chosen: EngineSettings) = if (twoUp(chosen)) chosen.marginHorizontal else 0
        fun pageWidth(chosen: EngineSettings) = width + 2 * overdraw(chosen)
        fun laidOut(chosen: EngineSettings) = chosen.copy(
            twoPages = twoUp(chosen),
            marginHorizontal = chosen.marginHorizontal + overdraw(chosen),
        )

        LaunchedEffect(file) {
            try {
                session = BookReader.open(context, file, pageWidth(settings), height, laidOut(settings), position)
                    .also { page = it.current() }
            } catch (e: IllegalArgumentException) {
                failed = true
            }
        }
        // Rotation and window resizing re-lay the book out at the new size.
        LaunchedEffect(width, height) {
            session?.let { page = it.resize(pageWidth(settings), height) }
        }
        DisposableEffect(file) {
            onDispose {
                // Closing waits for the engine thread, which outlives this composition.
                session?.let { open -> GlobalScope.launch { open.close() } }
            }
        }

        val shown = page
        LaunchedEffect(shown) { shown?.let { position = it.xpointer } }
        when {
            failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.cannot_open))
                TextButton(onClick = onClose) { Text(stringResource(R.string.close_book)) }
            }
            shown != null -> Image(
                bitmap = shown.bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxHeight()
                    .wrapContentWidth(unbounded = true)
                    .requiredWidth(with(density) { shown.bitmap.width.toDp() })
                    .pointerInput(Unit) {
                        detectTapGestures { tap ->
                            val open = session ?: return@detectTapGestures
                            when {
                                tap.x < size.width / 3 -> scope.launch { page = open.turn(-1) }
                                tap.x > size.width * 2 / 3 -> scope.launch { page = open.turn(1) }
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
                    fontFaces = produceState(emptyList<String>()) { value = Engine.fontFaces(context) }.value,
                    position = stringResource(R.string.page_of, shown.index + 1, shown.pageCount),
                    onChange = { changed ->
                        settings = changed
                        session?.let { open -> scope.launch { page = open.apply(laidOut(changed), pageWidth(changed), height) } }
                    },
                    onClose = onClose,
                )
            }
        }
    }
}

/** Hides the status and navigation bars while a book is on screen; a swipe from the edge shows them briefly. */
@Composable
private fun HideSystemBars() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private val SettingsSaver = listSaver<EngineSettings, Any>(
    save = {
        listOf(
            it.fontFace, it.fontSize, it.lineSpacingPercent, it.marginHorizontal, it.marginVertical,
            it.hyphenation, it.textAlign.name, it.twoPages,
        )
    },
    restore = {
        EngineSettings(
            fontFace = it[0] as String, fontSize = it[1] as Int, lineSpacingPercent = it[2] as Int,
            marginHorizontal = it[3] as Int, marginVertical = it[4] as Int,
            hyphenation = it[5] as Boolean, textAlign = TextAlign.valueOf(it[6] as String), twoPages = it[7] as Boolean,
        )
    },
)

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
        SettingSwitch(R.string.setting_justify, settings.textAlign == TextAlign.Justify) {
            onChange(settings.copy(textAlign = if (it) TextAlign.Justify else TextAlign.Left))
        }
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
