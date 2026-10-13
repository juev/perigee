package org.evsyukov.perigee.reader

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.evsyukov.perigee.R
import org.evsyukov.perigee.data.PageTurn
import org.evsyukov.perigee.data.ReaderPrefs
import org.evsyukov.perigee.data.TapScheme
import org.evsyukov.perigee.engine.Page

/** What the reader opened on top of the page. */
private enum class Panel { None, Menu, Settings, Contents, Search }

/** What a finger moving over the page turned out to be doing. */
private enum class Drag { Undecided, Brightness, Done }

/** What a touch on an empty part of the page does. */
internal enum class Zone { Forward, Back, Menu, DayNight, Bookmark, None }

/**
 * Where a touch landed, by the reader's turn scheme. The middle of the page
 * always opens the menu and the top corners keep their shortcuts, whichever
 * scheme turns the pages.
 */
internal fun zoneOf(x: Float, y: Float, size: IntSize, prefs: ReaderPrefs): Zone {
    val column = (x * 3 / size.width).toInt().coerceIn(0, 2)
    val row = (y * 3 / size.height).toInt().coerceIn(0, 2)
    val inTopStrip = y < size.height / 8f
    return when {
        inTopStrip && x < size.width / 4f && prefs.dayNightByCorner -> Zone.DayNight
        inTopStrip && x > size.width * 3 / 4f && prefs.bookmarkByCorner -> Zone.Bookmark
        column == 1 && row == 1 -> Zone.Menu
        else -> when (prefs.tapScheme) {
            TapScheme.LeftRight -> if (column == 0) Zone.Back else Zone.Forward
            TapScheme.TopBottom -> if (y < size.height / 2f) Zone.Back else Zone.Forward
            TapScheme.AnyForward -> Zone.Forward
            TapScheme.Off -> Zone.None
        }
    }
}

