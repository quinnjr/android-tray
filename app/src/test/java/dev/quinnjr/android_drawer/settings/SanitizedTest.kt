package dev.quinnjr.android_drawer.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SanitizedTest {
    @Test
    fun `out-of-range values are brought into range`() {
        val s = DrawerSettings(yFraction = 1.5f, handleHeightDp = 10, handleAlpha = 0f).sanitized()
        assertEquals(1f, s.yFraction)
        assertEquals(MIN_HANDLE_HEIGHT_DP, s.handleHeightDp)
        assertEquals(HANDLE_ALPHA_RANGE.start, s.handleAlpha)
    }

    @Test
    fun `NaN never makes the handle invisible or lost`() {
        val s = DrawerSettings(yFraction = Float.NaN, handleAlpha = Float.NaN).sanitized()
        assertEquals(DrawerSettings().yFraction, s.yFraction)
        assertEquals(HANDLE_ALPHA_RANGE.endInclusive, s.handleAlpha)
    }

    @Test
    fun `values in range are kept`() {
        val s = DrawerSettings(yFraction = 0.4f, handleHeightDp = 120, handleAlpha = 0.6f)
        assertEquals(s, s.sanitized())
    }
}
