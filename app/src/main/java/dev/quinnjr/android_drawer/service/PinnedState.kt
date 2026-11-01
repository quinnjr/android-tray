package dev.quinnjr.android_drawer.service

import dev.quinnjr.android_drawer.settings.AppKey

/** The apps loaded for a set of pinned [keys]; [apps] null if the load failed. */
internal class LoadedPinned<T>(val keys: Set<AppKey>, val apps: Map<AppKey, T>?) {
    /** These apps in [order] (keys not loaded are left out); null if the load failed. */
    fun ordered(order: List<AppKey>): List<T>? = apps?.let { byKey -> order.mapNotNull(byKey::get) }
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
    next.apps == null && this?.load?.apps != null -> copy(reloading = false, failed = true)
    else -> KeptLoad(next, failed = next.apps == null)
}

/**
 * What a pinned-apps load depends on: the pinned [set], the app-change counter [change], and other
 * [trigger]s (display config, retries). [settle]: this load follows an app change, so it waits for
 * a burst of changes (e.g. many updates) to settle first.
 */
internal data class LoadInputs(val set: Set<AppKey>, val change: Int, val trigger: Any?, val settle: Boolean = false) {
    fun after(previous: LoadInputs?) = copy(settle = previous != null && change != previous.change)
}
