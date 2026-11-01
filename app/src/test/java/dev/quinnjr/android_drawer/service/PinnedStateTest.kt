package dev.quinnjr.android_drawer.service

import dev.quinnjr.android_drawer.overlay.PinnedStatus
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

    private fun load(vararg keys: AppKey, failed: Boolean = false, retry: Int = 0) =
        LoadedPinned(keys.toSet(), if (failed) null else keys.associateWith { it.component }, retry)

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
        assertEquals(good!!.load.apps, failed.load.apps)
        assertTrue(failed.failed)
        assertFalse(failed.reloading)
    }

    @Test
    fun `the saved order is applied after keeping, so a kept load shows the current order and set`() {
        val kept = null.keep(load(a, b, c)).keep(load(c, a, failed = true))!!
        assertEquals(listOf("c/c.C", "a/a.A"), kept.load.ordered(listOf(c, a)))
    }

    @Test
    fun `status is current for a load of exactly these keys`() {
        assertEquals(PinnedStatus.CURRENT, null.keep(load(a, b))!!.status(setOf(a, b), retryRequested = 0))
    }

    @Test
    fun `a load for a different set means its reload is on the way`() {
        assertEquals(PinnedStatus.RELOADING, null.keep(load(a, b))!!.status(setOf(a, b, c), retryRequested = 0))
    }

    @Test
    fun `a failure shows as reloading once a retry has been asked for`() {
        val failed = null.keep(load(a, failed = true))!!
        assertEquals(PinnedStatus.FAILED, failed.status(setOf(a), retryRequested = 0))
        assertEquals(PinnedStatus.RELOADING, failed.status(setOf(a), retryRequested = 1))
    }

    @Test
    fun `a load waits until the last app change has settled, whatever else triggers it`() {
        val inputs = LoadInputs(setOf(a), changedAt = 1_000L, trigger = null)
        assertEquals(300L, inputs.settleDelay(now = 1_200L, settleMs = 500L))
        assertEquals(0L, inputs.settleDelay(now = 2_000L, settleMs = 500L))
        assertEquals(0L, LoadInputs(setOf(a), changedAt = 0L, trigger = null).settleDelay(now = 100L, settleMs = 500L))
    }

    @Test
    fun `a failed retry over a kept load counts as that retry, so it shows as failed`() {
        val kept = null.keep(load(a, b)).keep(load(a, b, failed = true))
        val retriedAndFailed = kept.keep(null).keep(load(a, b, failed = true, retry = 1))!!
        assertEquals(PinnedStatus.FAILED, retriedAndFailed.status(setOf(a, b), retryRequested = 1))
    }

    @Test
    fun `a failure kept through a change of the pinned set drops the apps unpinned since`() {
        val kept = null.keep(load(a, b, c)).keep(load(a, failed = true))!!
        assertEquals(setOf(a), kept.load.apps!!.keys)
    }
}
