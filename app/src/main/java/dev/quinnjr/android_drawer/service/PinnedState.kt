package dev.quinnjr.android_drawer.service

import dev.quinnjr.android_drawer.overlay.PinnedStatus
import dev.quinnjr.android_drawer.settings.AppKey
import dev.quinnjr.android_drawer.settings.valuesIn

/**
 * The apps loaded for a set of pinned [keys]: [apps] null if the load failed outright; [unknown]
 * those that couldn't be listed this time (the rest is shown, counted as a failure, so a drawer
 * opening retries). [retry] counts the retries asked for (on drawer open) before this load.
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
 * a kept load always shows the saved order and leaves out apps unpinned since. [givenUp]: apps
 * that still couldn't be listed after a retry, left out without counting as a failure. [attempt]:
 * the retry count of the latest load (see [LoadedPinned.retry]), so a retry asked for since shows.
 */
internal data class KeptLoad<T>(
    val load: LoadedPinned<T>,
    val reloading: Boolean = false,
    val failed: Boolean = false,
    val givenUp: Set<AppKey> = emptySet(),
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
            givenUp = givenUp intersect next.keys,
            attempt = next.retry,
        )
    // Some apps couldn't be listed this time: keep their earlier copy (if any) rather than drop
    // them, as a failure, so opening the drawer tries again. Once a retry asked for that way fails
    // for them too, they're left out and no longer count as failed (no endless retries for an app
    // that never lists), until they list again. Other reloads meanwhile don't count as that retry.
    next.apps != null && next.incomplete -> {
        val retried = if (this != null && next.retry > load.retry) { load.unknown } else { emptySet() }
        val givenUp = (this?.givenUp.orEmpty() + retried) intersect next.unknown
        val fresh = next.unknown - givenUp
        val earlier = this?.load?.apps.orEmpty().filterKeys { it in fresh }
        KeptLoad(
            LoadedPinned(next.keys, earlier + next.apps, next.unknown, next.retry),
            failed = fresh.isNotEmpty(),
            givenUp = givenUp,
        )
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
