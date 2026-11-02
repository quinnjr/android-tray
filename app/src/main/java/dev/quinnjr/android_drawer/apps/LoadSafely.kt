package dev.quinnjr.android_drawer.apps

import android.util.Log
import dev.quinnjr.android_drawer.TAG
import kotlinx.coroutines.CancellationException

/**
 * Runs [block]; a failure (e.g. a profile removed meanwhile, a broken app icon) is logged and
 * gives [fallback] rather than crashing. Cancellation is never swallowed, so coroutines that are
 * superseded (mapLatest, collectLatest) still stop.
 */
internal inline fun <T> orOnFailure(what: String, fallback: T, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "Could not load $what", e)
    fallback
}
