package dev.quinnjr.android_drawer.overlay

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
}
