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
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.quinnjr.android_drawer.R
import dev.quinnjr.android_drawer.TAG
import dev.quinnjr.android_drawer.apps.APPS_CHANGED_DEBOUNCE_MS
import dev.quinnjr.android_drawer.apps.AppCatalog
import dev.quinnjr.android_drawer.apps.DrawConfig
import dev.quinnjr.android_drawer.apps.DrawnAppCache
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.apps.RecentApps
import dev.quinnjr.android_drawer.apps.appsChanged
import dev.quinnjr.android_drawer.apps.orOnFailure
import dev.quinnjr.android_drawer.apps.resolveDrawn
import dev.quinnjr.android_drawer.overlay.DRAWER_ICON_SIZE
import dev.quinnjr.android_drawer.overlay.OverlayHost
import dev.quinnjr.android_drawer.settings.AppKey
import dev.quinnjr.android_drawer.settings.DrawerSettings
import dev.quinnjr.android_drawer.settings.SettingsRepository
import dev.quinnjr.android_drawer.settings.withVisibleOrder
import dev.quinnjr.android_drawer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** Pinned [apps] loaded for [keys]; [apps] null if loading failed. */
    private class Pinned(val keys: List<AppKey>, val apps: List<LaunchableApp>?)
    /** The settings the handle is showing; null until first read. */
    @Volatile private var latestSettings: DrawerSettings? = null
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
        lifecycleScope.launch {
            repo.settings.collect { s ->
                latestSettings = s
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
    private fun pinnedApps(): Flow<Pinned?> = flow {
        // Not Flow.settled(): every change must cancel a load in flight at once (its result could be
        // stale), and only the reload waits for the burst to settle. Per-collection state.
        var settledChange = pinnedAppsChanged.value
        emitAll(
            combine(
                repo.settings.map { it.apps }.distinctUntilChanged(),
                pinnedAppsChanged,
                displayConfig.filterNotNull(),
            ) { keys, change, _ -> keys to change }
                .transformLatest { (keys, change) ->
                    emit(null)
                    if (change != settledChange) {
                        delay(APPS_CHANGED_DEBOUNCE_MS)
                        settledChange = change
                    }
                    // Sized to the pinned apps (no LRU limit to thrash), minus any unpinned since.
                    pinnedIcons.retain(keys.toSet())
                    emit(Pinned(keys, withContext(Dispatchers.Default) { catalog.resolveDrawn(pinnedIcons, keys)?.apps }))
                },
        )
    }

    private fun openDrawer() {
        if (host.isDrawerOpen) {
            return
        }
        // The settings the handle shows (it isn't shown before they're read).
        val keys = latestSettings?.apps ?: return
        // Recents depend on what was just used, so they're read on every open and fill in when
        // ready; until then the drawer keeps their space if it expects any.
        val expectRecents = recents.hasAccess()
        val recent = mutableStateOf<List<LaunchableApp>?>(null)
        // Shown at once; the pinned apps fill in when loaded (usually they already are).
        val apps = mutableStateOf(pinned.value?.apps)
        // This drawer's own work, cancelled when this drawer goes (or fails to show) and never by
        // another drawer's callbacks, e.g. one still closing when this one opens.
        val drawerScope = CoroutineScope(lifecycleScope.coroutineContext + SupervisorJob(lifecycleScope.coroutineContext.job))
        var recentLoad: Job? = null
        drawerScope.launch {
            // Follows the pinned apps for as long as it's open: whatever the latest settings are,
            // and updated when an app or the display changes.
            pinned.filterNotNull().collect { loaded ->
                // A failed background load is retried here.
                apps.value = loaded.apps
                    ?: withContext(Dispatchers.Default) { catalog.resolveDrawn(pinnedIcons, loaded.keys)?.apps }.orEmpty()
            }
        }
        val shown = host.openDrawer(
            apps = apps,
            hasPinned = keys.isNotEmpty(),
            recent = recent,
            expectRecents = expectRecents,
            onLaunch = { app, bounds -> catalog.launch(app, bounds) },
            onReorder = { shownOrder ->
                lifecycleScope.launch { repo.update { it.copy(apps = it.apps.withVisibleOrder(shownOrder)) } }
            },
            onOpenSettings = {
                startActivity(Intent(this@EdgeService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onClosed = { drawerScope.cancel() },
            // Only once the drawer has laid out room for them (none in a short landscape drawer).
            onRecentsNeeded = {
                if (expectRecents && recent.value == null && recentLoad?.isActive != true) {
                    recentLoad = drawerScope.launch { recent.value = recentApps(keys) }
                }
            },
        )
        if (!shown) {
            drawerScope.cancel()
        }
    }

    /** Whether a change to [pkg] (null: several apps or a whole profile) concerns the pinned apps. */
    private fun affectsPinned(pkg: String?): Boolean =
        pkg == null || latestSettings?.apps.orEmpty().any { it.pkg == pkg }

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
