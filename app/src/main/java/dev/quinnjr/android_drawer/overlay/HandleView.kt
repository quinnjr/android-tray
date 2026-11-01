package dev.quinnjr.android_drawer.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
    private var dragging = false

    private val longPress = Runnable {
        if (gesture?.tick(System.currentTimeMillis()) == true) startDrag()
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
        if (current == null || current.side != side || current.touchSlopPx != touchSlop ||
            current.openDistancePx != openDistance || current.longPressMs != longPressMs ||
            current.screenWidth != screenWidth
        ) {
            gesture = HandleGesture(side, touchSlop, openDistance, longPressMs, screenWidth)
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
        val g = gesture ?: return false
        // Wall-clock time keeps tick() (posted runnable) and event timestamps on one clock.
        val now = System.currentTimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                g.down(event.rawX, event.rawY, now)
                postDelayed(longPress, g.longPressMs)
            }
            MotionEvent.ACTION_MOVE -> when (val r = g.move(event.rawX, event.rawY, now)) {
                GestureResult.Open -> {
                    removeCallbacks(longPress)
                    onOpen()
                }
                is GestureResult.Move -> {
                    if (!dragging) startDrag()
                    onDragMove(r.rawX, r.rawY)
                }
                else -> Unit
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val r = g.up(event.rawX, event.rawY, now)
                dragging = false
                if (r is GestureResult.Drop) onDrop(r.side, r.rawY)
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                g.cancel()
                if (dragging) onDrop(side, event.rawY)
                dragging = false
            }
        }
        return true
    }

    private fun startDrag() {
        dragging = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}
