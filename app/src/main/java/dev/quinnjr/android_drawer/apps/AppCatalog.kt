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
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import dev.quinnjr.android_drawer.TAG
import dev.quinnjr.android_drawer.settings.AppKey
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

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
 * This app with its icon pre-rendered at [sizePx] and the drawable dropped, which the
 * long-running service would otherwise keep alongside it. Call off the main thread.
 */
fun LaunchableApp.withBitmap(sizePx: Int): LaunchableApp = copy(bitmap = icon?.toImageBitmap(sizePx), icon = null)

class AppCatalog(context: Context) {
    private val packageName = context.packageName
    private val entriesLock = Any()
    @Volatile private var entriesCache: Map<String, List<LauncherActivityInfo>>? = null
    @Volatile private var entriesGeneration = 0
    @Volatile private var homeCache: Pair<Long, Set<String>>? = null
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    /** Every launchable app, sorted by label; apps [cached] already has aren't loaded again. */
    fun allApps(cached: (AppKey) -> LaunchableApp? = { null }): List<LaunchableApp> = activities()
        .map { (key, info) -> cached(key) ?: info.toApp(key) }
        .sortedBy { it.label.lowercase() }

    /**
     * The apps for [keys], in order; apps in a locked or paused profile are left out. Apps
     * [cached] already has are used as they are; for the rest only their packages are listed (not
     * every installed app), and icons load only for these.
     */
    fun resolve(keys: List<AppKey>, cached: (AppKey) -> LaunchableApp? = { null }): List<LaunchableApp> {
        // Checked for every key, cached or not: a cached app's profile may since have been
        // paused, locked or removed.
        val available = availableUsers()
        val shown = keys.filter { it.userSerial in available }
        val known = shown.mapNotNull { key -> cached(key)?.let { key to it } }.toMap()
        val found = HashMap<AppKey, LauncherActivityInfo>()
        val wanted = shown.filter { it !in known }
            .groupBy { it.userSerial to it.pkg }
            .mapValues { it.value.toHashSet() }
        for ((where, group) in wanted) {
            val (serial, pkg) = where
            val user = available.getValue(serial)
            runCatching { launcherApps.getActivityList(pkg, user) }
                .onFailure { Log.w(TAG, "Could not list $pkg for $user", it) }
                .getOrDefault(emptyList())
                .forEach { info ->
                    val key = AppKey(info.componentName.flattenToString(), serial)
                    if (key in group) found[key] = info
                }
        }
        return shown.mapNotNull { key -> known[key] ?: found[key]?.toApp(key) }
    }

    /** Profiles whose apps can be shown now (unlocked, not paused), by serial number. */
    private fun availableUsers(): Map<Long, UserHandle> = launcherApps.profiles
        .filter { user -> userManager.isUserUnlocked(user) && !userManager.isQuietModeEnabled(user) }
        .associateBy { userManager.getSerialNumberForUser(it) }

    /**
     * Up to [limit] recently used personal-profile apps, newest first, leaving out [pinned] apps,
     * this app and the home screen.
     */
    fun recent(
        pinned: List<AppKey>,
        recents: RecentApps,
        /** Already-loaded apps (e.g. with icons drawn on an earlier open), so their icons aren't reloaded. */
        cached: (AppKey) -> LaunchableApp? = { null },
        limit: Int = RECENT_LIMIT,
    ): List<LaunchableApp> {
        // Callers check recents.hasAccess() first (the drawer needs it to lay out anyway).
        val me = Process.myUserHandle()
        val serial = userManager.getSerialNumberForUser(me)
        val byPackage = personalEntries()
        val excluded = homePackages(recents) + packageName
        val pinnedHere = pinned.filter { it.userSerial == serial }.mapTo(HashSet()) { it.component }
        fun LauncherActivityInfo.isPinned() = componentName.flattenToString() in pinnedHere

        val picked = recents.withHistory { history ->
            history.pick(excluded, byPackage.keys).asSequence()
                .mapNotNull { pkg ->
                    val entries = byPackage.getValue(pkg)
                    val used = entries.firstOrNull { it.componentName.className == history.lastActivity(pkg) }
                    when {
                        // Show the entry point actually used (e.g. Lens rather than Google), unless pinned.
                        used != null -> used.takeUnless { it.isPinned() }
                        // Used through an inner screen, so the entry is unknown: skip if any is pinned.
                        entries.any { it.isPinned() } -> null
                        else -> entries.first()
                    }
                }
                .take(limit)
                .toList()
        }
        return picked.map { info ->
            val key = AppKey(info.componentName.flattenToString(), serial)
            cached(key) ?: info.toApp(key)
        }
    }

