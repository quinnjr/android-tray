package dev.quinnjr.android_drawer.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrangedByTest {
    @Test
    fun `items follow the order, missing ones dropped, others appended in their own order`() {
        val items = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), items.arrangedBy(listOf("c", "x", "a")) { it })
    }
}
