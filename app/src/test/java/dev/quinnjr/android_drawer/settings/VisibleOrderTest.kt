package dev.quinnjr.android_drawer.settings

import dev.quinnjr.android_drawer.ui.moved
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleOrderTest {
    private val a = AppKey("a/a.A", 0L)
    private val b = AppKey("b/b.B", 0L)
    private val c = AppKey("c/c.C", 0L)
    private val hidden = AppKey("h/h.H", 10L)

    @Test
    fun `new order replaces old when every app is shown`() {
        assertEquals(listOf(c, a, b), listOf(a, b, c).withVisibleOrder(listOf(c, a, b)))
    }

    @Test
    fun `apps the drawer could not show keep their place`() {
        assertEquals(listOf(c, hidden, a, b), listOf(a, hidden, b, c).withVisibleOrder(listOf(c, a, b)))
    }

    @Test
    fun `moving a shown app past a hidden one moves the right app`() {
        // Settings shows [a, b]; "move a down" must not swap the hidden app with a.
        val shown = listOf(a, b)
        assertEquals(listOf(hidden, b, a), listOf(hidden, a, b).withVisibleOrder(shown.moved(0, 1)))
    }

    @Test
    fun `apps unpinned meanwhile are not brought back`() {
        assertEquals(listOf(b, a), listOf(a, b).withVisibleOrder(listOf(c, b, a)))
    }

    @Test
    fun `apps pinned meanwhile are kept`() {
        assertEquals(listOf(b, a, c), listOf(a, b, c).withVisibleOrder(listOf(b, a)))
    }
}
