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
