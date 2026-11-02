package dev.quinnjr.android_drawer.overlay

import dev.quinnjr.android_drawer.settings.Side
import kotlin.math.roundToInt

/** How far the handle's top can move: the screen between the top and bottom system bars. */
private fun verticalRange(screenHeight: Int, handleHeight: Int, insetTop: Int, insetBottom: Int): Int =
    (screenHeight - insetTop - insetBottom - handleHeight).coerceAtLeast(0)

/** Top edge for a stored [yFraction], kept clear of the status and navigation bars. */
fun handleTop(yFraction: Float, screenHeight: Int, handleHeight: Int, insetTop: Int = 0, insetBottom: Int = 0): Int {
    val range = verticalRange(screenHeight, handleHeight, insetTop, insetBottom)
    // Rounded, not truncated: a stored fraction must give back exactly the top it came from.
    return insetTop + (yFraction * range).roundToInt().coerceIn(0, range)
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

/** A dragged or dropped handle's [top] edge, kept clear of the system bars. */
fun clampHandleTop(top: Int, screenHeight: Int, handleHeight: Int, insetTop: Int = 0, insetBottom: Int = 0): Int =
    top.coerceIn(insetTop, insetTop + verticalRange(screenHeight, handleHeight, insetTop, insetBottom))

/** A dragged handle's [left] edge, kept clear of side navigation bars and cutouts. */
fun clampHandleLeft(left: Int, screenWidth: Int, handleWidth: Int, insetLeft: Int, insetRight: Int): Int =
    left.coerceIn(insetLeft, (screenWidth - insetRight - handleWidth).coerceAtLeast(insetLeft))
