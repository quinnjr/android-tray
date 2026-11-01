package dev.quinnjr.android_drawer.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconCacheTest {
    @Test
    fun `stores and returns values`() {
        val cache = IconCache<String, String>()
        cache.put("a", "A", cache.generation())
        assertEquals("A", cache["a"])
    }

    @Test
    fun `a value drawn before a forget is not stored after it`() {
        val cache = IconCache<String, String>()
        val generation = cache.generation()
        cache.forget { it == "a" }
        cache.put("a", "stale", generation)
        assertNull(cache["a"])
    }

    @Test
    fun `forget drops only matching keys`() {
        val cache = IconCache<String, String>()
        cache.put("a/1", "A", cache.generation())
        cache.put("b/1", "B", cache.generation())
        cache.forget { it.startsWith("a/") }
        assertNull(cache["a/1"])
        assertEquals("B", cache["b/1"])
    }

    @Test
    fun `evicts the least recently used entry beyond the limit`() {
        val cache = IconCache<String, String>(maxSize = 2)
        cache.put("a", "A", cache.generation())
        cache.put("b", "B", cache.generation())
        cache["a"] // a is now more recent than b
        cache.put("c", "C", cache.generation())
        assertNull(cache["b"])
        assertEquals("A", cache["a"])
        assertEquals("C", cache["c"])
    }

    @Test
    fun `forgetting one key doesn't reject values for other keys drawn meanwhile`() {
        val cache = IconCache<String, String>()
        val generation = cache.generation()
        cache.forget { it == "a" }
        cache.put("b", "B", generation)
        assertEquals("B", cache["b"])
    }

    @Test
    fun `a value drawn before a clear is not stored after it`() {
        val cache = IconCache<String, String>()
        val generation = cache.generation()
        cache.clear()
        cache.put("b", "stale", generation)
        assertNull(cache["b"])
    }
}
