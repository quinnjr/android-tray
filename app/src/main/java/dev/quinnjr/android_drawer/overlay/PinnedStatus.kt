package dev.quinnjr.android_drawer.overlay

/** How current the pinned apps the drawer shows are. */
enum class PinnedStatus {
    /** Loaded for exactly the pinned apps. */
    CURRENT,

    /** Being reloaded (an app, the pinned set or the display changed, or a retry): shown meanwhile. */
    RELOADING,

    /** The last reload failed; what's shown is from before it (opening the drawer retries). */
    FAILED,
}

/** What the drawer's pinned area shows (see [pinnedView]). */
sealed interface PinnedView<out T> {
    /** Loading, with nothing to show yet. */
    data object Loading : PinnedView<Nothing>

    /** Loading failed with nothing ever loaded to show (opening the drawer again retries). */
    data object LoadFailed : PinnedView<Nothing>

    /** Nothing to show: none pinned ([hasPinned] false), or the pinned apps are unavailable. */
    data class NoApps(val hasPinned: Boolean) : PinnedView<Nothing>

    /** The [apps], marked [reloading] while a reload runs, or [failed] if the last one failed. */
    data class Grid<T>(val apps: List<T>, val reloading: Boolean, val failed: Boolean = false) : PinnedView<T>
}

/**
 * What to show for the pinned [apps] (null: nothing loaded for them yet, or loading failed with
 * nothing to show) at [status], given whether any apps are pinned. A list that loaded empty
 * because the apps are unavailable (e.g. a paused work profile) keeps saying so through reloads
 * and failed reloads; with none pinned, "no apps" stays put through reloads and failures alike.
 */
fun <T> pinnedView(apps: List<T>?, status: PinnedStatus, hasPinned: Boolean): PinnedView<T> = when {
    !hasPinned && (apps != null || status != PinnedStatus.RELOADING) -> PinnedView.NoApps(hasPinned = false)
    apps == null && status == PinnedStatus.FAILED -> PinnedView.LoadFailed
    apps == null -> PinnedView.Loading
    apps.isEmpty() -> PinnedView.NoApps(hasPinned = true)
    else -> PinnedView.Grid(apps, reloading = status == PinnedStatus.RELOADING, failed = status == PinnedStatus.FAILED)
}
