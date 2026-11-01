package dev.quinnjr.android_drawer.overlay

import dev.quinnjr.android_drawer.settings.Side

/** Android caps system-gesture exclusion at 200dp per edge, so the handle may not be taller. */
const val MAX_HANDLE_HEIGHT_DP = 200
const val MIN_HANDLE_HEIGHT_DP = 60

fun clampHandleHeightDp(dp: Int): Int = dp.coerceIn(MIN_HANDLE_HEIGHT_DP, MAX_HANDLE_HEIGHT_DP)

/** How far the handle's top can move: the screen between the top and bottom system bars. */
private fun verticalRange(screenHeight: Int, handleHeight: Int, insetTop: Int, insetBottom: Int): Int =
    (screenHeight - insetTop - insetBottom - handleHeight).coerceAtLeast(0)

/** Top edge for a stored [yFraction], kept clear of the status and navigation bars. */
fun handleTop(yFraction: Float, screenHeight: Int, handleHeight: Int, insetTop: Int = 0, insetBottom: Int = 0): Int {
    val range = verticalRange(screenHeight, handleHeight, insetTop, insetBottom)
    return insetTop + (yFraction * range).toInt().coerceIn(0, range)
}

/** The fraction to store for a handle whose top edge is at [top]; the inverse of [handleTop]. */
fun yFractionFor(top: Int, screenHeight: Int, handleHeight: Int, insetTop: Int = 0, insetBottom: Int = 0): Float {
    val range = verticalRange(screenHeight, handleHeight, insetTop, insetBottom)
    if (range <= 0) {
        return 0f
    }
    return ((top - insetTop).toFloat() / range).coerceIn(0f, 1f)
}

/** Left edge of the handle window, kept clear of a side navigation bar (landscape, 3-button nav). */
fun handleX(side: Side, screenWidth: Int, handleWidth: Int, insetLeft: Int, insetRight: Int): Int =
    if (side == Side.LEFT) {
        insetLeft
    } else {
        screenWidth - insetRight - handleWidth
    }

/** Top edge for a handle centred on [rawY] (while dragged or dropped), clear of the system bars. */
fun handleTopAt(rawY: Float, screenHeight: Int, handleHeight: Int, insetTop: Int = 0, insetBottom: Int = 0): Int =
    (rawY - handleHeight / 2f).toInt()
        .coerceIn(insetTop, insetTop + verticalRange(screenHeight, handleHeight, insetTop, insetBottom))

/** Left edge for a handle centred on [rawX] while dragged, kept clear of side navigation bars. */
fun handleDragX(rawX: Float, screenWidth: Int, handleWidth: Int, insetLeft: Int, insetRight: Int): Int =
    (rawX - handleWidth / 2f).toInt().coerceIn(insetLeft, (screenWidth - insetRight - handleWidth).coerceAtLeast(insetLeft))
