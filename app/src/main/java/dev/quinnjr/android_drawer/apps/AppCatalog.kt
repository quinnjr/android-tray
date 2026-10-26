package dev.quinnjr.android_drawer.apps

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.quinnjr.android_drawer.settings.AppKey
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class LaunchableApp(
    val key: AppKey,
    val label: String,
    val icon: Drawable?,
    val component: ComponentName,
    val user: UserHandle,
)

class AppCatalog(context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    /** Every launchable activity across available profiles, sorted by label. */
    fun allApps(): List<LaunchableApp> = activities()
        .map { (key, info) -> info.toApp(key) }
        .sortedBy { it.label.lowercase() }

    /** The apps for [keys], in order; icons are loaded only for these. */
    fun resolve(keys: List<AppKey>): List<LaunchableApp> =
        resolveInOrder(keys, activities().associateBy { it.first }).map { (key, info) -> info.toApp(key) }

    private fun activities(): List<Pair<AppKey, LauncherActivityInfo>> = launcherApps.profiles
        .filter { user -> userManager.isUserUnlocked(user) && !userManager.isQuietModeEnabled(user) }
        .flatMap { user ->
            val serial = userManager.getSerialNumberForUser(user)
            runCatching { launcherApps.getActivityList(null, user) }
                .onFailure { Log.w(TAG, "Could not list apps for $user", it) }
                .getOrDefault(emptyList())
                .map { info -> AppKey(info.componentName.flattenToString(), serial) to info }
        }

    private fun LauncherActivityInfo.toApp(key: AppKey) = LaunchableApp(
        key = key,
        label = label.toString(),
        icon = getBadgedIcon(0),
        component = componentName,
        user = user,
    )

    fun launch(app: LaunchableApp, sourceBounds: Rect?) {
        try {
            launcherApps.startMainActivity(app.component, app.user, sourceBounds, null)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "App vanished: ${app.component}", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to launch ${app.component}", e)
        }
    }

    private companion object {
        const val TAG = "EdgeDrawer"
    }
}

internal fun <T> resolveInOrder(keys: List<AppKey>, available: Map<AppKey, T>): List<T> =
    keys.mapNotNull { available[it] }

/**
 * Emits whenever an app is installed, removed or changed in any profile, or a profile
 * (work apps, Private Space) is paused, locked or made available again.
 */
fun appsChanged(context: Context): Flow<Unit> = callbackFlow {
    val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { trySend(Unit) }
    }
    val profileActions = IntentFilter().apply {
        addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
        addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
        addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            addAction(Intent.ACTION_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
        }
    }
    ContextCompat.registerReceiver(context, profileReceiver, profileActions, ContextCompat.RECEIVER_NOT_EXPORTED)

    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) { trySend(Unit) }
        override fun onPackageAdded(packageName: String?, user: UserHandle?) { trySend(Unit) }
        override fun onPackageChanged(packageName: String?, user: UserHandle?) { trySend(Unit) }
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) { trySend(Unit) }
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) { trySend(Unit) }
    }
    launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
    awaitClose {
        launcherApps.unregisterCallback(callback)
        context.unregisterReceiver(profileReceiver)
    }
}
