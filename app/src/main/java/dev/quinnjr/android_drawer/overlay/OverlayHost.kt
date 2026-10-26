package dev.quinnjr.android_drawer.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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

    private var settings: DrawerSettings? = null
    private var handle: HandleView? = null
    private val handleParams = overlayParams(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    val handleWidthPx get() = (HANDLE_TOUCH_WIDTH_DP * density).toInt()
    val handleHeightPx get() = ((settings?.handleHeightDp ?: 120) * density).toInt()

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
        if (view.isAttachedToWindow) update(view, handleParams) else add(view, handleParams)
    }

    /** Re-applies the stored position, e.g. after rotation. */
    fun relayout() {
        settings?.let(::showHandle)
    }

    fun removeAll() {
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

    private fun placeHandle(s: DrawerSettings, screen: Size) {
        handleParams.width = handleWidthPx
        handleParams.height = handleHeightPx
        handleParams.x = if (s.side == Side.LEFT) 0 else screen.width - handleWidthPx
        handleParams.y = handleTop(s.yFraction, screen.height, handleHeightPx)
    }

    private fun dragHandleTo(rawX: Float, rawY: Float) {
        val view = handle ?: return
        val screen = screenSize()
        handleParams.x = (rawX - handleWidthPx / 2f).toInt().coerceIn(0, screen.width - handleWidthPx)
        handleParams.y = (rawY - handleHeightPx / 2f).toInt().coerceIn(0, screen.height - handleHeightPx)
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

    internal fun add(view: View, params: WindowManager.LayoutParams) = guard { wm.addView(view, params) }

    internal fun update(view: View, params: WindowManager.LayoutParams) = guard {
        if (view.isAttachedToWindow) wm.updateViewLayout(view, params)
    }

    internal fun remove(view: View) = guard {
        if (view.isAttachedToWindow) wm.removeViewImmediate(view)
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
