package dev.quinnjr.android_drawer.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.platform.ComposeView
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.settings.DrawerSettings
import dev.quinnjr.android_drawer.settings.Side

private const val TAG = "EdgeDrawer"
private const val HANDLE_TOUCH_WIDTH_DP = 24

/**
 * Owns the overlay windows. Any window-manager failure (usually the overlay permission
 * being revoked) is reported through [onFatal] instead of crashing.
 */
class OverlayHost(
    private val context: Context,
    private val onOpen: () -> Unit,
    private val onHandleDropped: (side: Side, yFraction: Float) -> Unit,
    private val onFatal: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density get() = context.resources.displayMetrics.density
    private val added = mutableSetOf<View>()

    private var settings: DrawerSettings? = null
    private var handle: HandleView? = null
    private val handleParams = overlayParams(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        .apply { gravity = Gravity.TOP or Gravity.START }

    private var drawer: DrawerWindowView? = null
    private var drawerOwner: OverlayLifecycleOwner? = null
    private var drawerState: MutableTransitionState<Boolean>? = null
    private var fullscreen = false
    private val watcher = FullscreenWatcher(context, ::add, ::remove, ::setFullscreen)
    private var watching = false

    val handleWidthPx get() = (HANDLE_TOUCH_WIDTH_DP * density).toInt()
    val handleHeightPx get() = (clampHandleHeightDp(settings?.handleHeightDp ?: 120) * density).toInt()
    val isDrawerOpen get() = drawer != null

    fun showHandle(s: DrawerSettings) {
        settings = s
        val view = handle ?: HandleView(
            context,
            onOpen = onOpen,
            onDragMove = ::dragHandleTo,
            onDrop = ::dropHandle,
        ).also { handle = it }
        val screen = screenSize()
        view.configure(s.side, s.handleColor, s.handleAlpha, screen.width)
        placeHandle(s, screen)
        applyHandleVisibility()
        if (view in added) update(view, handleParams) else add(view, handleParams)
        if (!watching) {
            watching = true
            watcher.start()
        }
    }

    /** Re-applies the stored position, e.g. after rotation. */
    fun relayout() {
        settings?.let(::showHandle)
    }

    fun setFullscreen(value: Boolean) {
        if (fullscreen == value) return
        fullscreen = value
        applyHandleVisibility()
    }

    fun openDrawer(
        apps: List<LaunchableApp>,
        onLaunch: (LaunchableApp, Rect?) -> Unit,
        onAddApps: () -> Unit,
    ) {
        val s = settings ?: return
        if (drawer != null) return
        val state = MutableTransitionState(false).apply { targetState = true }
        val owner = OverlayLifecycleOwner()
        val root = DrawerWindowView(context, onBack = ::closeDrawer)
        val compose = ComposeView(context)
        owner.attachTo(root)
        root.addView(compose, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val centerY = handleTop(s.yFraction, screenSize().height, handleHeightPx) + handleHeightPx / 2
        compose.setContent {
            DrawerPanel(
                apps = apps,
                side = s.side,
                centerYPx = centerY,
                visible = state,
                // Cells stay clickable during the exit animation; ignore taps once closing.
                onLaunch = { app, bounds ->
                    if (state.targetState) {
                        onLaunch(app, bounds)
                        closeDrawer()
                    }
                },
                onAddApps = {
                    if (state.targetState) {
                        onAddApps()
                        closeDrawer()
                    }
                },
                onDismiss = ::closeDrawer,
                // Leave the composition before tearing down its window.
                onHidden = { root.post { if (drawer === root) removeDrawer() } },
            )
        }
        drawer = root
        drawerOwner = owner
        drawerState = state
        applyHandleVisibility()
        add(root, overlayParams(0).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        })
    }

    /** Starts the exit animation; the window is removed when it finishes. */
    fun closeDrawer() {
        drawerState?.targetState = false
    }

    fun removeAll() {
        removeDrawer()
        watcher.stop()
        watching = false
        handle?.let(::remove)
        handle = null
    }

    fun screenSize(): Size = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val b = wm.currentWindowMetrics.bounds
        Size(b.width(), b.height())
    } else {
        val dm = context.resources.displayMetrics
        Size(dm.widthPixels, dm.heightPixels)
    }

    private fun navBarInsets(): Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val i = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.navigationBars())
        Rect(i.left, i.top, i.right, i.bottom)
    } else {
        Rect()
    }

    private fun removeDrawer() {
        drawer?.let(::remove)
        drawerOwner?.destroy()
        drawer = null
        drawerOwner = null
        drawerState = null
        applyHandleVisibility()
    }

    private fun applyHandleVisibility() {
        val view = handle ?: return
        val visible = !fullscreen && drawer == null
        view.visibility = if (visible) View.VISIBLE else View.GONE
        // A hidden handle must not swallow touches meant for the app underneath.
        val flags = if (visible) {
            handleParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            handleParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (flags != handleParams.flags) {
            handleParams.flags = flags
            if (view in added) update(view, handleParams)
        }
    }

    private fun placeHandle(s: DrawerSettings, screen: Size) {
        handleParams.width = handleWidthPx
        handleParams.height = handleHeightPx
        val nav = navBarInsets()
        handleParams.x = handleX(s.side, screen.width, handleWidthPx, nav.left, nav.right)
        handleParams.y = handleTop(s.yFraction, screen.height, handleHeightPx)
    }

    private fun dragHandleTo(rawX: Float, rawY: Float) {
        val view = handle ?: return
        val screen = screenSize()
        handleParams.x = (rawX - handleWidthPx / 2f).toInt().coerceIn(0, screen.width - handleWidthPx)
        handleParams.y = (rawY - handleHeightPx / 2f).toInt().coerceIn(0, (screen.height - handleHeightPx).coerceAtLeast(0))
        update(view, handleParams)
    }

    private fun dropHandle(side: Side, rawY: Float) {
        val s = settings ?: return
        val screen = screenSize()
        val top = (rawY - handleHeightPx / 2f).toInt()
        val fraction = yFractionFor(top, screen.height, handleHeightPx)
        // Snap immediately; the settings flow will confirm with the persisted values.
        showHandle(s.copy(side = side, yFraction = fraction))
        onHandleDropped(side, fraction)
    }

    private fun add(view: View, params: WindowManager.LayoutParams) = guard {
        wm.addView(view, params)
        added += view
    }

    private fun update(view: View, params: WindowManager.LayoutParams) = guard {
        if (view in added) wm.updateViewLayout(view, params)
    }

    private fun remove(view: View) = guard {
        if (added.remove(view)) wm.removeViewImmediate(view)
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Overlay window rejected", e)
            onFatal()
        } catch (e: SecurityException) {
            Log.e(TAG, "Overlay permission missing", e)
            onFatal()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Overlay window in a bad state", e)
            onFatal()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Overlay window not attached", e)
            onFatal()
        }
    }
}

internal fun overlayParams(flags: Int) = WindowManager.LayoutParams(
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT,
).apply {
    layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else {
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}
