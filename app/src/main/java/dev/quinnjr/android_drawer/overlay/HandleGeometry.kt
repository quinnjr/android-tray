package dev.quinnjr.android_drawer.overlay

import dev.quinnjr.android_drawer.settings.Side

/** Android caps system-gesture exclusion at 200dp per edge, so the handle may not be taller. */
const val MAX_HANDLE_HEIGHT_DP = 200
const val MIN_HANDLE_HEIGHT_DP = 60

fun clampHandleHeightDp(dp: Int): Int = dp.coerceIn(MIN_HANDLE_HEIGHT_DP, MAX_HANDLE_HEIGHT_DP)

fun handleTop(yFraction: Float, screenHeight: Int, handleHeight: Int): Int {
    val range = (screenHeight - handleHeight).coerceAtLeast(0)
    return (yFraction * range).toInt().coerceIn(0, range)
}

fun yFractionFor(top: Int, screenHeight: Int, handleHeight: Int): Float {
    val range = screenHeight - handleHeight
    if (range <= 0) return 0f
    return (top.toFloat() / range).coerceIn(0f, 1f)
}

/** Left edge of the handle window, kept clear of a side navigation bar (landscape, 3-button nav). */
fun handleX(side: Side, screenWidth: Int, handleWidth: Int, insetLeft: Int, insetRight: Int): Int =
    if (side == Side.LEFT) insetLeft else screenWidth - insetRight - handleWidth
