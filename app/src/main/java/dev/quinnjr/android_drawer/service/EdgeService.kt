package dev.quinnjr.android_drawer.service

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.quinnjr.android_drawer.R
import dev.quinnjr.android_drawer.TAG
import dev.quinnjr.android_drawer.apps.AppCatalog
import dev.quinnjr.android_drawer.apps.DrawConfig
import dev.quinnjr.android_drawer.apps.DrawnAppCache
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.apps.RecentApps
import dev.quinnjr.android_drawer.apps.appsChanged
import dev.quinnjr.android_drawer.apps.orOnFailure
import dev.quinnjr.android_drawer.apps.resolveDrawn
import dev.quinnjr.android_drawer.apps.settled
import dev.quinnjr.android_drawer.overlay.DRAWER_ICON_SIZE
import dev.quinnjr.android_drawer.overlay.OverlayHost
import dev.quinnjr.android_drawer.settings.AppKey
import dev.quinnjr.android_drawer.settings.DrawerSettings
import dev.quinnjr.android_drawer.settings.SettingsRepository
import dev.quinnjr.android_drawer.settings.withVisibleOrder
import dev.quinnjr.android_drawer.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalCoroutinesApi::class)
class EdgeService : LifecycleService() {
    private lateinit var repo: SettingsRepository
    private lateinit var host: OverlayHost
    private lateinit var catalog: AppCatalog
    private lateinit var recents: RecentApps
    private lateinit var overlayContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Pinned apps with icons drawn, reused across loads. */
    private val pinnedIcons = DrawnAppCache()
    /**
     * The pinned apps, loaded in the background so the drawer usually has them at once. Derived
     * from everything they depend on (pinned keys, changes to those apps, the display config):
     * any change empties it immediately, so a stale list can never be shown. Null while loading.
     */
    private lateinit var pinned: StateFlow<Pinned?>
    /** Bumped at once on any change to a pinned app (or to many/all apps), before any debounce. */
    private val pinnedAppsChanged = MutableStateFlow(0)

    /** Pinned [apps] loaded for [keys]; [apps] null if loading failed. [load]: which load (by identity). */
    private data class Pinned(
        val keys: List<AppKey>,
        val apps: List<LaunchableApp>?,
        val load: LoadedPinned,
        /** Being reloaded (an app or the display changed): shown, but not to be launched from. */
        val reloading: Boolean = false,
    ) {
        /** These apps in [order] (the one place loaded apps are put in a pinned order). */
        fun inOrder(order: List<AppKey>) = copy(keys = order, apps = load.apps?.let { byKey -> order.mapNotNull(byKey::get) })

        companion object {
            /** The apps of [load] in [order]. */
            fun of(load: LoadedPinned, order: List<AppKey>) =
                Pinned(order, load.apps?.let { byKey -> order.mapNotNull(byKey::get) }, load)
        }
    }
    /** The settings the handle is showing; null until first read. */
    private lateinit var settings: StateFlow<DrawerSettings?>
    /** The settings as last read (the handle isn't shown before they are). */
    private val latestSettings: DrawerSettings? get() = settings.value
    /**
     * An order chosen in the drawer and not yet saved (the single place one lives), applied by the
     * drawer over the saved order in the same frame. Cleared once the settings show what was
     * written (or another write lands), or dropped if the save fails. Main thread only.
     */
    private var pendingOrder by mutableStateOf<List<AppKey>?>(null)
    /** Bumped to reload the pinned apps after a failed load (e.g. when a drawer opens). */
    private val pinnedRetry = MutableStateFlow(0)
    /** What app labels and icons depend on; the caches are re-warmed when it changes. */
    private val displayConfig = MutableStateFlow<DrawConfig?>(null)
    /** Recent apps with icons already drawn, so reopening the drawer doesn't redraw them. */
    private val recentIcons = DrawnAppCache(MAX_RECENT_ICONS)
    private var foreground = false
    /** Started through startForegroundService(), so startForeground() is mandatory. */
    private var foregroundRequired = false
    private var retryScheduled = false

    private val appOps by lazy { getSystemService(AppOpsManager::class.java) }
    private val overlayPermissionListener = AppOpsManager.OnOpChangedListener { _, pkg ->
        // Revoking "Display over other apps" doesn't kill us; the windows just vanish.
        if (pkg == packageName) { mainHandler.post { if (!Settings.canDrawOverlays(this)) { stop() } } }
    }