/** A book on the whole screen. */
@Composable
fun ReaderScreen(file: File, onClose: () -> Unit, viewModel: ReaderViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var panel by rememberSaveable { mutableStateOf(Panel.None) }

    LaunchedEffect(viewModel, file) { viewModel.open(file) }
    val close = {
        viewModel.close()
        onClose()
    }
    BackHandler {
        when {
            state.selection != null || state.quoteTouch != null -> viewModel.clearSelection()
            panel != Panel.None -> panel = Panel.None
            state.canGoBack -> viewModel.goBack()
            else -> close()
        }
    }
    HideSystemBars()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.saveCache() }
    ScreenBrightness(prefs.brightness)
    VolumeKeys(prefs.volumeKeysTurnPages && panel == Panel.None) { forward -> viewModel.turn(if (forward) 1 else -1) }

    val colors = ReaderViewModel.colorsOf(prefs.colorScheme)
    val paper = Color(0xFF000000 or colors.background.toLong())
    val ink = Color(0xFF000000 or colors.text.toLong())

    // The page keeps clear of the camera cutout, and by the same amount on the
    // opposite side, so the text stays centred; the strips stay page-coloured.
    val cutout = WindowInsets.displayCutout.asPaddingValues()
    val direction = LocalLayoutDirection.current
    val sideInset = maxOf(cutout.calculateLeftPadding(direction), cutout.calculateRightPadding(direction))
    Box(Modifier.fillMaxSize().background(paper)) {
        // The area the page gets; known once the screen is measured.
        var area by remember { mutableStateOf(IntSize.Zero) }
        val densityDpi = LocalConfiguration.current.densityDpi
        LaunchedEffect(viewModel, area, density.density) {
            if (area != IntSize.Zero) {
                // The area settles over a few frames, as the cutout inset arrives or a
                // rotation finishes. Laying a book out takes seconds: wait for the last size.
                delay(VIEWPORT_SETTLE_MILLIS)
                viewModel.setViewport(
                    Viewport(area.width, area.height, density.density, densityDpi),
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = sideInset, end = sideInset, top = cutout.calculateTopPadding(), bottom = cutout.calculateBottomPadding())
                .clipToBounds()
                .onSizeChanged { area = it },
        ) {
            val width = area.width

            val shown = state.page
            when {
                state.failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.cannot_open), color = ink)
                    TextButton(onClick = close) { Text(stringResource(R.string.close_book)) }
                }
                shown != null -> {
                    // With two pages the bitmap is wider than the screen and shown centred.
                    val offsetX = (shown.bitmap.width - width) / 2
                    val currentPrefs by rememberUpdatedState(prefs)
                    val menuOpen by rememberUpdatedState(panel == Panel.Menu)
                    val pageLabel = stringResource(R.string.page_description, shown.index + 1, shown.pageCount)
                    PageView(
                        page = shown,
                        turned = state.turned,
                        pageTurn = prefs.pageTurn,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("page")
                            .semantics { contentDescription = pageLabel }
                            .pointerInput(offsetX) {
                                detectTapGestures { tap ->
                                    scope.launch {
                                        when (val result = viewModel.tap(tap.x.toInt() + offsetX, tap.y.toInt())) {
                                            TapResult.Handled -> Unit
                                            is TapResult.ExternalLink -> openLink(context as? Activity, result.url)
                                            TapResult.Nothing -> when {
                                                menuOpen -> panel = Panel.None
                                                else -> when (zoneOf(tap.x, tap.y, size, currentPrefs)) {
                                                    Zone.Forward -> viewModel.turn(1)
                                                    Zone.Back -> viewModel.turn(-1)
                                                    Zone.Menu -> panel = Panel.Menu
                                                    Zone.DayNight -> viewModel.updatePrefs { it.toggledNight() }
                                                    Zone.Bookmark -> viewModel.toggleBookmark()
                                                    Zone.None -> Unit
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            .pointerInput(offsetX) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { at -> viewModel.select(at.x.toInt() + offsetX, at.y.toInt(), extend = false) },
                                    onDrag = { change, _ ->
                                        viewModel.select(change.position.x.toInt() + offsetX, change.position.y.toInt(), extend = true)
                                    },
                                )
                            }
                            .pointerInput(Unit) {
                                var moved = Offset.Zero
                                var gesture = Drag.Undecided
                                var startedOnEdge = false
                                var startBrightness = 0f
                                detectDragGestures(
                                    onDragStart = { at ->
                                        moved = Offset.Zero
                                        gesture = Drag.Undecided
                                        startedOnEdge = at.x < size.width * EDGE_SHARE
                                        startBrightness = currentPrefs.brightness ?: 0.5f
                                    },
                                    onDrag = { _, amount ->
                                        moved += amount
                                        val vertical = abs(moved.y) > abs(moved.x)
                                        if (gesture == Drag.Undecided && maxOf(abs(moved.x), abs(moved.y)) > SWIPE.toPx()) {
                                            // One finger movement is one thing: the way it went first decides.
                                            val turnsVertically = currentPrefs.pageTurn == PageTurn.Vertical
                                            gesture = when {
                                                startedOnEdge && vertical && currentPrefs.brightnessGesture -> Drag.Brightness
                                                vertical == turnsVertically -> {
                                                    // The page turns as the finger passes the threshold, not when it lifts.
                                                    val along = if (turnsVertically) moved.y else moved.x
                                                    viewModel.turn(if (along < 0) 1 else -1)
                                                    Drag.Done
                                                }
                                                else -> Drag.Done
                                            }
                                        }
                                        if (gesture == Drag.Brightness) {
                                            // The whole height of the screen is the whole range.
                                            val level = (startBrightness - moved.y / size.height).coerceIn(MIN_BRIGHTNESS, 1f)
                                            viewModel.updatePrefs { it.copy(brightness = level) }
                                        }
                                    },
                                )
                            },
                    )
                    Marks(state, offsetX, ink)
                    ProgressLine(
                        progress = shown.progress,
                        chapters = state.toc.filter { it.depth == 1 }.map { it.page.toFloat() / shown.pageCount },
                        color = ink,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        if (state.busy) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("busy"), color = ink.copy(alpha = 0.5f))
        }
        AnimatedVisibility(panel == Panel.Menu && state.page != null, enter = fadeIn(), exit = fadeOut()) {
            ReaderMenu(
                state = state,
                onClose = close,
                onSearch = { panel = Panel.Search },
                onContents = { panel = Panel.Contents },
                onSettings = { panel = Panel.Settings },
                onToggleBookmark = viewModel::toggleBookmark,
                onGoBack = viewModel::goBack,
                onSeek = viewModel::goToProgress,
            )
        }
        SelectionBars(state, viewModel, Modifier.align(Alignment.BottomCenter))
    }

    when (panel) {
        Panel.Settings -> SettingsSheet(prefs, state.fontFaces, viewModel::updatePrefs) { panel = Panel.None }
        Panel.Contents -> ContentsSheet(
            state = state,
            onGoTo = { xpointer ->
                panel = Panel.None
                viewModel.goTo(xpointer)
            },
            onRemoveBookmark = viewModel::removeBookmark,
            onRemoveQuote = viewModel::removeQuote,
            onDismiss = { panel = Panel.None },
        )
        Panel.Search -> SearchSheet(
            search = state.search,
            onSearch = viewModel::search,
            onGoTo = { xpointer ->
                panel = Panel.None
                viewModel.goTo(xpointer)
            },
            onDismiss = { panel = Panel.None },
        )
        Panel.None, Panel.Menu -> Unit
    }
}

/** The page bitmap; a turn slides the new page in from the side it comes from. */
@Composable
private fun PageView(page: Page, turned: Int, pageTurn: PageTurn, modifier: Modifier) {
    val density = LocalDensity.current
    AnimatedContent(
        targetState = page,
        modifier = modifier,
        transitionSpec = {
            when {
                // A jump or a redraw of the same page: nothing to slide.
                turned == 0 -> EnterTransition.None togetherWith ExitTransition.None
                pageTurn == PageTurn.Horizontal ->
                    slideInHorizontally(tween(TURN_MILLIS)) { it * turned } togetherWith
                        slideOutHorizontally(tween(TURN_MILLIS)) { -it * turned }
                else ->
                    slideInVertically(tween(TURN_MILLIS)) { it * turned } togetherWith
                        slideOutVertically(tween(TURN_MILLIS)) { -it * turned }
            }
        },
        label = "page",
    ) { shown ->
        Image(
            bitmap = shown.bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxHeight()
                .wrapContentWidth(unbounded = true)
                .requiredWidth(with(density) { shown.bitmap.width.toDp() }),
        )
    }
}

/** The selection being made, the quote touched and the bookmark ribbon, drawn over the page. */
@Composable
private fun Marks(state: ReaderState, offsetX: Int, ink: Color) {
    val rects = state.selection?.rects ?: state.quoteTouch?.rects
    Canvas(Modifier.fillMaxSize()) {
        rects?.forEach { rect ->
            drawRect(
                color = SELECTION,
                topLeft = Offset((rect.left - offsetX).toFloat(), rect.top.toFloat()),
                size = Size(rect.width().toFloat(), rect.height().toFloat()),
            )
        }
        if (state.pageBookmark != null) {
            val ribbon = Size(12.dp.toPx(), 28.dp.toPx())
            drawRect(ink.copy(alpha = 0.6f), Offset(size.width - ribbon.width - 16.dp.toPx(), 0f), ribbon)
        }
    }
}

/** A thin line along the bottom edge: how far the reader is, with a tick where each chapter starts. */
@Composable
private fun ProgressLine(progress: Float, chapters: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        val y = size.height - 3.dp.toPx()
        drawLine(color.copy(alpha = 0.2f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        drawLine(color.copy(alpha = 0.55f), Offset(0f, y), Offset(size.width * progress, y), 2.dp.toPx())
        for (chapter in chapters) {
            drawCircle(color.copy(alpha = 0.45f), 1.5.dp.toPx(), Offset(size.width * chapter, y))
        }
    }
}

/** Hides the status and navigation bars while a book is on screen; a swipe from the edge shows them briefly. */
@Composable
private fun HideSystemBars() {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Applies the brightness the reader set by gesture; null hands it back to the system. */
@Composable
private fun ScreenBrightness(level: Float?) {
    val window = LocalActivity.current?.window
    DisposableEffect(window, level) {
        fun set(value: Float) {
            window?.attributes = window?.attributes?.apply { screenBrightness = value }
        }
        set(level ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        onDispose { set(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }
}

/** Lets the volume keys turn pages while the book is on screen. */
@Composable
private fun VolumeKeys(enabled: Boolean, onTurn: (forward: Boolean) -> Unit) {
    val host = LocalActivity.current as? VolumeKeyHost
    val turn by rememberUpdatedState(onTurn)
    DisposableEffect(host, enabled) {
        host?.onVolumeKey = if (enabled) { down -> turn(down) } else null
        onDispose { host?.onVolumeKey = null }
    }
}

/** An activity that can hand its volume keys to the screen. */
interface VolumeKeyHost {
    /** Called with true for volume down, false for volume up; null leaves the keys to the system. */
    var onVolumeKey: ((down: Boolean) -> Unit)?
}

private fun openLink(activity: Activity?, url: String) {
    // No app may be able to open the address; the book stays as it is then.
    runCatching { activity?.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

private val SWIPE = 48.dp
private const val VIEWPORT_SETTLE_MILLIS = 100L
private const val EDGE_SHARE = 0.15f
private const val MIN_BRIGHTNESS = 0.01f
private const val TURN_MILLIS = 180
private val SELECTION = Color(0x553D8BFD)
