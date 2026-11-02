package dev.quinnjr.android_drawer.ui

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import dev.quinnjr.android_drawer.R

/** When the last save failure was reported (elapsed realtime ms), so one undo is reported once. */
@Volatile private var lastSaveFailureReport = Long.MIN_VALUE / 2

/**
 * Whether a save failure should be reported now: not if one was just reported (one failed save
 * can fail several changes waiting on it, all undone together). Call on the main thread.
 */
fun shouldReportSaveFailure(): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (now - lastSaveFailureReport < SAVE_FAILURE_REPORT_MS) {
        return false
    }
    lastSaveFailureReport = now
    return true
}

/** Says a change couldn't be saved (it has been undone), unless that was just said. */
fun Context.toastSaveFailed() {
    if (shouldReportSaveFailure()) {
        Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
    }
}

private const val SAVE_FAILURE_REPORT_MS = 2_000L
