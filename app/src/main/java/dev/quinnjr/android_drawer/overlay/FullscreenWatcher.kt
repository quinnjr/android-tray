package dev.quinnjr.android_drawer.overlay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.os.Build

/**
 * Detects when the foreground app hides the status bar (videos, games) using an invisible,
 * untouchable, full-height overlay: its insets report whether the system bars are showing.
 */
class FullscreenWatcher(
    context: Context,
    private val add: (View, WindowManager.LayoutParams) -> Unit,
    private val remove: (View) -> Unit,
    private val onChange: (fullscreen: Boolean) -> Unit,
) {
    private val view = View(context)
    private var last: Boolean? = null

    init {
        view.setOnApplyWindowInsetsListener { v, insets ->
            val fullscreen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                !insets.isVisible(WindowInsets.Type.statusBars())
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop == 0
            }
            if (fullscreen != last) {
                last = fullscreen
                onChange(fullscreen)
            }
            v.onApplyWindowInsets(insets)
        }
    }

    fun start() {
        add(view, overlayParams(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        ).apply {
            // Not LAYOUT_IN_SCREEN-only: we want insets, so keep the window inset-aware.
            flags = flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
            width = 1
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            // Android 12+ blocks touches passing through other apps' overlays above 0.8 opacity.
            alpha = 0f
        })
    }

    fun stop() {
        remove(view)
        last = null
    }
}
