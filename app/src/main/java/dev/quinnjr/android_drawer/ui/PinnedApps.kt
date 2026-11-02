package dev.quinnjr.android_drawer.ui

import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.settings.AppKey

/**
 * The pinned [keys], the [apps] among them that could be shown, and those definitely
 * [unavailable] (paused profile, uninstalled), all from one load. [failed]: the load, or some of
 * it, failed (those apps are in neither list).
 */
data class PinnedApps(
    val keys: List<AppKey>,
    val apps: List<LaunchableApp>,
    val unavailable: Set<AppKey>,
    val failed: Boolean = false,
)
