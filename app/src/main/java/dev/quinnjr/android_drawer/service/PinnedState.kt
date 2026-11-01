package dev.quinnjr.android_drawer.service

import dev.quinnjr.android_drawer.overlay.PinnedStatus
import dev.quinnjr.android_drawer.settings.AppKey

/** The apps loaded for a set of pinned [keys] ([apps] null if the load failed), as of retry [retry]. */
internal class LoadedPinned<T>(val keys: Set<AppKey>, val apps: Map<AppKey, T>?, val retry: Int) {
    /** These apps in [order] (keys not loaded are left out); null if the load failed. */
    fun ordered(order: List<AppKey>): List<T>? = apps?.let { byKey -> order.mapNotNull(byKey::get) }

    /** Only the apps among [keys] (dropping those unpinned since, and their icons). */
    fun restrictedTo(keys: Set<AppKey>) = LoadedPinned(this.keys intersect keys, apps?.filterKeys { it in keys }, retry)
}

/**
 * The load the drawer shows: the latest one, or while a reload runs ([reloading]) or after one
 * failed ([failed]) the last good one. The current pinned order is applied to it afterwards, so
 * a kept load always shows the saved order and leaves out apps unpinned since.
 */
internal data class KeptLoad<T>(
    val load: LoadedPinned<T>,
    val reloading: Boolean = false,
    val failed: Boolean = false,
    /** The retry the latest load (good or failed) was for. */
    val retry: Int = load.retry,
)

/** The next kept load, given [next]: a new load, or null when a reload has just started. */
internal fun <T> KeptLoad<T>?.keep(next: LoadedPinned<T>?): KeptLoad<T>? = when {
    next == null -> this?.copy(reloading = true)
    // Failed with a good load to keep: keep it (minus apps unpinned since), noting this attempt.
    next.apps == null && this?.load?.apps != null ->
        KeptLoad(load.restrictedTo(next.keys), reloading = false, failed = true, retry = next.retry)
    else -> KeptLoad(next, failed = next.apps == null)
}

/**
 * How current this kept load is for the pinned [keys], given the retries asked for so far: a
 * reload under way, a retry asked for but not yet loaded, or a load for another set (its reload
 * is on the way) all count as reloading.
 */
internal fun <T> KeptLoad<T>.status(keys: Set<AppKey>, retryRequested: Int): PinnedStatus = when {
    reloading || retry < retryRequested -> PinnedStatus.RELOADING
    failed -> PinnedStatus.FAILED
    load.keys != keys -> PinnedStatus.RELOADING
    else -> PinnedStatus.CURRENT
}

/**
 * What a pinned-apps load depends on: the pinned [set], when an app last changed ([changedAt],
 * elapsed-realtime ms; 0 if never), the [retry] count, and other [trigger]s (the display config).
 */
internal data class LoadInputs(val set: Set<AppKey>, val changedAt: Long, val trigger: Any?, val retry: Int = 0) {
    /**
     * How long to wait before loading: until [settleMs] after the last app change, so a burst of
     * changes (e.g. many updates) loads once, whatever else triggers the load meanwhile.
     */
    fun settleDelay(now: Long, settleMs: Long): Long =
        if (changedAt == 0L) { 0L } else { (changedAt + settleMs - now).coerceAtLeast(0L) }
}
