package dev.quinnjr.android_drawer.service

import dev.quinnjr.android_drawer.settings.AppKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedStateTest {
    private val a = AppKey("a/a.A", 0L)
    private val b = AppKey("b/b.B", 0L)
    private val c = AppKey("c/c.C", 0L)

    private fun load(vararg keys: AppKey, failed: Boolean = false) =
        LoadedPinned(keys.toSet(), if (failed) null else keys.associateWith { it.component })

    @Test
    fun `nothing kept before the first load`() {
        assertNull((null as KeptLoad<String>?).keep(null))
    }

    @Test
    fun `a reload keeps the last load, marked reloading`() {
        val first = null.keep(load(a, b))
        val reloading = first.keep(null)!!
        assertTrue(reloading.reloading)
        assertEquals(first!!.load, reloading.load)
    }

    @Test
    fun `a failed reload keeps the last load, marked failed and no longer reloading`() {
        val good = null.keep(load(a, b)).keep(null)
        val failed = good.keep(load(a, b, failed = true))!!
        assertEquals(good!!.load, failed.load)
        assertTrue(failed.failed)
        assertFalse(failed.reloading)
    }

    @Test
    fun `a failure with nothing to keep is shown as a failure`() {
        val failed = null.keep(load(a, failed = true))!!
        assertTrue(failed.failed)
        assertNull(failed.load.apps)
    }

    @Test
    fun `the saved order is applied after keeping, so a kept load shows the current order and set`() {
        // Loaded [a, b, c]; then b was unpinned and the rest reordered, and that reload failed.
        val kept = null.keep(load(a, b, c)).keep(load(c, a, failed = true))!!
        assertEquals(listOf("c/c.C", "a/a.A"), kept.load.ordered(listOf(c, a)))
    }

    @Test
    fun `only a change in the app-change counter waits for the burst to settle`() {
        val first = LoadInputs(setOf(a), change = 0, trigger = 0).after(null)
        assertFalse(first.settle)
        val changed = LoadInputs(setOf(a), change = 1, trigger = 0).after(first)
        assertTrue(changed.settle)
        val reordered = LoadInputs(setOf(a, b), change = 1, trigger = 0).after(changed)
        assertFalse(reordered.settle)
    }
}
