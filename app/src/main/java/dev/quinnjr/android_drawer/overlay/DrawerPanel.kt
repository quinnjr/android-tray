package dev.quinnjr.android_drawer.overlay

import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.zIndex
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.settings.AppKey
import dev.quinnjr.android_drawer.settings.Side
import dev.quinnjr.android_drawer.settings.arrangedBy
import dev.quinnjr.android_drawer.settings.moved
import dev.quinnjr.android_drawer.ui.AppIcon
import dev.quinnjr.android_drawer.ui.theme.AppTheme
import kotlinx.coroutines.launch

private val DISMISS_DRAG = 80.dp
private const val COLUMNS = 2
private val TWO_RECENT_ROWS_MIN_HEIGHT = 560.dp
private val ONE_RECENT_ROW_MIN_HEIGHT = 360.dp
private val CELL_HEIGHT = 84.dp
private val CELL_SPACING = 12.dp

/** Size of app icons in the drawer; the service pre-renders icons at this size. */
val DRAWER_ICON_SIZE = 52.dp
private val AUTOSCROLL_EDGE = 48.dp
private const val AUTOSCROLL_STEP_PX = 12f

/** App names and the settings gear; the panel is always dark, so always white. */
private val LABEL_COLOR = Color.White

/**
 * The slide-out panel. [visible] drives the enter/exit animation; [onHidden] fires once the
 * exit animation has finished so the host can remove the window.
 */
