package dev.quinnjr.android_drawer.ui

import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.settings.AppKey

/** The pinned [keys] and, of those, the [apps] that could be shown, taken together. */
data class PinnedApps(val keys: List<AppKey>, val apps: List<LaunchableApp>) {
    /** Pinned apps that can't be shown now (e.g. a paused work profile, or uninstalled). */
    val unavailable: Set<AppKey> get() = keys.toSet() - apps.mapTo(HashSet()) { it.key }
}
