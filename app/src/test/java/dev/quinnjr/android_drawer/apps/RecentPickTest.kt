package dev.quinnjr.android_drawer.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentPickTest {
    private val all = setOf("a", "b", "c", "d", "e", "f", "now", "split")

    /** Each app is resumed then paused at [time], in order, unless listed in [stillOpen]. */
    private fun history(vararg uses: Pair<String, Long>, stillOpen: List<Pair<String, Long>> = emptyList()) =
        UsageHistory().apply {
            for ((pkg, time) in uses) {
                resumed(pkg, time)
                paused(pkg)
            }
            for ((pkg, time) in stillOpen) resumed(pkg, time)
        }

    private fun UsageHistory.pick(excluded: Set<String> = emptySet(), launchable: Set<String> = all, limit: Int = 4) =
        pick(excluded, launchable, limit)

    @Test
    fun `most recent first, using each app's latest use`() {
        val h = history("a" to 10L, "b" to 20L, "a" to 30L, "c" to 25L)
        assertEquals(listOf("a", "c", "b"), h.pick())
    }

    @Test
    fun `apps still on screen are left out`() {
        val h = history("a" to 1L, stillOpen = listOf("now" to 2L))
        assertEquals(listOf("a"), h.pick())
    }

    @Test
    fun `both split-screen apps are left out`() {
        val h = history("a" to 1L, stillOpen = listOf("now" to 2L, "split" to 3L))
        assertEquals(listOf("a"), h.pick())
    }

    @Test
    fun `app under a dismissed dialog is still treated as on screen`() {
        // X paused for a dialog from another package, then resumed when it closed.
        val h = UsageHistory().apply {
            resumed("a", 1L); paused("a")
            resumed("now", 2L); paused("now")
            resumed("dialog", 3L); paused("dialog")
            resumed("now", 4L)
        }
        assertEquals(listOf("dialog", "a"), h.pick(launchable = all + "dialog"))
    }

    @Test
    fun `excluded apps are skipped and older apps fill their slots`() {
        val h = history("a" to 1L, "b" to 2L, "c" to 3L, "d" to 4L, "e" to 5L, "f" to 6L)
        assertEquals(listOf("f", "d", "b", "a"), h.pick(excluded = setOf("e", "c")))
    }

    @Test
    fun `apps without a launcher entry are skipped`() {
        val h = history("a" to 1L, "systemui" to 2L)
        assertEquals(listOf("a"), h.pick())
    }

    @Test
    fun `at most limit apps`() {
        val h = history("a" to 1L, "b" to 2L, "c" to 3L, "d" to 4L, "e" to 5L, "f" to 6L)
        assertEquals(listOf("f", "e", "d", "c"), h.pick())
    }

    @Test
    fun `no history gives nothing`() {
        assertEquals(emptyList<String>(), UsageHistory().pick())
    }
}
