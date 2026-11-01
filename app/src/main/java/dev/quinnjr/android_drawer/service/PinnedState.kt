package dev.quinnjr.android_drawer.service

import dev.quinnjr.android_drawer.overlay.PinnedStatus
import dev.quinnjr.android_drawer.settings.AppKey
import dev.quinnjr.android_drawer.settings.valuesIn

/**
 * The apps loaded for a set of pinned [keys]: [apps] null if the load failed outright; [unknown]
 * those that still couldn't be listed after the load's own retries (their earlier copy is kept).
 * [retry] counts the retries asked for (on drawer open) before this load.
 */
internal class LoadedPinned<T>(
    val keys: Set<AppKey>,
    val apps: Map<AppKey, T>?,
    val unknown: Set<AppKey> = emptySet(),
    val retry: Int = 0,
) {
    val incomplete: Boolean get() = unknown.isNotEmpty()

    /** These apps in [order] (keys not loaded are left out); null if the load failed. */
    fun ordered(order: List<AppKey>): List<T>? = apps?.valuesIn(order)

    /** Only the apps among [keys] (dropping those unpinned since, and their icons). */
    fun restrictedTo(keys: Set<AppKey>) =
        LoadedPinned(this.keys intersect keys, apps?.filterKeys { it in keys }, unknown intersect keys, retry)
}

/**
 * The load the drawer shows: the latest one, or while a reload runs ([reloading]) or after one
 * failed ([failed]) the last good one. The current pinned order is applied to it afterwards, so
 * a kept load always shows the saved order and leaves out apps unpinned since. [attempt]:
 * the retry count of the latest load (see [LoadedPinned.retry]), so a retry asked for since shows.
 */
internal data class KeptLoad<T>(
    val load: LoadedPinned<T>,
    val reloading: Boolean = false,
    val failed: Boolean = false,
    val attempt: Int = load.retry,
)

/** The next kept load, given [next]: a new load, or null when a reload has just started. */
internal fun <T> KeptLoad<T>?.keep(next: LoadedPinned<T>?): KeptLoad<T>? = when {
    next == null -> this?.copy(reloading = true)
    // Failed with a good load to keep: keep it (minus apps unpinned since), noting this attempt.
    next.apps == null && this?.load?.apps != null ->
        KeptLoad(
            load.restrictedTo(next.keys),
            reloading = false,
            failed = true,
            attempt = next.retry,
        )
    // Some apps still couldn't be listed (the load already retried them): their earlier copy, if
    // any, is kept rather than dropped. Not a failure: a drawer opening wouldn't do better than
    // those retries, and launching such an app just says it isn't available.
    next.apps != null && next.incomplete -> {
        val earlier = this?.load?.apps.orEmpty().filterKeys { it in next.unknown }
        KeptLoad(LoadedPinned(next.keys, earlier + next.apps, next.unknown, next.retry))
    }
    else -> KeptLoad(next, failed = next.apps == null)
}

/**
 * How current this kept load is for the pinned [keys]: a reload under way, a failure being
 * retried (the retries asked for are [retryAsked]), or a load for another set (its reload is on
 * the way), counts as reloading.
 */
internal fun <T> KeptLoad<T>.status(keys: Set<AppKey>, retryAsked: Int = attempt): PinnedStatus = when {
    reloading -> PinnedStatus.RELOADING
    failed && retryAsked > attempt -> PinnedStatus.RELOADING
    failed -> PinnedStatus.FAILED
    load.keys != keys -> PinnedStatus.RELOADING
    else -> PinnedStatus.CURRENT
}
