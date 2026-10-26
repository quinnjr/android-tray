package dev.quinnjr.android_drawer.overlay

import dev.quinnjr.android_drawer.settings.Side
import kotlin.math.abs
import kotlin.math.hypot

sealed interface GestureResult {
    data object None : GestureResult
    data object Open : GestureResult
    data class Move(val rawX: Float, val rawY: Float) : GestureResult
    data class Drop(val side: Side, val rawY: Float) : GestureResult
}

/**
 * Touch state machine for the edge handle, in raw screen coordinates.
 * An inward swipe opens the drawer; holding still for [longPressMs] enters drag mode,
 * where the handle follows the finger and is dropped onto whichever half it is released over.
 * A plain tap does nothing.
 */
class HandleGesture(
    val side: Side,
    val touchSlopPx: Float,
    val openDistancePx: Float,
    val longPressMs: Long,
    val screenWidth: Int,
) {
    private var active = false
    private var dragging = false
    private var movedPastSlop = false
    private var opened = false
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    fun down(x: Float, y: Float, t: Long) {
        active = true
        dragging = false
        movedPastSlop = false
        opened = false
        downX = x
        downY = y
        downT = t
    }

    /** Returns true at the moment drag mode starts. */
    fun tick(t: Long): Boolean {
        if (!active || dragging || movedPastSlop || opened) return false
        if (t - downT < longPressMs) return false
        dragging = true
        return true
    }

    fun move(x: Float, y: Float, t: Long): GestureResult {
        if (!active) return GestureResult.None
        if (dragging || tick(t)) return GestureResult.Move(x, y)

        val dx = x - downX
        val dy = y - downY
        if (hypot(dx, dy) > touchSlopPx) movedPastSlop = true
        if (opened) return GestureResult.None

        val inward = if (side == Side.RIGHT) -dx else dx
        if (inward >= openDistancePx && abs(dx) > abs(dy)) {
            opened = true
            return GestureResult.Open
        }
        return GestureResult.None
    }

    fun up(x: Float, y: Float, t: Long): GestureResult {
        val wasDragging = active && dragging
        cancel()
        if (!wasDragging) return GestureResult.None
        return GestureResult.Drop(if (x < screenWidth / 2f) Side.LEFT else Side.RIGHT, y)
    }

    fun cancel() {
        active = false
        dragging = false
    }
}
