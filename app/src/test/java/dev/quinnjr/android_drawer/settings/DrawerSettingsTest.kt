package dev.quinnjr.android_drawer.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrawerSettingsTest {
    @Test
    fun `AppKey round-trips`() {
        val key = AppKey("com.example/com.example.Main", 10L)
        assertEquals(key, AppKey.decode(key.encode()))
    }

    @Test
    fun `AppKey decode rejects garbage`() {
        assertNull(AppKey.decode(""))
        assertNull(AppKey.decode("abc"))
        assertNull(AppKey.decode("x|"))
        assertNull(AppKey.decode("0|"))
        assertNull(AppKey.decode("0|nocomponent"))
    }

    @Test
    fun `apps list encoding preserves order and skips malformed lines`() {
        val a = AppKey("a.pkg/a.pkg.A", 0L)
        val b = AppKey("b.pkg/b.pkg.B", 10L)
        val encoded = encodeApps(listOf(b, a))
        assertEquals(listOf(b, a), decodeApps(encoded))
        assertEquals(listOf(b, a), decodeApps("${b.encode()}\ngarbage\n\n${a.encode()}"))
        assertEquals(emptyList<AppKey>(), decodeApps(""))
    }

    @Test
    fun `apps list decoding drops duplicates`() {
        val a = AppKey("a.pkg/a.pkg.A", 0L)
        assertEquals(listOf(a), decodeApps("${a.encode()}\n${a.encode()}"))
    }
}