@Composable
fun DrawerPanel(
    /** The pinned apps; null while they're still loading. */
    apps: State<List<LaunchableApp>?>,
    /** How current the apps shown are: a reload under way, or a failed one, shows as a hint. */
    status: State<PinnedStatus>,
    /** Recently used apps; null while they're still being read (their space is kept meanwhile). */
    recent: State<List<LaunchableApp>?>,
    /** Whether to show the recents section at all (usage access granted). */
    expectRecents: Boolean,
    /** Whether any apps are pinned, even if none can be shown right now. */
    hasPinned: State<Boolean>,
    /** A short message to show (e.g. an app that couldn't be started), or null. */
    notice: State<String?>,
    side: Side,
    visible: MutableTransitionState<Boolean>,
    iconsLocked: Boolean,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    onReorder: (List<AppKey>) -> Unit,
    onOpenSettings: () -> Unit,
    /** Called when the layout has room for recents, so they're only read when they'll be shown. */
    onRecentsNeeded: () -> Unit,
    onDismiss: () -> Unit,
    onHidden: () -> Unit,
) {
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) { onHidden() }
    }
    // Always dark so the white app labels stay readable whatever the system theme. Laid out left to
    // right whatever the language: the handle, slides and gestures all use the physical edges
    // (right-to-left text still renders correctly).
    AppTheme(dark = true) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val fromRight = side == Side.RIGHT
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            ) {
                // One transition for scrim and panel: `visible` only reports hidden once both have
                // finished leaving, so the window is never removed mid-slide.
                AnimatedVisibility(visibleState = visible, enter = EnterTransition.None, exit = ExitTransition.None) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .animateEnterExit(enter = fadeIn(), exit = fadeOut())
                                .background(Color.Black.copy(alpha = 0.3f)),
                        )
                        val offscreen = { width: Int ->
                            if (fromRight) {
                                width
                            } else {
                                -width
                            }
                        }
                        Box(
                            Modifier
                                .align(if (fromRight) { Alignment.TopEnd } else { Alignment.TopStart })
                                .fillMaxHeight()
                                .animateEnterExit(
                                    enter = slideInHorizontally(initialOffsetX = offscreen) + fadeIn(),
                                    exit = slideOutHorizontally(targetOffsetX = offscreen) + fadeOut(),
                                ),
                        ) {
                            Panel(
                                apps, status, recent, expectRecents, hasPinned, notice, fromRight, iconsLocked,
                                onLaunch, onReorder, onOpenSettings, onRecentsNeeded, onDismiss,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(
    apps: State<List<LaunchableApp>?>,
    /** How current the apps shown are: a reload under way, or a failed one, shows as a hint. */
    status: State<PinnedStatus>,
    recent: State<List<LaunchableApp>?>,
    /** Whether to show the recents section at all (usage access granted). */
    expectRecents: Boolean,
    /** Whether any apps are pinned, even if none can be shown right now. */
    hasPinned: State<Boolean>,
    /** A short message to show (e.g. an app that couldn't be started), or null. */
    notice: State<String?>,
    fromRight: Boolean,
    iconsLocked: Boolean,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    onReorder: (List<AppKey>) -> Unit,
    onOpenSettings: () -> Unit,
    onRecentsNeeded: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dismissPx = with(LocalDensity.current) { DISMISS_DRAG.toPx() }
    val offset = remember { Animatable(0f) }
    val dragX = remember { floatArrayOf(0f) }
    val scope = rememberCoroutineScope()

    Surface(
        shape = RoundedCornerShape(28.dp),
        // The darkest surface of the (always dark) theme, fully opaque.
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .padding(12.dp)
            .width(212.dp)
            .fillMaxHeight()
            .graphicsLayer { translationX = offset.value }
            // Swallow taps on the panel background so they don't reach the scrim.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .pointerInput(fromRight) {
                detectHorizontalDragGestures(
                    onDragStart = { dragX[0] = offset.value },
                    onDragEnd = {
                        val towardEdge = if (fromRight) { dragX[0] } else { -dragX[0] }
                        if (towardEdge > dismissPx) { onDismiss() } else { scope.launch { offset.animateTo(0f) } }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                ) { _, dx ->
                    // Accumulate here, not from offset.value: snapTo runs later, so several drag
                    // events in a row would otherwise read the same stale value and drop deltas.
                    // Only follow drags toward the edge the panel came from.
                    val next = dragX[0] + dx
                    dragX[0] = if (fromRight) { next.coerceAtLeast(0f) } else { next.coerceAtMost(0f) }
                    scope.launch { offset.snapTo(dragX[0]) }
                }
            },
    ) {
        BoxWithConstraints {
            // Fewer recent rows when the drawer is short (landscape), so pinned apps keep room.
            val recentRows = when {
                !expectRecents -> 0
                maxHeight >= TWO_RECENT_ROWS_MIN_HEIGHT -> 2
                maxHeight >= ONE_RECENT_ROW_MIN_HEIGHT -> 1
                else -> 0
            }
            Column(Modifier.padding(top = 16.dp)) {
                // Fixed above the scrolling pinned apps, so recents arriving late never shift or hide them.
                if (recentRows > 0) { RecentSection(recent.value?.take(recentRows * COLUMNS), recentRows, onLaunch) }
                LaunchedEffect(recentRows > 0) {
                    if (recentRows > 0) {
                        onRecentsNeeded()
                    }
                }
                Box(Modifier.weight(1f)) {
                    val pinned = apps.value
                    val current = status.value
                    when {
                        // Nothing loaded yet (or only an empty list, while pinned apps are reloading;
                        // with none pinned, "No apps yet" stays put through reloads).
                        pinned == null || (pinned.isEmpty() && current == PinnedStatus.RELOADING && hasPinned.value) -> CircularProgressIndicator(
                            color = LABEL_COLOR,
                            modifier = Modifier.align(Alignment.Center).size(32.dp),
                        )
                        // Loading failed with nothing to show (not the same as unavailable apps).
                        pinned.isEmpty() && current == PinnedStatus.FAILED && hasPinned.value -> Text(
                            "Couldn't load your pinned apps. Open the drawer again to retry.",
                            color = LABEL_COLOR,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                        pinned.isEmpty() -> NoApps(hasPinned.value, onOpenSettings, Modifier.fillMaxSize())
                        else -> {
                            // Stays composed while reloading (the last list is kept), so a press or
                            // drag under way and the scroll position survive (unless the list turns
                            // out empty: then there's nothing left to drag). Taps launch at once: an
                            // app gone meanwhile isn't started and says so. The status shows as a hint.
                            PinnedGrid(pinned, iconsLocked, onLaunch, onReorder)
                            if (current == PinnedStatus.RELOADING) {
                                CircularProgressIndicator(
                                    color = LABEL_COLOR,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp),
                                )
                            }
                        }
                    }
                }
                // Its own line below the grid (not over its last row): a message, else a failed refresh.
                val failedRefresh = status.value == PinnedStatus.FAILED && !apps.value.isNullOrEmpty()
                (notice.value ?: if (failedRefresh) { "Couldn't refresh" } else { null })?.let { text ->
                    Text(
                        text,
                        color = LABEL_COLOR,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp),
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "Edge Drawer settings", tint = LABEL_COLOR)
                }
            }
        }
    }
}

/**
 * [rows] rows of recently used apps and a divider below them, always the same height — empty
 * while [recent] is still null — so nothing below ever moves when they arrive.
 */
@Composable
private fun RecentSection(recent: List<LaunchableApp>?, rows: Int, onLaunch: (LaunchableApp, Rect?) -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(CELL_SPACING)) {
        Box(Modifier.fillMaxWidth().height(CELL_HEIGHT * rows + CELL_SPACING * (rows - 1))) {
            when {
                recent == null -> Unit
                recent.isEmpty() -> Text(
                    "No recent apps",
                    color = LABEL_COLOR.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(CELL_SPACING)) {
                    recent.chunked(COLUMNS).forEach { row ->
                        Row {
                            row.forEach { app -> AppCell(app, onLaunch, Modifier.weight(1f)) }
                            repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 8.dp))
    }
}

/**
 * The pinned apps. Unless [iconsLocked], a long-press picks one up so it can be dragged to a new
 * place; the new order is saved on release.
 */
@Composable
private fun PinnedGrid(
    latest: List<LaunchableApp>,
    iconsLocked: Boolean,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    onReorder: (List<AppKey>) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val haptics = LocalHapticFeedback.current
    val edgePx = with(LocalDensity.current) { AUTOSCROLL_EDGE.toPx() }
    val drag = remember { GridDrag() }
    // The gesture below outlives recompositions (reloads don't restart it), so it reads these.
    val currentLatest by rememberUpdatedState(latest)
    val currentOnReorder by rememberUpdatedState(onReorder)
    // While a drag is in progress its order is shown, mapped onto the current apps: a reload
    // meanwhile mustn't move icons under the finger, but updated icons show, removed apps go (the
    // drag ends, saving the other moves, if it's the dragged one) and newly pinned ones are added at
    // the end. The released order then shows at once: the drawer applies it while it saves.
    val dragged = remember(drag.order, latest) { drag.order?.let { order -> latest.arrangedBy(order) { it.key } } }
    val draggedAppGone = drag.key != null && dragged != null && dragged.none { it.key == drag.key }
    val order = dragged ?: latest
    val currentOrder by rememberUpdatedState(order)
    // Finger position in grid coordinates, and where on the icon it grabbed.
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    // Auto-scroll only once the lifted app has actually been moved.
    var hasMoved by remember { mutableStateOf(false) }

    fun appAt(position: Offset): LazyGridItemInfo? = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
        position.x >= it.offset.x && position.x < it.offset.x + it.size.width &&
            position.y >= it.offset.y && position.y < it.offset.y + it.size.height
    }

    /** Moves the lifted app into the slot under the finger (indices of the list on screen). */
    fun followPointer() {
        val key = drag.key ?: return
        val current = currentOrder
        val from = current.indexOfFirst { it.key == key }
        // Wait for the grid to lay out the previous move before moving again.
        val laidOut = gridState.layoutInfo.visibleItemsInfo.any { it.index == from && it.key == key.encode() }
        // By key, not index: the layout may still be a frame behind the list (e.g. after a reload).
        val to = appAt(pointer)?.let { info -> current.indexOfFirst { it.key.encode() == info.key } } ?: return
        if (laidOut && to >= 0 && to != from) {
            drag.order = current.moved(from, to).map { it.key }
            drag.moved = true
        }
    }

    /**
     * Ends the drag, saving the order if the user moved anything: on release, and also when the
     * system cancels the gesture (shade pulled, Home, screen off), so a rearrangement isn't lost.
     * Built from the drag state itself (not the last frame), and only the apps that were being
     * dragged among: one pinned meanwhile keeps the place it was given.
     */
    fun finishDrag() {
        val keys = drag.order
        if (drag.key != null && drag.moved && keys != null) {
            val members = drag.start.toSet()
            val chosen = currentLatest.arrangedBy(keys) { it.key }.map { it.key }.filter { it in members }
            // Moved and moved back: nothing to save.
            if (chosen != drag.start.filter { it in chosen }) {
                currentOnReorder(chosen)
            }
        }
        drag.clear()
    }

    if (draggedAppGone) {
        // The rest of this gesture is still consumed (see below) until the finger lifts.
        SideEffect {
            finishDrag()
            drag.abandoned = true
        }
    }

    // While an app is lifted, keep scrolling each frame the finger rests near the top or bottom.
    LaunchedEffect(drag.key != null) {
        while (drag.key != null) {
            withFrameNanos { }
            if (!hasMoved) { continue }
            val height = gridState.layoutInfo.viewportSize.height
            val delta = when {
                pointer.y < edgePx -> -AUTOSCROLL_STEP_PX
                pointer.y > height - edgePx -> AUTOSCROLL_STEP_PX
                else -> 0f
            }
            if (delta != 0f && gridState.dispatchRawDelta(delta) != 0f) { followPointer() }
        }
    }

    val reorder = if (iconsLocked) {
        Modifier
    } else {
        // Not keyed on the apps: a reload must not restart it and drop a press already under way.
        Modifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { start ->
                    val info = appAt(start) ?: return@detectDragGesturesAfterLongPress
                    val shown = currentOrder
                    // By key, not index: the layout may still be a frame behind a reload.
                    val app = shown.firstOrNull { it.key.encode() == info.key } ?: return@detectDragGesturesAfterLongPress
                    drag.begin(app.key, shown)
                    pointer = start
                    hasMoved = false
                    grab = start - info.offset.toOffset()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDragEnd = {
                    finishDrag()
                    drag.abandoned = false
                },
                onDragCancel = {
                    finishDrag()
                    drag.abandoned = false
                },
            ) { change, _ ->
                if (drag.key == null) {
                    // A drag ended early (its app vanished): keep the gesture from scrolling the grid.
                    if (drag.abandoned) {
                        change.consume()
                    }
                    return@detectDragGesturesAfterLongPress
                }
                change.consume()
                pointer = change.position
                hasMoved = true
                followPointer()
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = gridState,
        userScrollEnabled = drag.key == null && !drag.abandoned,
        // Plain padding, not contentPadding: the drag hit-testing compares the finger (in this
        // modifier's coordinates) with item offsets, which contentPadding would shift.
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = CELL_SPACING, bottom = 16.dp).then(reorder),
        verticalArrangement = Arrangement.spacedBy(CELL_SPACING),
    ) {
        items(order, key = { it.key.encode() }) { app ->
            val lifted = app.key == drag.key
            val cell = if (lifted) {
                Modifier.zIndex(1f).graphicsLayer {
                    // Follow the finger, relative to wherever the grid currently places this cell.
                    val placed = gridState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == app.key.encode() }?.offset?.toOffset() ?: Offset.Zero
                    val shift = pointer - grab - placed
                    translationX = shift.x
                    translationY = shift.y
                    scaleX = 1.1f
                    scaleY = 1.1f
                }
            } else {
                Modifier.animateItem()
            }
            // A long-press that turns into a drag must not also launch the app on release.
            AppCell(app, { a, b -> if (drag.key == null) { onLaunch(a, b) } }, cell)
        }
    }
}

/** The pinned app being dragged in the drawer, if any ([key] null when not dragging). */
private class GridDrag {
    var key by mutableStateOf<AppKey?>(null)
        private set
    /** The order being dragged, as keys (shown mapped onto the current apps). */
    var order by mutableStateOf<List<AppKey>?>(null)
    /** Whether the user has moved anything (a long-press and release alone saves nothing). */
    var moved = false
    /** The drag ended early (its app vanished) but the finger is still down. */
    var abandoned by mutableStateOf(false)
    /**
     * The apps there when the drag began, in their order then: only their order is saved (one
     * pinned meanwhile keeps its place).
     */
    var start: List<AppKey> = emptyList()
        private set

    fun begin(key: AppKey, apps: List<LaunchableApp>) {
        order = apps.map { it.key }
        start = apps.map { it.key }
        moved = false
        this.key = key
    }

    fun clear() {
        key = null
        order = null
        moved = false
    }
}

@Composable
private fun NoApps(hasPinned: Boolean, onOpenSettings: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        if (hasPinned) {
            // Pinned apps exist but none can be shown, e.g. they're all in a paused work profile.
            Text(
                "Your pinned apps aren't available right now",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        } else {
            Text("No apps yet", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onOpenSettings) { Text("Add apps") }
        }
    }
}

@Composable
private fun AppCell(app: LaunchableApp, onLaunch: (LaunchableApp, Rect?) -> Unit, modifier: Modifier = Modifier) {
    val position = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .height(CELL_HEIGHT)
            .clickable {
                val b = position[0]?.takeIf { it.isAttached }?.boundsInWindow()
                onLaunch(app, b?.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) })
            }
            .padding(4.dp),
    ) {
        AppIcon(app, Modifier.onGloballyPositioned { position[0] = it }, DRAWER_ICON_SIZE)
        Text(
            app.label,
            fontSize = 12.sp,
            color = LABEL_COLOR,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
