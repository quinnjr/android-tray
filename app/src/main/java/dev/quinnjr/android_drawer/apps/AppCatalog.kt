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
import java.text.Collator
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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

    /**
     * Every launchable app, sorted by label as the user's language sorts it. Each app comes from
     * [obtain], which returns a cached one or makes (and draws) it right away, so only one app's
     * full icon is held at a time.
     */
    fun allApps(obtain: AppSource = MakeEach): List<LaunchableApp> {
        val collator = Collator.getInstance()
        return activities()
            .mapNotNull { (key, info) -> obtainOrSkip(obtain, key) { info.toApp(key) } }
            .map { collator.getCollationKey(it.label) to it }
            .sortedBy { it.first }
            .map { it.second }
    }

    /**
     * The apps for [keys], in order, and which keys are unavailable (locked, paused or removed
     * profile; app uninstalled). An app that merely failed to load is in neither. Apps [cached]
     * already has are used as they are; for the rest only their packages are listed (not every
     * installed app), and they're made through [obtain].
     */
    fun resolve(
        keys: List<AppKey>,
        cached: (AppKey) -> LaunchableApp? = { null },
        obtain: AppSource = MakeEach,
    ): Resolved {
        // Checked for every key, cached or not: a cached app's profile may since have been
        // paused, locked or removed.
        val available = availableUsers()
        val shown = keys.filter { it.userSerial in available }
        val known = shown.mapNotNull { key -> cached(key)?.let { key to it } }.toMap()
        val found = HashMap<AppKey, LauncherActivityInfo>()
        val wanted = shown.filter { it !in known }
            .groupBy { it.userSerial to it.pkg }
            .mapValues { it.value.toHashSet() }
        // Packages whose listing failed: their apps can't be called unavailable.
        val unknown = HashSet<AppKey>()
        for ((where, group) in wanted) {
            val (serial, pkg) = where
            val user = available.getValue(serial)
            val entries = try {
                launcherApps.getActivityList(pkg, user)
            } catch (e: Exception) {
                Log.w(TAG, "Could not list $pkg for $user", e)
                unknown += group
                continue
            }
            for (info in entries) {
                val key = AppKey(info.componentName.flattenToString(), serial)
                if (key in group) {
                    found[key] = info
                }
            }
        }
        val apps = shown.mapNotNull { key -> known[key] ?: found[key]?.let { info -> obtainOrSkip(obtain, key) { info.toApp(key) } } }
        val unavailable = keys.filterTo(HashSet()) { it.userSerial !in available || (it !in known && it !in found && it !in unknown) }
        return Resolved(apps, unavailable)
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
        /** Gives cached apps (e.g. drawn on an earlier open) or makes them, so icons aren't reloaded. */
        obtain: AppSource = MakeEach,
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
        return picked.mapNotNull { info ->
            val key = AppKey(info.componentName.flattenToString(), serial)
            obtainOrSkip(obtain, key) { info.toApp(key) }
        }
    }

    /**
     * One app through [obtain], or null if loading it failed (e.g. a broken icon, or its profile
     * just went away): one bad app is left out rather than failing the whole list.
     */
    private inline fun obtainOrSkip(obtain: AppSource, key: AppKey, crossinline make: () -> LaunchableApp): LaunchableApp? =
        orOnFailure("$key", null) { obtain(key) { make() } }

    /** Personal-profile launcher entries by package, cached until [invalidate]. */
    private fun personalEntries(): Map<String, List<LauncherActivityInfo>> {
        entriesCache?.let { return it }
        val generation = entriesGeneration
        val entries = try {
            launcherApps.getActivityList(null, Process.myUserHandle()).groupBy { it.componentName.packageName }
        } catch (e: Exception) {
            // Not cached: a passing failure mustn't leave recents empty until the next app change.
            Log.w(TAG, "Could not list apps", e)
            return emptyMap()
        }
        // Don't store a list read before an invalidate() that happened while reading it.
        synchronized(entriesLock) {
            if (generation == entriesGeneration) {
                entriesCache = entries
            }
        }
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

/** Resolved pinned apps, and the keys that are definitely unavailable (not merely failed to load). */
data class Resolved(val apps: List<LaunchableApp>, val unavailable: Set<AppKey>)

/**
 * The pinned apps for [keys] through [cache] (drawn ones reused, others drawn and kept); null if
 * loading failed altogether. Shared by the drawer and the settings screen.
 */
suspend fun AppCatalog.resolveDrawn(cache: DrawnAppCache, keys: List<AppKey>): Resolved? =
    orOnFailure("pinned apps", null) {
        cache.load { obtain -> resolve(keys, cached = { cache[it] }, obtain = obtain) }
    }

/**
 * How a catalog gets each app: `obtain(key) { make() }` returns a cached app for [AppKey] or makes
 * one (and may draw its icon) with the given function.
 */
typealias AppSource = (key: AppKey, make: () -> LaunchableApp) -> LaunchableApp

/** Makes every app afresh; no cache. */
val MakeEach: AppSource = { _, make -> make() }

/**
 * Events with a burst collapsed into one once it settles. With [holdFirst] false the first value
 * (e.g. a StateFlow's current one) passes at once; only later ones are held back.
 */
@OptIn(FlowPreview::class)
fun <T> Flow<T>.settled(holdFirst: Boolean = true): Flow<T> = flow {
    var first = !holdFirst
    emitAll(
        debounce {
            if (first) {
                first = false
                0L
            } else {
                APPS_CHANGED_DEBOUNCE_MS
            }
        },
    )
}

/** These app changes as reloads: a burst of [relevant] ones gives one, and there's one to start. */
fun Flow<String?>.reloads(relevant: (pkg: String?) -> Boolean = { true }): Flow<Unit> =
    filter(relevant).settled().map { }.onStart { emit(Unit) }

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

        // Suspended apps (Digital Wellbeing, Focus mode, an admin) get a greyed-out icon.
        override fun onPackagesSuspended(packageNames: Array<out String>?, user: UserHandle?) {
            packageNames.orEmpty().forEach { trySend(it) }
        }

        override fun onPackagesUnsuspended(packageNames: Array<out String>?, user: UserHandle?) {
            packageNames.orEmpty().forEach { trySend(it) }
        }
    }
    launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
    awaitClose {
        launcherApps.unregisterCallback(callback)
        context.unregisterReceiver(profileReceiver)
    }
    // Unbounded: a big update burst must not drop events, as each one invalidates caches.
}.buffer(Channel.UNLIMITED)
