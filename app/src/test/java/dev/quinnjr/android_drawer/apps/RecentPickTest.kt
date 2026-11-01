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

    private fun UsageHistory.pick(excluded: Set<String> = emptySet(), launchable: Set<String> = all) =
        pick(excluded, launchable)

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
    fun `app left resumed by a shutdown is not stuck as on screen`() {
        val h = UsageHistory().apply {
            resumed("a", 1L)
            reset()
            resumed("b", 2L); paused("b")
        }
        assertEquals(listOf("b", "a"), h.pick())
    }

    @Test
    fun `app whose pause was never recorded drops off screen once two newer apps resume`() {
        // e.g. a crash: no pause for "a", but two other apps have been on screen since.
        val h = UsageHistory().apply {
            resumed("a", 1L)
            resumed("b", 2L); paused("b")
            resumed("c", 3L)
            resumed("d", 4L)
        }
        assertEquals(listOf("b", "a"), h.pick())
    }

    @Test
    fun `stopped counts as no longer on screen`() {
        val h = UsageHistory().replay(
            listOf(
                UsageEvent(UsageEvent.Kind.RESUMED, "a", "a.Main", 1L),
                UsageEvent(UsageEvent.Kind.LEFT, "a", "a.Main", 2L),
                UsageEvent(UsageEvent.Kind.RESUMED, "now", "now.Main", 3L),
            ),
        )
        assertEquals(listOf("a"), h.pick())
    }

    @Test
    fun `app stays on screen when one of its activities stops after another resumed`() {
        // Opening a sub-screen: A pauses, B resumes, then A stops.
        val h = UsageHistory().apply {
            resumed("a", 1L, "a.Old"); paused("a", "a.Old")
            resumed("now", 2L, "now.Main"); paused("now", "now.Main")
            resumed("now", 3L, "now.Sub"); paused("now", "now.Main")
        }
        assertEquals(listOf("a"), h.pick())
    }

    @Test
    fun `remembers the activity last used in each app`() {
        val h = UsageHistory().apply {
            resumed("a", 1L, "a.Main"); paused("a")
            resumed("a", 2L, "a.Lens"); paused("a")
        }
        assertEquals("a.Lens", h.lastActivity("a"))
        assertEquals(null, h.lastActivity("b"))
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
    fun `replayed events match direct calls, older span first`() {
        val older = listOf(
            UsageEvent(UsageEvent.Kind.RESUMED, "a", "a.Main", 1L),
            UsageEvent(UsageEvent.Kind.LEFT, "a", "a.Main", 2L),
            UsageEvent(UsageEvent.Kind.RESUMED, "crashed", "c.Main", 3L),
            UsageEvent(UsageEvent.Kind.RESET, "android", null, 4L),
        )
        val latest = listOf(
            UsageEvent(UsageEvent.Kind.RESUMED, "b", "b.Main", 5L),
            UsageEvent(UsageEvent.Kind.LEFT, "b", "b.Main", 6L),
            UsageEvent(UsageEvent.Kind.RESUMED, "now", "now.Main", 7L),
        )
        val h = UsageHistory().replay(older + latest)
        assertEquals(listOf("b", "a"), h.pick(launchable = all + "crashed").filter { it != "crashed" })
        assertEquals(true, "crashed" in h.pick(launchable = all + "crashed"))
        assertEquals("b.Main", h.lastActivity("b"))
    }

    @Test
    fun `a copy is independent of the original`() {
        val settled = history("a" to 1L)
        val withTail = settled.copy().apply { resumed("b", 2L); paused("b") }
        assertEquals(listOf("a"), settled.pick())
        assertEquals(listOf("b", "a"), withTail.pick())
    }

    @Test
    fun `a long-running split screen keeps both halves on screen`() {
        val hour = 60L * 60 * 1000
        val h = UsageHistory().apply {
            resumed("a", 0L); paused("a")
            resumed("split", 1L)
            resumed("b", hour); paused("b")
            resumed("now", 2 * hour)
        }
        assertEquals(listOf("b", "a"), h.pick())
    }

    @Test
    fun `screen off clears an app left resumed by a crash`() {
        // "x" crashed without a pause; on screen-off everything shown pauses, and whatever is
        // really on screen resumes again on unlock.
        val h = UsageHistory().replay(
            listOf(
                UsageEvent(UsageEvent.Kind.RESUMED, "x", "x.Main", 1L),
                UsageEvent(UsageEvent.Kind.RESUMED, "now", "now.Main", 2L),
                UsageEvent(UsageEvent.Kind.RESET, "android", null, 3L),
                UsageEvent(UsageEvent.Kind.RESUMED, "now", "now.Main", 4L),
            ),
        )
        assertEquals(listOf("x"), h.pick(launchable = all + "x"))
    }

    @Test
    fun `no history gives nothing`() {
        assertEquals(emptyList<String>(), UsageHistory().pick())
    }
}