    /** Personal-profile launcher entries by package, cached until [invalidate]. */
    private fun personalEntries(): Map<String, List<LauncherActivityInfo>> {
        entriesCache?.let { return it }
        val generation = entriesGeneration
        val entries = runCatching { launcherApps.getActivityList(null, Process.myUserHandle()) }
            .getOrDefault(emptyList())
            .groupBy { it.componentName.packageName }
        // Don't store a list read before an invalidate() that happened while reading it.
        synchronized(entriesLock) { if (generation == entriesGeneration) entriesCache = entries }
        return entries
    }

    /** Drops cached launcher entries; call when apps are installed, removed or changed. */
    fun invalidate() = synchronized(entriesLock) {
        entriesGeneration++
        entriesCache = null
        homeCache = null
    }

    /**
     * Home apps, cached briefly: choosing a different default launcher sends no event, so the
     * cache also expires on its own.
     */
    private fun homePackages(recents: RecentApps): Set<String> {
        val now = SystemClock.elapsedRealtime()
        homeCache?.takeIf { now - it.first < HOME_CACHE_MS }?.let { return it.second }
        return recents.homePackages().also { homeCache = now to it }
    }

    private fun activities(): List<Pair<AppKey, LauncherActivityInfo>> = availableUsers().entries
        .flatMap { (serial, user) ->
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
        private const val HOME_CACHE_MS = 60_000L
    }
}

/** How long app changes must settle before a burst of them (e.g. many updates) is acted on once. */
private const val APPS_CHANGED_DEBOUNCE_MS = 500L

/** Events with a burst collapsed into one once it settles. */
@OptIn(FlowPreview::class)
fun <T> Flow<T>.settled(): Flow<T> = debounce(APPS_CHANGED_DEBOUNCE_MS)

/**
 * App changes worth reloading for. [onChange] sees every change at once (to drop caches), then a
 * burst of [relevant] ones (e.g. many updates) gives a single emission once it settles. Starts
 * with one emission, for the initial load.
 */
fun appChanges(
    context: Context,
    relevant: (pkg: String?) -> Boolean = { true },
    onChange: (pkg: String?) -> Unit = {},
): Flow<Unit> = appsChanged(context)
    .onEach(onChange)
    .filter(relevant)
    .settled()
    .map { }
    .onStart { emit(Unit) }

/** Whether this key belongs to [pkg]. */
fun AppKey.isIn(pkg: String): Boolean = this.pkg == pkg

/**
 * Emits whenever an app is installed, removed or changed in any profile (its package name), or
 * several apps or a whole profile (work apps, Private Space) change at once (null).
 */
fun appsChanged(context: Context): Flow<String?> = callbackFlow {
    val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { trySend(null) }
    }
    val profileActions = IntentFilter().apply {
        addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
        addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
        addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
        addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            addAction(Intent.ACTION_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
        }
    }
    ContextCompat.registerReceiver(context, profileReceiver, profileActions, ContextCompat.RECEIVER_NOT_EXPORTED)

    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) { trySend(packageName) }
        override fun onPackageAdded(packageName: String?, user: UserHandle?) { trySend(packageName) }
        override fun onPackageChanged(packageName: String?, user: UserHandle?) { trySend(packageName) }
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) { trySend(null) }
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) { trySend(null) }
    }
    launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
    awaitClose {
        launcherApps.unregisterCallback(callback)
        context.unregisterReceiver(profileReceiver)
    }
}
