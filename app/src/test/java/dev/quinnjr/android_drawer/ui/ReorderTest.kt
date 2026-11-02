package dev.quinnjr.android_drawer.ui

import dev.quinnjr.android_drawer.settings.AppKey
import dev.quinnjr.android_drawer.settings.moved
import org.junit.Assert.assertEquals
import org.junit.Test

class ReorderTest {
    @Test
    fun `moved shifts element`() {
        assertEquals(listOf("b", "c", "a"), listOf("a", "b", "c").moved(0, 2))
        assertEquals(listOf("c", "a", "b"), listOf("a", "b", "c").moved(2, 0))
    }

    @Test
    fun `moved out of range returns same list`() {
        val list = listOf("a", "b")
        assertEquals(list, list.moved(0, 5))
        assertEquals(list, list.moved(-1, 0))
    }

    @Test
    fun `toggled adds then removes`() {
        val k = AppKey("a/a.A", 0L)
        val added = emptyList<AppKey>().toggled(k)
        assertEquals(listOf(k), added)
        assertEquals(emptyList<AppKey>(), added.toggled(k))
    }
}
