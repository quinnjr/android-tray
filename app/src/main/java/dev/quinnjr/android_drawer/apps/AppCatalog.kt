package dev.quinnjr.android_drawer.apps

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
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
    fun allApps(): List<LaunchableApp> = launcherApps.profiles
        .filter { user -> userManager.isUserUnlocked(user) && !userManager.isQuietModeEnabled(user) }
        .flatMap { user ->
            val serial = userManager.getSerialNumberForUser(user)
            runCatching { launcherApps.getActivityList(null, user) }
                .onFailure { Log.w(TAG, "Could not list apps for $user", it) }
                .getOrDefault(emptyList())
                .map { info ->
                    LaunchableApp(
                        key = AppKey(info.componentName.flattenToString(), serial),
                        label = info.label.toString(),
                        icon = info.getBadgedIcon(0),
                        component = info.componentName,
                        user = user,
                    )
                }
        }
        .sortedBy { it.label.lowercase() }

    fun resolve(keys: List<AppKey>): List<LaunchableApp> =
        resolveInOrder(keys, allApps().associateBy { it.key })

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

/** Emits whenever an app is installed, removed or changed in any profile. */
fun appsChanged(context: Context): Flow<Unit> = callbackFlow {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) { trySend(Unit) }
        override fun onPackageAdded(packageName: String?, user: UserHandle?) { trySend(Unit) }
        override fun onPackageChanged(packageName: String?, user: UserHandle?) { trySend(Unit) }
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) { trySend(Unit) }
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) { trySend(Unit) }
    }
    launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
    awaitClose { launcherApps.unregisterCallback(callback) }
}
