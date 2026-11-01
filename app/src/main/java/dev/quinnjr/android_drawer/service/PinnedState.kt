package dev.quinnjr.android_drawer.service

import dev.quinnjr.android_drawer.overlay.PinnedStatus
import dev.quinnjr.android_drawer.settings.AppKey

/** The apps loaded for a set of pinned [keys] ([apps] null if the load failed). */
internal class LoadedPinned<T>(val keys: Set<AppKey>, val apps: Map<AppKey, T>?) {
    /** These apps in [order] (keys not loaded are left out); null if the load failed. */
    fun ordered(order: List<AppKey>): List<T>? = apps?.let { byKey -> order.mapNotNull(byKey::get) }

    /** Only the apps among [keys] (dropping those unpinned since, and their icons). */
    fun restrictedTo(keys: Set<AppKey>) = LoadedPinned(this.keys intersect keys, apps?.filterKeys { it in keys })
}

/**
 * The load the drawer shows: the latest one, or while a reload runs ([reloading]) or after one
 * failed ([failed]) the last good one. The current pinned order is applied to it afterwards, so
 * a kept load always shows the saved order and leaves out apps unpinned since.
 */
internal data class KeptLoad<T>(val load: LoadedPinned<T>, val reloading: Boolean = false, val failed: Boolean = false)

/** The next kept load, given [next]: a new load, or null when a reload has just started. */
internal fun <T> KeptLoad<T>?.keep(next: LoadedPinned<T>?): KeptLoad<T>? = when {
    next == null -> this?.copy(reloading = true)
    // Failed with a good load to keep: keep it (minus apps unpinned since), noting this attempt.
    next.apps == null && this?.load?.apps != null ->
        KeptLoad(load.restrictedTo(next.keys), reloading = false, failed = true)
    else -> KeptLoad(next, failed = next.apps == null)
}

/**
 * How current this kept load is for the pinned [keys]: a reload under way, or a load for another
 * set (its reload is on the way), counts as reloading.
 */
internal fun <T> KeptLoad<T>.status(keys: Set<AppKey>): PinnedStatus = when {
    reloading -> PinnedStatus.RELOADING
    failed -> PinnedStatus.FAILED
    load.keys != keys -> PinnedStatus.RELOADING
    else -> PinnedStatus.CURRENT
}

/**
 * What a pinned-apps load depends on: the pinned [set] and when an app last changed ([changedAt],
 * elapsed-realtime ms; 0 if never). The display config and retries only trigger a load.
 */
internal data class LoadInputs(val set: Set<AppKey>, val changedAt: Long) {
    /**
     * How long to wait before loading: until [settleMs] after the last app change, so a burst of
     * changes (e.g. many updates) loads once, whatever else triggers the load meanwhile.
     */
    fun settleDelay(now: Long, settleMs: Long): Long =
        if (changedAt == 0L) { 0L } else { (changedAt + settleMs - now).coerceAtLeast(0L) }
}

/**
 * An order chosen in the drawer, shown over the pinned apps until they show it themselves. It
 * applies while the shown order is still one it may be waiting on ([bases]: the order it was
 * chosen over, plus earlier choices not yet shown) or is [order] itself. Once seen ([landed]), only
 * [order] counts, so a later change elsewhere (even back to the old order) isn't overridden. It
 * never needs clearing in time; only a failed save drops it.
 */
internal data class PendingOrder(val order: List<AppKey>, val bases: Set<List<AppKey>>, val landed: Boolean = false) {
    fun appliesTo(shown: List<AppKey>?): Boolean = shown == order || (!landed && shown in bases)

    /** This, noting that the pinned apps now show [shown]. */
    fun seen(shown: List<AppKey>?): PendingOrder = if (!landed && shown == order) { copy(landed = true) } else { this }

    /** This with its order replaced by what was actually saved (if the apps changed meanwhile). */
    fun saved(written: List<AppKey>): PendingOrder =
        if (written == order) { this } else { copy(order = written, bases = bases + setOf(order)) }

    companion object {
        /** A new [order] chosen while [shown] is what the pinned apps show (and [previous] the last choice). */
        fun chosen(previous: PendingOrder?, shown: List<AppKey>, order: List<AppKey>): PendingOrder =
            if (previous != null && !previous.landed && previous.appliesTo(shown)) {
                PendingOrder(order, previous.bases + setOf(previous.order))
            } else {
                PendingOrder(order, setOf(shown))
            }
    }
}
