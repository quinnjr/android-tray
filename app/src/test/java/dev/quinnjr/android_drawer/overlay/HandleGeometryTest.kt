package dev.quinnjr.android_drawer.overlay

import dev.quinnjr.android_drawer.settings.Side
import dev.quinnjr.android_drawer.settings.clampHandleHeightDp
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
        assertEquals(470, clampHandleLeft(470, 2400, 60, insetLeft = 126, insetRight = 0))
        assertEquals(126, clampHandleLeft(-20, 2400, 60, insetLeft = 126, insetRight = 0))
        assertEquals(2400 - 126 - 60, clampHandleLeft(2369, 2400, 60, insetLeft = 0, insetRight = 126))
    }

    @Test
    fun `dragged handle top is kept on screen`() {
        assertEquals(400, clampHandleTop(400, 2000, 200))
        assertEquals(0, clampHandleTop(-90, 2000, 200))
        assertEquals(1800, clampHandleTop(1890, 2000, 200))
    }

    @Test
    fun `handle stays clear of the status and navigation bars`() {
        // 2000px screen, 100px status bar, 150px navigation bar, 200px handle.
        assertEquals(100, handleTop(0f, 2000, 200, insetTop = 100, insetBottom = 150))
        assertEquals(2000 - 150 - 200, handleTop(1f, 2000, 200, insetTop = 100, insetBottom = 150))
        assertEquals(100, clampHandleTop(-90, 2000, 200, insetTop = 100, insetBottom = 150))
        assertEquals(2000 - 150 - 200, clampHandleTop(1890, 2000, 200, insetTop = 100, insetBottom = 150))
    }

    @Test
    fun `yFractionFor inverts handleTop with insets`() {
        val top = handleTop(0.4f, 2000, 200, insetTop = 100, insetBottom = 150)
        assertEquals(0.4f, yFractionFor(top, 2000, 200, insetTop = 100, insetBottom = 150), 0.001f)
    }

    @Test
    fun `a stored position gives back exactly the same top`() {
        for (range in 1..300) {
            for (top in 0..range) {
                val fraction = yFractionFor(top, range + 100, 100)
                assertEquals("top $top, range $range", top, handleTop(fraction, range + 100, 100))
            }
        }
    }
}
