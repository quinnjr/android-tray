package dev.quinnjr.android_drawer.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.quinnjr.android_drawer.settings.Side

/** Visible width of the pill drawn inside the (wider) touch window. */
private const val PILL_WIDTH_DP = 6f
private const val OPEN_DISTANCE_DP = 24f

@SuppressLint("ViewConstructor")
class HandleView(
    context: Context,
    private val onOpen: () -> Unit,
    private val onDragMove: (rawX: Float, rawY: Float) -> Unit,
    private val onDrop: (side: Side, rawY: Float) -> Unit,
) : View(context) {
    // Read live: the display size (and so density) can change while this view is reused.
    private val density get() = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = RectF()
    private var side = Side.RIGHT
    private var gesture: HandleGesture? = null
    /** A reconfigured gesture waiting for the current touch to end. */
    private var pendingGesture: HandleGesture? = null
    private var touching = false
    /** Where the finger last was; ACTION_CANCEL's own coordinates aren't reliable. */
    private var lastRawX = 0f
    private var lastRawY = 0f

    /** The gesture's own state, so there is a single source of truth. */
    val isDragging get() = gesture?.isDragging == true

    private val longPress = Runnable {
        if (gesture?.tick(SystemClock.uptimeMillis()) == true) pickedUp()
    }

    fun configure(side: Side, color: Long, alpha: Float, screenWidth: Int) {
        this.side = side
        paint.color = color.toInt()
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val openDistance = OPEN_DISTANCE_DP * density
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        val current = gesture
        // Settings writes (colour, a just-dropped position) re-run this; keep a swipe in progress.
        val latest = pendingGesture ?: current
        if (latest == null || latest.side != side || latest.touchSlopPx != touchSlop ||
            latest.openDistancePx != openDistance || latest.longPressMs != longPressMs ||
            latest.screenWidth != screenWidth
        ) {
            val next = HandleGesture(side, touchSlop, openDistance, longPressMs, screenWidth)
            // Mid-gesture (e.g. rotating while dragging the handle), swap it in once the finger lifts.
            if (touching) pendingGesture = next else gesture = next
        }
        invalidate()
    }

    // A fresh Rect only when the size changes: the system diffs against what it last received.
    @SuppressLint("DrawAllocation")
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Win the edge swipe over the system back gesture, but only within the handle itself.
        if (changed) systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
    }

    override fun onDraw(canvas: Canvas) {
        val pillWidth = PILL_WIDTH_DP * density
        val inset = 2 * density
        val left = if (side == Side.RIGHT) width - pillWidth - inset else inset
        pill.set(left, 0f, left + pillWidth, height.toFloat())
        canvas.drawRoundRect(pill, pillWidth / 2, pillWidth / 2, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A new touch always starts on the latest configuration, even if the last one never ended.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) endTouch()
        val g = gesture ?: return false
        // Uptime, like postDelayed: the long-press runnable then never fires "early" for tick().
        val now = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                lastRawX = event.rawX
                lastRawY = event.rawY
                g.down(event.rawX, event.rawY, now)
                postDelayed(longPress, g.longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
                val wasDragging = g.isDragging
                when (val r = g.move(event.rawX, event.rawY, now)) {
                    GestureResult.Open -> {
                        removeCallbacks(longPress)
                        onOpen()
                    }
                    is GestureResult.Move -> {
                        // Picked up by this move (the long-press came due before it).
                        if (!wasDragging) pickedUp()
                        onDragMove(r.rawX, r.rawY)
                    }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val r = g.up(event.rawX, event.rawY, now)
                // If the screen changed mid-drag (rotation), judge the side on the new width.
                val drop = (r as? GestureResult.Drop)?.let { pendingGesture?.sideAt(event.rawX) ?: it.side }
                endTouch()
                if (drop != null) onDrop(drop, event.rawY)
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val wasDragging = g.isDragging
                // Drop where the finger was (on the current screen width), like a normal release.
                val dropSide = (pendingGesture ?: g).sideAt(lastRawX)
                g.cancel()
                endTouch()
                if (wasDragging) onDrop(dropSide, lastRawY)
            }
        }
        return true
    }

    private fun endTouch() {
        touching = false
        pendingGesture?.let { gesture = it }
        pendingGesture = null
    }

    private fun pickedUp() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
