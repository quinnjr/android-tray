package dev.quinnjr.android_drawer.overlay

import android.graphics.Rect
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.settings.Side
import kotlinx.coroutines.launch

private val DISMISS_DRAG = 80.dp

/**
 * The slide-out panel. [visible] drives the enter/exit animation; [onHidden] fires once the
 * exit animation has finished so the host can remove the window.
 */
@Composable
fun DrawerPanel(
    apps: List<LaunchableApp>,
    side: Side,
    centerYPx: Int,
    visible: MutableTransitionState<Boolean>,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    onAddApps: () -> Unit,
    onDismiss: () -> Unit,
    onHidden: () -> Unit,
) {
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) onHidden()
    }
    DrawerTheme {
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
                    .centerOn(centerYPx),
                enter = slideInHorizontally { if (fromRight) it else -it } + fadeIn(),
                exit = slideOutHorizontally { if (fromRight) it else -it } + fadeOut(),
            ) {
                Panel(apps, fromRight, onLaunch, onAddApps, onDismiss)
            }
        }
    }
}

@Composable
private fun Panel(
    apps: List<LaunchableApp>,
    fromRight: Boolean,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    onAddApps: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dismissPx = with(LocalDensity.current) { DISMISS_DRAG.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .width(212.dp)
            .heightIn(max = maxHeight)
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
        if (apps.isEmpty()) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("No apps yet", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onAddApps) { Text("Add apps") }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.padding(vertical = 16.dp, horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(apps, key = { it.key.encode() }) { app -> AppCell(app, onLaunch) }
            }
        }
    }
}

@Composable
private fun AppCell(app: LaunchableApp, onLaunch: (LaunchableApp, Rect?) -> Unit) {
    val density = LocalDensity.current
    val sizePx = with(density) { 52.dp.roundToPx() }
    val bitmap = remember(app.key, sizePx) { app.icon?.toBitmap(sizePx, sizePx)?.asImageBitmap() }
    val position = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable {
                val b = position[0]?.takeIf { it.isAttached }?.boundsInWindow()
                onLaunch(app, b?.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) })
            }
            .padding(4.dp),
    ) {
        Box(Modifier.size(52.dp).onGloballyPositioned { position[0] = it }) {
            bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
        Text(
            app.label,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Vertically centres the child on [centerYPx] (window coordinates), kept fully on screen. */
private fun Modifier.centerOn(centerYPx: Int) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val parentHeight = constraints.maxHeight
    val margin = 32.dp.roundToPx()
    val maxTop = (parentHeight - placeable.height - margin).coerceAtLeast(margin)
    val top = (centerYPx - placeable.height / 2).coerceIn(margin, maxTop)
    layout(placeable.width, parentHeight) { placeable.place(0, top) }
}

@Composable
private fun DrawerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