    /**
     * Closes an open drawer on screen-off (it shouldn't greet the user on unlock) and on Home or
     * Recents, which never reach an overlay window as keys and, when the launcher is already in
     * front, don't take the drawer's focus either; the system still announces them this way.
     */
    private val closeDrawerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Closing drawer: ${intent.action} ${intent.getStringExtra(EXTRA_CLOSE_REASON).orEmpty()}")
            // With the screen off no frames run, so an exit animation would only play on unlock.
            if (intent.action == Intent.ACTION_SCREEN_OFF) { host.removeDrawer() } else { host.closeDrawer() }
        }
    }

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = onDisplayChanged(newConfig)

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    override fun onCreate() {
        super.onCreate()
        // After a system restart (START_STICKY) we may lack the foreground-start exemption until
        // the handle is visible, so a failure here is retried once the handle is shown.
        foreground = tryStartForeground()
        overlayContext = createOverlayContext()
        repo = SettingsRepository(this)
        catalog = AppCatalog(this)
        recents = RecentApps(this)
        host = OverlayHost(
            context = overlayContext,
            onOpen = ::openDrawer,
            onHandleDropped = { side, fraction ->
                lifecycleScope.launch { repo.update { it.copy(side = side, yFraction = fraction) } }
            },
            onFatal = ::stop,
        )
        if (overlayContext !== this) { overlayContext.registerComponentCallbacks(configCallbacks) }
        useDrawConfig(DrawConfig.of(overlayContext.resources.configuration, DRAWER_ICON_SIZE))
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, overlayPermissionListener)
        @Suppress("DEPRECATION") // Still delivered for Home/Recents; only sending it is restricted.
        val closeActions = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS) }
        ContextCompat.registerReceiver(this, closeDrawerReceiver, closeActions, ContextCompat.RECEIVER_NOT_EXPORTED)
        // One reading of the settings for everything here (handle, pinned apps, drawer), so they
        // can never disagree about which apps are pinned.
        settings = repo.settings.stateIn(lifecycleScope, SharingStarted.Eagerly, null)
        lifecycleScope.launch {
            settings.filterNotNull().collect { s ->
                if (!s.enabled || !Settings.canDrawOverlays(this@EdgeService)) {
                    stop()
                } else {
                    host.showHandle(s)
                    if (!foreground && !retryScheduled) {
                        retryScheduled = true
                        // The exemption needs the handle actually on screen, i.e. after a frame.
                        mainHandler.postDelayed({
                            foreground = tryStartForeground()
                            // Only a plain (sticky-restart) start can get here: for one through
                            // startForegroundService() the system already allowed the foreground
                            // start, and stopping before startForeground() would crash the app.
                            if (!foreground && !foregroundRequired) { stop() }
                        }, FOREGROUND_RETRY_MS)
                    }
                }
            }
        }
        // Any app change invalidates the cached launcher entries and that app's icons, at once.
        lifecycleScope.launch {
            appsChanged(this@EdgeService).collect { pkg ->
                catalog.invalidate()
                pinnedIcons.onAppChanged(pkg)
                recentIcons.onAppChanged(pkg)
                if (affectsPinned(pkg)) {
                    pinnedAppsChanged.value++
                }
            }
        }
        pinned = pinnedApps().stateIn(lifecycleScope, SharingStarted.Eagerly, null)
    }

    /**
     * The pinned apps as they change: null first on every change (so nothing stale is ever
     * current), then the reloaded list. After a change to the apps themselves it waits for a
     * burst (e.g. many updates) to settle before reloading.
     */
    private fun pinnedApps(): Flow<Pinned?> {
        val keys = settings.filterNotNull().map { it.apps }.distinctUntilChanged()
        // Loaded by the set of pinned apps: a reorder needs no reload (and shows no spinner).
        val loaded = combine(
            keys.map { it.toSet() }.distinctUntilChanged(),
            // Every change empties it at once (a load in flight could be stale); it reloads only
            // once the settled copy catches up, i.e. after a burst of changes has settled.
            pinnedAppsChanged,
            // The first value (no change yet) isn't held back, only changes are.
            pinnedAppsChanged.settled(holdFirst = false),
            displayConfig.filterNotNull(),
            pinnedRetry,
        ) { set, change, settledChange, _, _ -> LoadInputs(set, change, settledChange) }
            .transformLatest { (set, change, settledChange) ->
                emit(null)
                if (change == settledChange) {
                    // Sized to the pinned apps (no LRU limit to thrash), minus any unpinned since.
                    pinnedIcons.retain(set)
                    // Retried with backoff until it works (e.g. LauncherApps not ready after boot).
                    // The failure is reported once, after a quick retry; a drawer that already shows
                    // a list keeps it (marked reloading) meanwhile.
                    var attempt = 0
                    while (true) {
                        val apps = withContext(Dispatchers.Default) { catalog.resolveDrawn(pinnedIcons, set.toList()) }
                        if (apps != null) {
                            emit(LoadedPinned(set, apps.apps.associateBy { it.key }))
                            break
                        }
                        if (attempt == 1) {
                            emit(LoadedPinned(set, null))
                        }
                        delay((LOAD_RETRY_MS shl attempt.coerceAtMost(MAX_RETRY_SHIFT)))
                        attempt++
                    }
                }
            }
        // ...then put in the saved order (a drawer applies any not-yet-saved one on top).
        return combine(keys, loaded) { order, load -> load?.takeIf { it.keys == order.toSet() }?.let { Pinned.of(it, order) } }
            // While reloading, or retrying a failed reload, the last list stays (marked reloading)
            // rather than vanishing, so an open drawer keeps its grid, scroll and any press under way.
            .scan<Pinned?, Pinned?>(null) { last, next ->
                when {
                    next == null -> last?.copy(reloading = true)
                    next.apps == null && last?.apps != null -> last.copy(reloading = true)
                    else -> next
                }
            }
            .distinctUntilChanged()
    }

    private class LoadedPinned(val keys: Set<AppKey>, val apps: Map<AppKey, LaunchableApp>?)

    /** What a pinned-apps load depends on (the display config and retries only trigger one). */
    private data class LoadInputs(val set: Set<AppKey>, val change: Int, val settledChange: Int)

    private fun openDrawer() {
        if (host.isDrawerOpen) {
            return
        }
        // The handle isn't shown before the settings are read.
        if (latestSettings == null) {
            return
        }
        // A failed load is retried whenever someone opens the drawer (e.g. LauncherApps wasn't
        // ready right after boot); meanwhile the drawer shows the spinner, not that failure.
        val failedLoad = pinned.value?.takeIf { it.apps == null }?.load
        if (failedLoad != null) {
            pinnedRetry.value++
        }
        // Recents depend on what was just used, so they're read on every open and fill in when
        // ready; until then the drawer keeps their space if it expects any.
        val expectRecents = recents.hasAccess()
        val recent = mutableStateOf<List<LaunchableApp>?>(null)
        // Everything the drawer shows about the pinned apps (the grid, "no apps" vs "unavailable",
        // what recents leave out) comes from this one value, so it's always consistent. Shown at
        // once; null until loaded (usually it already is).
        // The pinned apps already carry the order (saved, or chosen and being saved), so the
        // drawer shows them exactly as they are, except the failure being retried.
        fun visible(p: Pinned?) = p?.takeIf { it.load !== failedLoad }
        val shownPinned = mutableStateOf(visible(pinned.value))
        // This drawer's work: cancelled when this drawer goes (or fails to show), never by
        // another drawer's callbacks, e.g. one still closing when this one opens.
        val drawerJob = lifecycleScope.launch {
            // Follows the pinned apps exactly while open (marked while reloading, so a removed app
            // can't be launched from the list still shown).
            pinned.collect { p -> shownPinned.value = visible(p) }
        }
        var recentLoad: Job? = null
        val added = host.openDrawer(
            // An order chosen here and still being saved is applied on top, in the same frame.
            apps = derivedStateOf {
                shownPinned.value?.let { p ->
                    val pending = pendingOrder?.takeIf { it.toSet() == p.keys.toSet() }
                    (if (pending != null) { p.inOrder(pending) } else { p }).apps.orEmpty()
                }
            },
            reloading = derivedStateOf { shownPinned.value?.reloading == true },
            hasPinned = derivedStateOf { shownPinned.value?.keys?.isNotEmpty() == true },
            recent = recent,
            expectRecents = expectRecents,
            onLaunch = { app, bounds -> catalog.launch(app, bounds) },
            onReorder = { shownOrder ->
                val saved = latestSettings?.apps
                if (saved != null) {
                    val order = saved.withVisibleOrder(shownOrder)
                    pendingOrder = order
                    lifecycleScope.launch { savePendingOrder(order, shownOrder) }
                }
            },
            onOpenSettings = {
                startActivity(Intent(this@EdgeService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onClosed = { drawerJob.cancel() },
            // Only once the drawer has laid out room for them (none in a short landscape drawer).
            onRecentsNeeded = {
                if (expectRecents && recentLoad?.isActive != true) {
                    // A child of this drawer's job, so it's cancelled with it. Recomputed whenever
                    // the pinned apps change, so recents never repeat or drop one of them.
                    recentLoad = lifecycleScope.launch(drawerJob) {
                        // They leave out the pinned apps (the set in settings: reordering doesn't
                        // change it), and are read again only if that set changes while open.
                        settings.map { it?.apps.orEmpty().toSet() }
                            .distinctUntilChanged()
                            .collectLatest { keys -> recent.value = recentApps(keys.toList()) }
                    }
                }
            },
        )
        if (!added) {
            drawerJob.cancel()
        }
    }

    /** Whether a change to [pkg] (null: several apps or a whole profile) concerns the pinned apps. */
    private fun affectsPinned(pkg: String?): Boolean =
        pkg == null || latestSettings?.apps.orEmpty().any { it.pkg == pkg }

    /**
     * Saves a drawer order (pending meanwhile). Clears it once the settings show what was
     * written, or another write lands first; drops it if the save fails, unless a newer order has
     * replaced it meanwhile.
     */
    private suspend fun savePendingOrder(order: List<AppKey>, shownOrder: List<AppKey>) {
        val before = latestSettings?.apps
        var written: List<AppKey>? = null
        val ok = repo.update { s -> s.copy(apps = s.apps.withVisibleOrder(shownOrder)).also { written = it.apps } }
        val final = written
        if (ok && final != null) {
            if (pendingOrder == order) {
                pendingOrder = final
            }
            // At once if the write changed nothing; otherwise when it (or a later write) arrives.
            withTimeoutOrNull(PENDING_ORDER_SAFETY_MS) { settings.first { it?.apps == final || it?.apps != before } }
            if (pendingOrder == final) {
                pendingOrder = null
            }
        } else if (pendingOrder == order) {
            pendingOrder = null
        }
    }

    private suspend fun recentApps(pinned: List<AppKey>): List<LaunchableApp> = withContext(Dispatchers.Default) {
        val start = SystemClock.elapsedRealtime()
        orOnFailure("recent apps", emptyList()) {
            recentIcons.load { obtain -> catalog.recent(pinned, recents, obtain) }
        }.also { Log.d(TAG, "Recent apps read in ${SystemClock.elapsedRealtime() - start} ms") }
    }

    /**
     * Stops the service. One started through startForegroundService() must have gone foreground
     * first, or the system crashes the app, so that is (re)tried before stopping.
     */
    private fun stop() {
        if (foregroundRequired && !foreground) { foreground = tryStartForeground() }
        stopSelf()
    }

    /**
     * Read from the [Configuration] passed in rather than the overlay context's resources: on
     * Android 11 those callbacks arrive before (or without) the window context's resources updating.
     */
    private fun onDisplayChanged(newConfig: Configuration) {
        host.relayout()
        useDrawConfig(DrawConfig.of(newConfig, DRAWER_ICON_SIZE))
    }

    private fun useDrawConfig(config: DrawConfig) {
        pinnedIcons.use(config)
        recentIcons.use(config)
        displayConfig.value = config
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND_REQUIRED, false) == true) {
            foregroundRequired = true
            if (!foreground) { foreground = tryStartForeground() }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlayContext === this) { onDisplayChanged(newConfig) }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        appOps.stopWatchingMode(overlayPermissionListener)
        unregisterReceiver(closeDrawerReceiver)
        if (overlayContext !== this) { overlayContext.unregisterComponentCallbacks(configCallbacks) }
        host.removeAll()
        super.onDestroy()
    }

    /** Overlay windows get a dedicated window context on API 30+, as the platform recommends. */
    private fun createOverlayContext(): Context {
        // Android 11's window context doesn't get its own configuration callbacks (they go to the
        // application, ahead of its resources updating), so there the service itself is used.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { return this }
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return this
        return createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }

    private fun tryStartForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val openSettings = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentIntent(openSettings)
            .setOngoing(true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is an IllegalStateException.
            Log.w(TAG, "Foreground start not allowed yet", e)
            false
        }
    }

    companion object {
        private const val CHANNEL_ID = "edge_handle"
        private const val NOTIFICATION_ID = 1
        private const val FOREGROUND_RETRY_MS = 500L
        private const val MAX_RECENT_ICONS = 32
        private const val LOAD_RETRY_MS = 1_000L
        /** Only so a stuck settings read can't keep it forever; normally cleared long before. */
        private const val PENDING_ORDER_SAFETY_MS = 30_000L
        private const val MAX_RETRY_SHIFT = 5
        private const val EXTRA_CLOSE_REASON = "reason"

        private const val EXTRA_FOREGROUND_REQUIRED = "foreground_required"

        /** Starts or stops the service to match [enabled] and the overlay permission. */
        fun sync(context: Context, enabled: Boolean) {
            if (enabled && Settings.canDrawOverlays(context)) {
                start(context)
            } else {
                stop(context)
            }
        }

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, EdgeService::class.java).putExtra(EXTRA_FOREGROUND_REQUIRED, true),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EdgeService::class.java))
        }
    }
}
