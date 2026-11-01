package dev.quinnjr.android_drawer.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.State
import androidx.compose.ui.platform.ComposeView
import dev.quinnjr.android_drawer.TAG
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.settings.AppKey
import dev.quinnjr.android_drawer.settings.DrawerSettings
import dev.quinnjr.android_drawer.settings.Side

private const val HANDLE_TOUCH_WIDTH_DP = 24
private val DEFAULT_SETTINGS = DrawerSettings()

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
    private var drawerClosed: (() -> Unit)? = null
    private var drawerParams: WindowManager.LayoutParams? = null
    /** Where the finger grabbed the handle, relative to its top-left corner. */
    private var grabOffset = 0f to 0f
    /** Screen size and system-bar insets for the drag in progress. */
    private var dragBounds: Pair<Size, Rect>? = null
    private var fullscreen = false
    private val watcher = FullscreenWatcher(context, ::add, ::remove, ::setFullscreen)
    private var watching = false

    val handleWidthPx get() = (HANDLE_TOUCH_WIDTH_DP * density).toInt()
    val handleHeightPx get() = (clampHandleHeightDp(settings?.handleHeightDp ?: DEFAULT_SETTINGS.handleHeightDp) * density).toInt()
    /** Open and not closing: during the exit animation the handle is back and can reopen it. */
    val isDrawerOpen get() = drawer != null && drawerState?.targetState == true

    fun showHandle(s: DrawerSettings) {
        settings = s
        val view = handle ?: HandleView(
            context,
            onOpen = onOpen,
            onPickUp = ::pickUpHandle,
            onDragMove = ::dragHandleTo,
            onDrop = ::dropHandle,
        ).also {
            handle = it
            // Before Android 11 the navigation-bar size comes from this window's insets, which
            // only arrive once it is attached: place the handle again when they do.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                it.setOnApplyWindowInsetsListener { v, insets -> replaceForInsets(); v.onApplyWindowInsets(insets) }
            }
        }
        val screen = screenSize()
        view.configure(s.side, s.handleColor, s.handleAlpha, screen.width)
        // While the handle is being dragged it follows the finger; a settings write or rotation
        // landing meanwhile must not snap it back to the stored spot.
        if (!view.isDragging) { placeHandle(s, screen) }
        // One window update for position, look and visibility together.
        applyHandleVisibility(updateWindow = false)
        if (view in added) { update(view, handleParams) } else { add(view, handleParams) }
        if (!watching) {
            watching = true
            watcher.start()
        }
    }

    /** Re-applies the stored position, e.g. after rotation. */
    fun relayout() {
        dragBounds = null
        settings?.let(::showHandle)
    }

    fun setFullscreen(value: Boolean) {
        if (fullscreen == value) { return }
        fullscreen = value
        applyHandleVisibility()
    }

    /** Shows the drawer; false if it couldn't be (no settings yet, or one is already open). */
    fun openDrawer(
        /** The pinned apps; null while they're still loading (the drawer shows meanwhile). */
        apps: State<List<LaunchableApp>?>,
        /** How current the apps shown are (shown as a hint; launching from them is still fine). */
        status: State<PinnedStatus>,
        hasPinned: State<Boolean>,
        /** A short message to show in the drawer (e.g. an app that couldn't be started), or null. */
        notice: State<String?>,
        recent: State<List<LaunchableApp>?>,
        expectRecents: Boolean,
        /** Starts an app; false if it couldn't be (the drawer then stays open). */
        onLaunch: (LaunchableApp, Rect?) -> Boolean,
        onReorder: (List<AppKey>) -> Unit,
        onOpenSettings: () -> Unit,
        /** Called once the drawer has been removed, or straight away if it couldn't be shown. */
        onClosed: () -> Unit,
        onRecentsNeeded: () -> Unit,
    ): Boolean {
        // Not shown: onClosed still runs, so the caller's cleanup is in one place.
        val s = settings ?: run {
            onClosed()
            return false
        }
        if (drawer != null) {
            if (isDrawerOpen) {
                onClosed()
                return false
            }
            // Swiped again while it was closing: replace it rather than wait for the animation.
            removeDrawer()
        }
        val state = MutableTransitionState(false).apply { targetState = true }
        val owner = OverlayLifecycleOwner()
        val root = DrawerWindowView(context, onBack = ::closeDrawer)
        val compose = ComposeView(context)
        owner.attachTo(root)
        root.addView(compose, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        compose.setContent {
            DrawerPanel(
                apps = apps,
                status = status,
                recent = recent,
                hasPinned = hasPinned,
                notice = notice,
                expectRecents = expectRecents,
                side = s.side,
                visible = state,
                iconsLocked = s.iconsLocked,
                // Cells stay clickable during the exit animation; ignore taps once closing.
                onLaunch = { app, bounds ->
                    // Closes only once an app actually started: tapping one gone meanwhile (e.g.
                    // uninstalled while the list reloaded) leaves the drawer open.
                    if (state.targetState && onLaunch(app, bounds)) {
                        closeDrawer()
                    }
                },
                onReorder = onReorder,
                onOpenSettings = {
                    if (state.targetState) {
                        onOpenSettings()
                        closeDrawer()
                    }
                },
                onRecentsNeeded = onRecentsNeeded,
                onDismiss = ::closeDrawer,
                // Leave the composition before tearing down its window.
                onHidden = { root.post { if (drawer === root) { removeDrawer() } } },
            )
        }
        drawer = root
        drawerClosed = onClosed
        drawerOwner = owner
        drawerState = state
        applyHandleVisibility()
        val params = overlayParams(0).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        drawerParams = params
        add(root, params)
        if (root !in added) {
            // The window couldn't be added: undo the rest, so the handle comes back and the drawer
            // can be opened again.
            removeDrawer()
            return false
        }
        return true
    }

    /** Starts the exit animation; the window is removed when it finishes. */
    fun closeDrawer() {
        val state = drawerState ?: return
        // Back, Home, focus loss (which the changes below themselves cause) can all ask again.
        if (!state.targetState) {
            return
        }
        state.targetState = false
        // The handle comes back as the drawer starts to leave, so it can be swiped again at once;
        // the leaving drawer (above it) stops taking touches and focus so the swipe reaches it.
        val view = drawer
        val params = drawerParams
        if (view != null && params != null) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            // Android 12+ blocks touches through another app's overlay above this opacity, even an
            // untouchable one, so taps on the app behind would be lost while the drawer leaves. The
            // drawer stays fully opaque while open; the step down comes as its fade-out begins.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.alpha = context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
            }
            update(view, params)
        }
        applyHandleVisibility()
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
        // displayMetrics leaves out the navigation bar; the overlay is laid out on the real screen.
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        Size(dm.widthPixels, dm.heightPixels)
    }

    /** The system bars' size on each edge (status bar, navigation bar), which the handle keeps clear of. */
    private fun barInsets(): Rect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // Display cutouts too: the handle may be laid out under a notch or punch-hole otherwise.
        // Ignoring visibility: placed while an app hides the bars, it must still clear them once
        // they're back (like the stable insets used before Android 11).
        val i = wm.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        Rect(i.left, i.top, i.right, i.bottom)
    } else {
        // Before Android 11, the system bars' and cutout's size come from an attached overlay's insets.
        handle?.rootWindowInsets?.let { insets ->
            val cutout = insets.displayCutout
            @Suppress("DEPRECATION")
            Rect(
                maxOf(insets.stableInsetLeft, cutout?.safeInsetLeft ?: 0),
                maxOf(insets.stableInsetTop, cutout?.safeInsetTop ?: 0),
                maxOf(insets.stableInsetRight, cutout?.safeInsetRight ?: 0),
                maxOf(insets.stableInsetBottom, cutout?.safeInsetBottom ?: 0),
            )
        } ?: Rect()
    }

    /** Removes the drawer at once, without the exit animation (e.g. when the screen turns off). */
    fun removeDrawer() {
        val closed = drawerClosed
        drawerClosed = null
        drawer?.let(::remove)
        drawerOwner?.destroy()
        drawer = null
        drawerOwner = null
        drawerState = null
        drawerParams = null
        applyHandleVisibility()
        closed?.invoke()
    }

    private fun applyHandleVisibility(updateWindow: Boolean = true) {
        val view = handle ?: return
        val visible = !fullscreen && !isDrawerOpen
        view.visibility = if (visible) { View.VISIBLE } else { View.GONE }
        // A hidden handle must not swallow touches meant for the app underneath.
        val flags = if (visible) {
            handleParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            handleParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (flags != handleParams.flags) {
            handleParams.flags = flags
            if (updateWindow && view in added) { update(view, handleParams) }
        }
    }

    /** Re-places the handle with insets that arrived after it was added (before Android 11). */
    private fun replaceForInsets() {
        val view = handle ?: return
        val s = settings ?: return
        if (view.isDragging) { return }
        val x = handleParams.x
        val y = handleParams.y
        placeHandle(s, screenSize())
        // Only when it moved: the update itself can dispatch insets again.
        if (handleParams.x != x || handleParams.y != y) { update(view, handleParams) }
    }

    private fun placeHandle(s: DrawerSettings, screen: Size) {
        handleParams.width = handleWidthPx
        handleParams.height = handleHeightPx
        val bars = barInsets()
        handleParams.x = handleX(s.side, screen.width, handleWidthPx, bars.left, bars.right)
        handleParams.y = handleTop(s.yFraction, screen.height, handleHeightPx, bars.top, bars.bottom)
    }

    /** Remembers where on the handle the finger is, so it moves with the finger without jumping. */
    private fun pickUpHandle(rawX: Float, rawY: Float) {
        grabOffset = (rawX - handleParams.x) to (rawY - handleParams.y)
    }

    private fun dragHandleTo(rawX: Float, rawY: Float) {
        val view = handle ?: return
        // Measured once per drag: this runs for every touch move.
        val (screen, bars) = dragBounds ?: (screenSize() to barInsets()).also { dragBounds = it }
        val (grabX, grabY) = grabOffset
        handleParams.x = clampHandleLeft((rawX - grabX).toInt(), screen.width, handleWidthPx, bars.left, bars.right)
        handleParams.y = clampHandleTop((rawY - grabY).toInt(), screen.height, handleHeightPx, bars.top, bars.bottom)
        update(view, handleParams)
    }

    private fun dropHandle(side: Side, rawY: Float) {
        dragBounds = null
        val s = settings ?: return
        val screen = screenSize()
        val bars = barInsets()
        // Where the finger holds it, as while dragging: released without moving, it stays put.
        val top = clampHandleTop((rawY - grabOffset.second).toInt(), screen.height, handleHeightPx, bars.top, bars.bottom)
        val fraction = yFractionFor(top, screen.height, handleHeightPx, bars.top, bars.bottom)
        // Snap immediately; the settings flow will confirm with the persisted values.
        showHandle(s.copy(side = side, yFraction = fraction))
        onHandleDropped(side, fraction)
    }

    private fun add(view: View, params: WindowManager.LayoutParams) = guard {
        wm.addView(view, params)
        added += view
    }

    private fun update(view: View, params: WindowManager.LayoutParams) = guard {
        if (view in added) { wm.updateViewLayout(view, params) }
    }

    private fun remove(view: View) = guard {
        if (added.remove(view)) { wm.removeViewImmediate(view) }
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
