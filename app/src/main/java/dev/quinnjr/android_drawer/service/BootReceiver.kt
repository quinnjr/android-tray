package dev.quinnjr.android_drawer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.quinnjr.android_drawer.TAG
import dev.quinnjr.android_drawer.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val SETTINGS_READ_TIMEOUT_MS = 5_000L

/** Brings the handle back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) { return }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Settings that can't be read soon (they're retried, not guessed) leave the handle
                // off until the app is opened, rather than overrun the broadcast's time limit.
                val enabled = withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) {
                    SettingsRepository.get(context).settings.first().enabled
                } ?: return@launch
                EdgeService.sync(context, enabled)
            } catch (e: Exception) {
                // Not worth crashing the app at boot; the handle comes back when the app is opened.
                Log.e(TAG, "Could not start the edge handle", e)
            } finally {
                pending.finish()
            }
        }
    }
}
