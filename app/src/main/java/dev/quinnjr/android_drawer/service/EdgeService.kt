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
import dev.quinnjr.android_drawer.apps.AppCatalog
import dev.quinnjr.android_drawer.apps.DrawConfig
import dev.quinnjr.android_drawer.apps.DrawnAppCache
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.apps.RecentApps
import dev.quinnjr.android_drawer.apps.appChanges
import dev.quinnjr.android_drawer.apps.withBitmap
import dev.quinnjr.android_drawer.overlay.DRAWER_ICON_SIZE
import dev.quinnjr.android_drawer.overlay.OverlayHost
import dev.quinnjr.android_drawer.settings.AppKey
import dev.quinnjr.android_drawer.settings.SettingsRepository
import dev.quinnjr.android_drawer.settings.withVisibleOrder
import dev.quinnjr.android_drawer.ui.MainActivity
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
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

    /**
     * Pinned apps resolved ahead of time, icons already drawn, by key; null until the first resolve.
     * The order always comes from settings when the drawer opens, so it can never be stale.
     */
    private val resolved = MutableStateFlow<Map<AppKey, LaunchableApp>?>(null)
    /** Pinned keys the last resolve couldn't find (e.g. a paused work profile's apps). */
    private var unresolvable: Set<AppKey> = emptySet()
    private var unresolvableSince = 0L
    /** What app labels and icons depend on; they're reloaded when it changes. */
    private val displayConfig = MutableStateFlow<DrawConfig?>(null)
    private var opening = false
    private var recentJob: Job? = null
    /** The drawer being prepared (resolving pinned apps) before it is shown. */
    private var openJob: Job? = null
    /** Recent apps with icons already drawn, so reopening the drawer doesn't redraw them. */
    private val recentIcons = DrawnAppCache(MAX_RECENT_ICONS)
    /** Packages of pinned apps: changes to other apps don't need the pinned apps re-resolved. */
    @Volatile private var pinnedPackages: Set<String> = emptySet()
    /** True while the background resolve is working on a newer set of pinned apps. */
    private val resolving = MutableStateFlow(false)
    private var foreground = false
    /** Started through startForegroundService(), so startForeground() is mandatory. */
    private var foregroundRequired = false
    private var retryScheduled = false

    private val appOps by lazy { getSystemService(AppOpsManager::class.java) }
    private val overlayPermissionListener = AppOpsManager.OnOpChangedListener { _, pkg ->
        // Revoking "Display over other apps" doesn't kill us; the windows just vanish.
        if (pkg == packageName) mainHandler.post { if (!Settings.canDrawOverlays(this)) stopSelf() }
    }

    /**
     * Closes an open drawer on screen-off (it shouldn't greet the user on unlock) and on Home or
     * Recents, which never reach an overlay window as keys and, when the launcher is already in
     * front, don't take the drawer's focus either; the system still announces them this way.
     */
    private val closeDrawerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Closing drawer: ${intent.action} ${intent.getStringExtra(EXTRA_CLOSE_REASON).orEmpty()}")
            // A drawer still being prepared must not appear after this.
            openJob?.cancel()
            // With the screen off no frames run, so an exit animation would only play on unlock.
            if (intent.action == Intent.ACTION_SCREEN_OFF) host.removeDrawerNow() else host.closeDrawer()
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
            onFatal = ::stopSelf,
        )
        if (overlayContext !== this) overlayContext.registerComponentCallbacks(configCallbacks)
        displayConfig.value = DrawConfig.of(overlayContext.resources.configuration, DRAWER_ICON_SIZE)
            .also(recentIcons::use)
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, overlayPermissionListener)
        @Suppress("DEPRECATION") // Still delivered for Home/Recents; only sending it is restricted.
        val closeActions = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS) }
        ContextCompat.registerReceiver(this, closeDrawerReceiver, closeActions, ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleScope.launch {
            repo.settings.collect { s ->
                pinnedPackages = s.apps.mapTo(HashSet()) { it.component.substringBefore('/') }
                if (!s.enabled || !Settings.canDrawOverlays(this@EdgeService)) {
                    stopSelf()
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
                            if (!foreground && !foregroundRequired) stopSelf()
                        }, FOREGROUND_RETRY_MS)
                    }
                }
            }
        }
        // Resolve icons off the main thread ahead of time so the drawer opens instantly. Keyed by
        // the set of pinned apps: reordering needs no new resolve.
        lifecycleScope.launch {
            combine(
                repo.settings.map { it.apps.toSet() }.distinctUntilChanged(),
                // Any app change invalidates the cached launcher entries and that app's recent icon;
                // only changes to pinned apps (or to many/all at once) re-resolve the pinned apps.
                appChanges(
                    this@EdgeService,
                    relevant = { pkg -> pkg == null || pkg in pinnedPackages },
                    onChange = { pkg ->
                        catalog.invalidate()
                        recentIcons.onAppChanged(pkg)
                    },
                ),
                displayConfig,
            ) { keys, _, _ -> keys }
                .onEach { resolving.value = true }
                .mapLatest { keys -> keys to resolveWithIcons(keys.toList()) }
                .collect { (keys, apps) ->
                    resolving.value = false
                    if (apps == null) {
                        // Failed: keep what we had; with nothing yet, let the drawer resolve on open.
                        if (resolved.value == null) resolved.value = emptyMap()
                        return@collect
                    }
                    val byKey = apps.associateBy { it.key }
                    unresolvable = keys - byKey.keys
                    unresolvableSince = SystemClock.elapsedRealtime()
                    resolved.value = byKey
                }
        }
    }

    private fun openDrawer() {
        // Further swipes while the drawer is being prepared would only redo the same work.
        if (opening || host.isDrawerOpen) return
        opening = true
        // Recents depend on what was just used, so they're read on every open and fill in when
        // ready; until then the drawer keeps their space if it expects any.
        val expectRecents = recents.hasAccess()
        val recent = mutableStateOf<List<LaunchableApp>?>(null)
        openJob = lifecycleScope.launch {
            val keys: List<AppKey>
            try {
                keys = repo.settings.first().apps
                host.openDrawer(
                    apps = pinnedApps(keys),
                    recent = recent,
                    expectRecents = expectRecents,
                    onLaunch = { app, bounds -> catalog.launch(app, bounds) },
                    onReorder = { shownOrder ->
                        lifecycleScope.launch { repo.update { it.copy(apps = it.apps.withVisibleOrder(shownOrder)) } }
                    },
                    onOpenSettings = {
                        startActivity(Intent(this@EdgeService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    // Nobody will see the recents of a closed drawer.
                    onClosed = { recentJob?.cancel() },
                )
            } finally {
                opening = false
            }
            recentJob?.cancel()
            if (!expectRecents) return@launch
            recentJob = lifecycleScope.launch {
                recent.value = withContext(Dispatchers.Default) {
                    val start = SystemClock.elapsedRealtime()
                    // Taken before anything is read, so icons read before a clear are never stored after it.
                    val generation = recentIcons.generation()
                    try {
                        catalog.recent(keys, recents, cached = { recentIcons[it] })
                            .map { ensureActive(); recentIcons.draw(it, iconSizePx(), generation) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not read recent apps", e)
                        emptyList()
                    }.also { Log.d(TAG, "Recent apps read in ${SystemClock.elapsedRealtime() - start} ms") }
                }
            }
        }
    }

    /**
     * [keys] in order, from the cache (waiting for the first background resolve if needed); any
     * it hasn't resolved yet (e.g. just pinned) are resolved now.
     */
    private suspend fun pinnedApps(keys: List<AppKey>): List<LaunchableApp> {
        // A background resolve of newly pinned apps is likely almost done: wait for it (briefly)
        // rather than resolving the same apps a second time.
        withTimeoutOrNull(RESOLVE_WAIT_MS) { resolving.first { !it } }
        val cache = resolved.filterNotNull().first()
        // Keys that couldn't be resolved (e.g. a paused work profile) are retried now and then,
        // in case the event that should have brought them back was missed or raced.
        val retry = SystemClock.elapsedRealtime() - unresolvableSince > UNRESOLVABLE_RETRY_MS
        val missing = keys.filter { it !in cache && (retry || it !in unresolvable) }
        if (missing.isEmpty()) return keys.mapNotNull { cache[it] }
        val fresh = resolveWithIcons(missing).orEmpty().associateBy { it.key }
        if (retry) {
            unresolvable = unresolvable - fresh.keys
            unresolvableSince = SystemClock.elapsedRealtime()
        }
        // Keep what was just resolved, so the next open doesn't resolve it again; anything the
        // background resolve published meanwhile is newer and wins.
        if (fresh.isNotEmpty()) resolved.update { current -> fresh + current.orEmpty() }
        return keys.mapNotNull { cache[it] ?: fresh[it] }
    }

    /**
     * Resolves [keys] with icons drawn, off the main thread. A failure (e.g. a profile removed
     * mid-resolve) is logged and gives null rather than crashing the service.
     */
    private suspend fun resolveWithIcons(keys: List<AppKey>): List<LaunchableApp>? = withContext(Dispatchers.Default) {
        try {
            val px = iconSizePx()
            // Check for cancellation between icons, so a superseded resolve stops early.
            catalog.resolve(keys).map { ensureActive(); it.withBitmap(px) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not resolve pinned apps", e)
            null
        }
    }

    /**
     * Read from the [Configuration] passed in rather than the overlay context's resources: on
     * Android 11 those callbacks arrive before (or without) the window context's resources updating.
     */
    private fun onDisplayChanged(newConfig: Configuration) {
        host.relayout()
        val config = DrawConfig.of(newConfig, DRAWER_ICON_SIZE)
        recentIcons.use(config)
        displayConfig.value = config
    }

    private fun iconSizePx(): Int = displayConfig.value?.iconSizePx
        ?: (DRAWER_ICON_SIZE.value * overlayContext.resources.displayMetrics.density).roundToInt()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND_REQUIRED, false) == true) {
            foregroundRequired = true
            if (!foreground) foreground = tryStartForeground()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlayContext === this) onDisplayChanged(newConfig)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        appOps.stopWatchingMode(overlayPermissionListener)
        unregisterReceiver(closeDrawerReceiver)
        if (overlayContext !== this) overlayContext.unregisterComponentCallbacks(configCallbacks)
        host.removeAll()
        super.onDestroy()
    }

    /** Overlay windows get a dedicated window context on API 30+, as the platform recommends. */
    private fun createOverlayContext(): Context {
        // Android 11's window context doesn't get its own configuration callbacks (they go to the
        // application, ahead of its resources updating), so there the service itself is used.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
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
        private const val UNRESOLVABLE_RETRY_MS = 30_000L
        private const val RESOLVE_WAIT_MS = 1_000L
        private const val EXTRA_CLOSE_REASON = "reason"

        private const val EXTRA_FOREGROUND_REQUIRED = "foreground_required"

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
