package dev.quinnjr.android_drawer.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedViewTest {
    private val one = listOf("a")

    @Test
    fun `nothing loaded yet is loading`() {
        assertEquals(PinnedView.Loading, pinnedView<String>(null, PinnedStatus.RELOADING, hasPinned = true))
        assertEquals(PinnedView.Loading, pinnedView<String>(null, PinnedStatus.RELOADING, hasPinned = false))
    }

    @Test
    fun `failed with nothing loaded says so, unless none are pinned`() {
        assertEquals(PinnedView.LoadFailed, pinnedView<String>(null, PinnedStatus.FAILED, hasPinned = true))
        assertEquals(PinnedView.NoApps(hasPinned = false), pinnedView<String>(null, PinnedStatus.FAILED, hasPinned = false))
    }

    @Test
    fun `an empty list of unavailable apps keeps saying so while reloading`() {
        assertEquals(PinnedView.NoApps(hasPinned = true), pinnedView(emptyList<String>(), PinnedStatus.RELOADING, hasPinned = true))
        assertEquals(PinnedView.NoApps(hasPinned = false), pinnedView(emptyList<String>(), PinnedStatus.RELOADING, hasPinned = false))
    }

    @Test
    fun `an empty list of unavailable apps says so, even after a failed reload`() {
        assertEquals(PinnedView.NoApps(hasPinned = true), pinnedView(emptyList<String>(), PinnedStatus.CURRENT, hasPinned = true))
        assertEquals(PinnedView.NoApps(hasPinned = true), pinnedView(emptyList<String>(), PinnedStatus.FAILED, hasPinned = true))
    }

    @Test
    fun `apps show as a grid, marked while reloading`() {
        assertEquals(PinnedView.Grid(one, reloading = false), pinnedView(one, PinnedStatus.CURRENT, hasPinned = true))
        assertEquals(PinnedView.Grid(one, reloading = true), pinnedView(one, PinnedStatus.RELOADING, hasPinned = true))
        assertEquals(PinnedView.Grid(one, reloading = false, failed = true), pinnedView(one, PinnedStatus.FAILED, hasPinned = true))
    }
}
