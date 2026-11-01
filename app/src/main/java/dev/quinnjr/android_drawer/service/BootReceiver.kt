package dev.quinnjr.android_drawer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import dev.quinnjr.android_drawer.TAG
import dev.quinnjr.android_drawer.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Brings the handle back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = SettingsRepository(context).settings.first().enabled
                if (enabled && Settings.canDrawOverlays(context)) EdgeService.start(context)
            } catch (e: Exception) {
                // Not worth crashing the app at boot; the handle comes back when the app is opened.
                Log.e(TAG, "Could not start the edge handle", e)
            } finally {
                pending.finish()
            }
        }
    }
}
