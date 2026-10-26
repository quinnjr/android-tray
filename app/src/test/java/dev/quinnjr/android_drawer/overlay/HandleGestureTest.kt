package dev.quinnjr.android_drawer.overlay

import dev.quinnjr.android_drawer.settings.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandleGestureTest {
    private fun gesture(side: Side) = HandleGesture(
        side = side,
        touchSlopPx = 16f,
        openDistancePx = 60f,
        longPressMs = 400L,
        screenWidth = 1080,
    )

    @Test
    fun `inward swipe on right handle opens`() {
        val g = gesture(Side.RIGHT)
        g.down(1070f, 500f, 0L)
        assertEquals(GestureResult.Open, g.move(1000f, 510f, 50L))
    }

    @Test
    fun `inward swipe on left handle opens`() {
        val g = gesture(Side.LEFT)
        g.down(10f, 500f, 0L)
        assertEquals(GestureResult.Open, g.move(80f, 505f, 50L))
    }

    @Test
    fun `open fires only once per gesture`() {
        val g = gesture(Side.RIGHT)
        g.down(1070f, 500f, 0L)
        assertEquals(GestureResult.Open, g.move(1000f, 500f, 50L))
        assertEquals(GestureResult.None, g.move(900f, 500f, 60L))
        assertEquals(GestureResult.None, g.up(900f, 500f, 70L))
    }

    @Test
    fun `outward swipe does nothing`() {
        val g = gesture(Side.LEFT)
        g.down(30f, 500f, 0L)
        assertEquals(GestureResult.None, g.move(-50f, 500f, 50L))
        assertEquals(GestureResult.None, g.up(-50f, 500f, 60L))
    }

    @Test
    fun `tap does nothing`() {
        val g = gesture(Side.RIGHT)
        g.down(1070f, 500f, 0L)
        assertEquals(GestureResult.None, g.up(1070f, 500f, 80L))
    }

    @Test
    fun `vertical swipe does nothing`() {
        val g = gesture(Side.RIGHT)
        g.down(1070f, 500f, 0L)
        assertEquals(GestureResult.None, g.move(1000f, 650f, 50L))
        assertEquals(GestureResult.None, g.up(1000f, 650f, 60L))
    }

    @Test
    fun `long press then drag moves and drops on other side`() {
        val g = gesture(Side.RIGHT)
        g.down(1070f, 500f, 0L)
        assertFalse(g.tick(399L))
        assertTrue(g.tick(400L))
        assertEquals(GestureResult.Move(800f, 700f), g.move(800f, 700f, 500L))
        assertEquals(GestureResult.Drop(Side.LEFT, 900f), g.up(300f, 900f, 600L))
    }

    @Test
    fun `long press drag dropped on same half keeps side`() {
        val g = gesture(Side.RIGHT)
        g.down(1070f, 500f, 0L)
        assertTrue(g.tick(450L))
        assertEquals(GestureResult.Drop(Side.RIGHT, 300f), g.up(1000f, 300f, 600L))
    }

    @Test
    fun `long press detected on late move without tick`() {
        val g = gesture(Side.RIGHT)
        g.down(1070f, 500f, 0L)
        assertEquals(GestureResult.Move(1070f, 600f), g.move(1070f, 600f, 500L))
    }

    @Test
    fun `movement before long press cancels long press`() {
        val g = gesture(Side.RIGHT)
        g.down(1070f, 500f, 0L)
        g.move(1070f, 540f, 100L)
        assertFalse(g.tick(500L))
    }

    @Test
    fun `cancel resets drag`() {
        val g = gesture(Side.RIGHT)
        g.down(1070f, 500f, 0L)
        assertTrue(g.tick(400L))
        g.cancel()
        assertEquals(GestureResult.None, g.up(300f, 900f, 600L))
    }
}
