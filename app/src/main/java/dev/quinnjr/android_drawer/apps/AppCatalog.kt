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
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
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
    /** [icon] already drawn to a bitmap, so the drawer doesn't rasterize on the main thread. */
    val bitmap: ImageBitmap? = null,
)

/** Draws an icon at [sizePx] square. */
fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap = toBitmap(sizePx, sizePx).asImageBitmap()

/**
 * Pre-renders each app's icon at [sizePx] and drops the drawable, which the long-running service
 * would otherwise keep alongside it. Call off the main thread.
 */
fun List<LaunchableApp>.withBitmaps(sizePx: Int): List<LaunchableApp> =
    map { app -> app.copy(bitmap = app.icon?.toImageBitmap(sizePx), icon = null) }

class AppCatalog(context: Context) {
    private val packageName = context.packageName
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    /** Every launchable activity across available profiles, sorted by label. */
    fun allApps(): List<LaunchableApp> = activities()
        .map { (key, info) -> info.toApp(key) }
        .sortedBy { it.label.lowercase() }

    /** The apps for [keys], in order; icons are loaded only for these. */
    fun resolve(keys: List<AppKey>): List<LaunchableApp> =
        resolveInOrder(keys, activities().associateBy { it.first }).map { (key, info) -> info.toApp(key) }

    /**
     * Up to [limit] recently used personal-profile apps, newest first, leaving out [pinned] apps,
     * this app and the home screen.
     */
    fun recent(pinned: List<AppKey>, recents: RecentApps, limit: Int = RECENT_LIMIT): List<LaunchableApp> {
        if (!recents.hasAccess()) return emptyList()
        val me = Process.myUserHandle()
        val serial = userManager.getSerialNumberForUser(me)
        val byPackage = runCatching { launcherApps.getActivityList(null, me) }
            .getOrDefault(emptyList())
            .groupBy { it.componentName.packageName }
        val excluded = recents.homePackages() + packageName
        val pinnedHere = pinned.filter { it.userSerial == serial }.mapTo(HashSet()) { it.component }
        fun choose(history: UsageHistory): List<LauncherActivityInfo> = history.pick(excluded, byPackage.keys)
            .asSequence()
            .map { pkg ->
                // Prefer the entry point actually used, for apps with several (e.g. Google and Lens).
                val entries = byPackage.getValue(pkg)
                entries.firstOrNull { it.componentName.className == history.lastActivity(pkg) } ?: entries.first()
            }
            .filter { it.componentName.flattenToString() !in pinnedHere }
            .take(limit)
            .toList()

        // A few hours almost always fill the slots; only read the older span when they don't.
        val now = System.currentTimeMillis()
        val latest = recents.events(now - SHORT_LOOKBACK_MS, now)
        var picked = choose(UsageHistory().replay(latest))
        if (picked.size < limit) {
            picked = choose(UsageHistory().replay(recents.events(now - LONG_LOOKBACK_MS, now - SHORT_LOOKBACK_MS) + latest))
        }
        return picked.map { info -> info.toApp(AppKey(info.componentName.flattenToString(), serial)) }
    }

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

    companion object {
        /** How many recent apps the drawer shows. */
        const val RECENT_LIMIT = 4
        private const val TAG = "EdgeDrawer"
        private const val SHORT_LOOKBACK_MS = 6L * 60 * 60 * 1000
        private const val LONG_LOOKBACK_MS = 3L * 24 * 60 * 60 * 1000
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
