package dev.quinnjr.android_drawer.overlay

import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.zIndex
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.settings.AppKey
import dev.quinnjr.android_drawer.settings.Side
import dev.quinnjr.android_drawer.ui.AppIcon
import dev.quinnjr.android_drawer.ui.moved
import dev.quinnjr.android_drawer.ui.theme.AppTheme
import kotlinx.coroutines.launch

private val DISMISS_DRAG = 80.dp
private const val COLUMNS = 2
private const val RECENT_ROWS = 2
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
    apps: List<LaunchableApp>,
    /** Recently used apps; null while they're still being read (their space is kept meanwhile). */
    recent: State<List<LaunchableApp>?>,
    side: Side,
    visible: MutableTransitionState<Boolean>,
    iconsLocked: Boolean,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    onReorder: (List<AppKey>) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    onHidden: () -> Unit,
) {
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) onHidden()
    }
    // Always dark so the white app labels stay readable whatever the system theme.
    AppTheme(dark = true) {
        val fromRight = side == Side.RIGHT
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        ) {
            AnimatedVisibility(visibleState = visible, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
            }
            AnimatedVisibility(
                visibleState = visible,
                modifier = Modifier
                    .align(if (fromRight) Alignment.TopEnd else Alignment.TopStart)
                    .fillMaxHeight(),
                enter = slideInHorizontally { if (fromRight) it else -it } + fadeIn(),
                exit = slideOutHorizontally { if (fromRight) it else -it } + fadeOut(),
            ) {
                Panel(apps, recent, fromRight, iconsLocked, onLaunch, onReorder, onOpenSettings, onDismiss)
            }
        }
    }
}

@Composable
private fun Panel(
    apps: List<LaunchableApp>,
    recent: State<List<LaunchableApp>?>,
    fromRight: Boolean,
    iconsLocked: Boolean,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    onReorder: (List<AppKey>) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dismissPx = with(LocalDensity.current) { DISMISS_DRAG.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
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
                    onDragEnd = {
                        val towardEdge = if (fromRight) offset.value else -offset.value
                        if (towardEdge > dismissPx) onDismiss() else scope.launch { offset.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                ) { _, dx ->
                    // Only follow drags toward the edge the panel came from.
                    val next = offset.value + dx
                    scope.launch { offset.snapTo(if (fromRight) next.coerceAtLeast(0f) else next.coerceAtMost(0f)) }
                }
            },
    ) {
        Column(Modifier.padding(top = 16.dp)) {
            // Fixed above the scrolling pinned apps, so recents arriving late never shift or hide them.
            // Its space is decided once, when the drawer opens: kept if recents are expected.
            val showRecents = remember { recent.value == null }
            if (showRecents) RecentSection(recent.value, onLaunch)
            Box(Modifier.weight(1f)) {
                if (apps.isEmpty()) {
                    NoApps(onOpenSettings, Modifier.fillMaxSize())
                } else {
                    PinnedGrid(apps, iconsLocked, onLaunch, onReorder)
                }
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

/**
 * Two rows of recently used apps and a divider below them, always the same height — empty while
 * [recent] is still null — so nothing below ever moves when they arrive.
 */
@Composable
private fun RecentSection(recent: List<LaunchableApp>?, onLaunch: (LaunchableApp, Rect?) -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(CELL_SPACING)) {
        Box(Modifier.fillMaxWidth().height(CELL_HEIGHT * RECENT_ROWS + CELL_SPACING * (RECENT_ROWS - 1))) {
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
    apps: List<LaunchableApp>,
    iconsLocked: Boolean,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    onReorder: (List<AppKey>) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val haptics = LocalHapticFeedback.current
    val edgePx = with(LocalDensity.current) { AUTOSCROLL_EDGE.toPx() }
    var order by remember(apps) { mutableStateOf(apps) }
    // The order last handed to onReorder, so a later drag that restores it is saved too.
    var saved by remember(apps) { mutableStateOf(apps) }
    var dragging by remember { mutableStateOf<AppKey?>(null) }
    // Finger position in grid coordinates, and where on the icon it grabbed.
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    // Auto-scroll only once the lifted app has actually been moved.
    var moved by remember { mutableStateOf(false) }

    fun appAt(position: Offset): LazyGridItemInfo? = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
        position.x >= it.offset.x && position.x < it.offset.x + it.size.width &&
            position.y >= it.offset.y && position.y < it.offset.y + it.size.height
    }

    /** Moves the lifted app into the slot under the finger. */
    fun followPointer() {
        val key = dragging ?: return
        val from = order.indexOfFirst { it.key == key }
        // Wait for the grid to lay out the previous move before moving again.
        val laidOut = gridState.layoutInfo.visibleItemsInfo.any { it.index == from && it.key == key.encode() }
        val target = appAt(pointer) ?: return
        if (laidOut && target.index != from) order = order.moved(from, target.index)
    }

    fun finishDrag() {
        if (dragging != null && order != saved) {
            onReorder(order.map { it.key })
            saved = order
        }
        dragging = null
    }

    // While an app is lifted, keep scrolling each frame the finger rests near the top or bottom.
    LaunchedEffect(dragging != null) {
        while (dragging != null) {
            withFrameNanos { }
            if (!moved) continue
            val height = gridState.layoutInfo.viewportSize.height
            val delta = when {
                pointer.y < edgePx -> -AUTOSCROLL_STEP_PX
                pointer.y > height - edgePx -> AUTOSCROLL_STEP_PX
                else -> 0f
            }
            if (delta != 0f && gridState.dispatchRawDelta(delta) != 0f) followPointer()
        }
    }

    val reorder = if (iconsLocked) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { start ->
                    val info = appAt(start) ?: return@detectDragGesturesAfterLongPress
                    dragging = order[info.index].key
                    pointer = start
                    moved = false
                    grab = start - info.offset.toOffset()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDragEnd = ::finishDrag,
                onDragCancel = ::finishDrag,
            ) { change, _ ->
                if (dragging == null) return@detectDragGesturesAfterLongPress
                change.consume()
                pointer = change.position
                moved = true
                followPointer()
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = gridState,
        userScrollEnabled = dragging == null,
        // Plain padding, not contentPadding: the drag hit-testing compares the finger (in this
        // modifier's coordinates) with item offsets, which contentPadding would shift.
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = CELL_SPACING, bottom = 16.dp).then(reorder),
        verticalArrangement = Arrangement.spacedBy(CELL_SPACING),
    ) {
        items(order, key = { it.key.encode() }) { app ->
            val lifted = app.key == dragging
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
            AppCell(app, { a, b -> if (dragging == null) onLaunch(a, b) }, cell)
        }
    }
}

@Composable
private fun NoApps(onOpenSettings: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("No apps yet", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onOpenSettings) { Text("Add apps") }
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
