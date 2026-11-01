package dev.quinnjr.android_drawer.service

import dev.quinnjr.android_drawer.settings.AppKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingOrderTest {
    private val a = AppKey("a/a.A", 0L)
    private val b = AppKey("b/b.B", 0L)
    private val c = AppKey("c/c.C", 0L)
    private val saved = listOf(a, b, c)
    private val o1 = listOf(c, a, b)
    private val o2 = listOf(b, c, a)

    @Test
    fun `applies while the shown order is the one it was based on, or itself`() {
        val p = PendingOrder.chosen(previous = null, shown = saved, order = o1)
        assertTrue(p.appliesTo(saved))
        assertTrue(p.appliesTo(o1))
        assertFalse(p.appliesTo(o2))
    }

    @Test
    fun `a second drag before the first is saved still applies when the first lands`() {
        val p1 = PendingOrder.chosen(previous = null, shown = saved, order = o1)
        val p2 = PendingOrder.chosen(previous = p1, shown = saved, order = o2)
        assertTrue(p2.appliesTo(saved))
        assertTrue(p2.appliesTo(o1))
        assertTrue(p2.appliesTo(o2))
    }

    @Test
    fun `once shown it no longer applies to its old base`() {
        val landed = PendingOrder.chosen(previous = null, shown = saved, order = o1).seen(o1)
        assertTrue(landed.appliesTo(o1))
        // E.g. the settings screen later moves things back.
        assertFalse(landed.appliesTo(saved))
    }

    @Test
    fun `a new drag after the last one landed starts from what is shown`() {
        val landed = PendingOrder.chosen(previous = null, shown = saved, order = o1).seen(o1)
        val next = PendingOrder.chosen(previous = landed, shown = o1, order = o2)
        assertTrue(next.appliesTo(o1))
        assertFalse(next.appliesTo(saved))
    }
}
