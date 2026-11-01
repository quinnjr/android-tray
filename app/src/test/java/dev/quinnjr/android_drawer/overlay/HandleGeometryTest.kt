package dev.quinnjr.android_drawer.overlay

import dev.quinnjr.android_drawer.settings.Side
import org.junit.Assert.assertEquals
import org.junit.Test

class HandleGeometryTest {
    @Test
    fun `handleTop clamps into screen`() {
        assertEquals(2000 - 300, handleTop(1.0f, 2000, 300))
        assertEquals(2000 - 300, handleTop(1.5f, 2000, 300))
        assertEquals(0, handleTop(-0.2f, 2000, 300))
        assertEquals(850, handleTop(0.5f, 2000, 300))
    }

    @Test
    fun `handleTop with handle taller than screen pins to top`() {
        assertEquals(0, handleTop(0.5f, 200, 300))
    }

    @Test
    fun `yFractionFor inverts handleTop`() {
        assertEquals(0.5f, yFractionFor(850, 2000, 300), 0.001f)
        assertEquals(1f, yFractionFor(5000, 2000, 300), 0.001f)
        assertEquals(0f, yFractionFor(-10, 2000, 300), 0.001f)
    }

    @Test
    fun `size clamps to exclusion limit`() {
        assertEquals(200, clampHandleHeightDp(250))
        assertEquals(60, clampHandleHeightDp(10))
        assertEquals(120, clampHandleHeightDp(120))
    }

    @Test
    fun `handleX sits inside side navigation bar insets`() {
        assertEquals(1080 - 60, handleX(Side.RIGHT, 1080, 60, insetLeft = 0, insetRight = 0))
        assertEquals(2400 - 126 - 60, handleX(Side.RIGHT, 2400, 60, insetLeft = 0, insetRight = 126))
        assertEquals(126, handleX(Side.LEFT, 2400, 60, insetLeft = 126, insetRight = 0))
        assertEquals(0, handleX(Side.LEFT, 1080, 60, insetLeft = 0, insetRight = 0))
    }

    @Test
    fun `dragged handle stays on screen and clear of side navigation bars`() {
        assertEquals(500 - 30, handleDragX(500f, 2400, 60, insetLeft = 126, insetRight = 0))
        assertEquals(126, handleDragX(10f, 2400, 60, insetLeft = 126, insetRight = 0))
        assertEquals(2400 - 126 - 60, handleDragX(2399f, 2400, 60, insetLeft = 0, insetRight = 126))
    }

    @Test
    fun `dragged handle top is centred on the finger and clamped`() {
        assertEquals(400, handleTopAt(500f, 2000, 200))
        assertEquals(0, handleTopAt(10f, 2000, 200))
        assertEquals(1800, handleTopAt(1990f, 2000, 200))
    }

    @Test
    fun `handle stays clear of the status and navigation bars`() {
        // 2000px screen, 100px status bar, 150px navigation bar, 200px handle.
        assertEquals(100, handleTop(0f, 2000, 200, insetTop = 100, insetBottom = 150))
        assertEquals(2000 - 150 - 200, handleTop(1f, 2000, 200, insetTop = 100, insetBottom = 150))
        assertEquals(100, handleTopAt(10f, 2000, 200, insetTop = 100, insetBottom = 150))
        assertEquals(2000 - 150 - 200, handleTopAt(1990f, 2000, 200, insetTop = 100, insetBottom = 150))
    }

    @Test
    fun `yFractionFor inverts handleTop with insets`() {
        val top = handleTop(0.4f, 2000, 200, insetTop = 100, insetBottom = 150)
        assertEquals(0.4f, yFractionFor(top, 2000, 200, insetTop = 100, insetBottom = 150), 0.001f)
    }
}
