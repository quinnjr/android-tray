package dev.quinnjr.android_drawer.apps

import dev.quinnjr.android_drawer.settings.AppKey
import org.junit.Assert.assertEquals
import org.junit.Test

class AppResolveTest {
    @Test
    fun `resolve drops missing keys and preserves order`() {
        val a = AppKey("a/a.A", 0L)
        val b = AppKey("b/b.B", 0L)
        val x = AppKey("x/x.X", 0L)
        assertEquals(listOf("B", "A"), resolveInOrder(listOf(b, x, a), mapOf(a to "A", b to "B")))
    }

    @Test
    fun `same component in another profile is a different app`() {
        val personal = AppKey("a/a.A", 0L)
        val work = AppKey("a/a.A", 10L)
        assertEquals(listOf("work"), resolveInOrder(listOf(work), mapOf(personal to "personal", work to "work")))
    }
}
