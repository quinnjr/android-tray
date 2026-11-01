package dev.quinnjr.android_drawer.service

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
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
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.quinnjr.android_drawer.R
import dev.quinnjr.android_drawer.apps.AppCatalog
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.apps.RecentApps
import dev.quinnjr.android_drawer.apps.withBitmaps
import dev.quinnjr.android_drawer.overlay.DRAWER_ICON_SIZE
import dev.quinnjr.android_drawer.apps.appsChanged
import dev.quinnjr.android_drawer.overlay.OverlayHost
import dev.quinnjr.android_drawer.settings.AppKey
import dev.quinnjr.android_drawer.settings.SettingsRepository
import dev.quinnjr.android_drawer.settings.withVisibleOrder
import dev.quinnjr.android_drawer.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
    /** Display density; icons are re-drawn when it changes (e.g. Display size). */
    private val density = MutableStateFlow(0f)
    private var opening = false
    private var recentJob: Job? = null
    private var foreground = false
    private var retryScheduled = false

    private val appOps by lazy { getSystemService(AppOpsManager::class.java) }
    private val overlayPermissionListener = AppOpsManager.OnOpChangedListener { _, pkg ->
        // Revoking "Display over other apps" doesn't kill us; the windows just vanish.
        if (pkg == packageName) mainHandler.post { if (!Settings.canDrawOverlays(this)) stopSelf() }
    }

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = onDisplayChanged()

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
        density.value = overlayContext.resources.displayMetrics.density
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, overlayPermissionListener)
        lifecycleScope.launch {
            repo.settings.collect { s ->
                if (!s.enabled || !Settings.canDrawOverlays(this@EdgeService)) {
                    stopSelf()
                } else {
                    host.showHandle(s)
                    if (!foreground && !retryScheduled) {
                        retryScheduled = true
                        // The exemption needs the handle actually on screen, i.e. after a frame.
                        mainHandler.postDelayed({
                            foreground = tryStartForeground()
                            if (!foreground) stopSelf()
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
                appsChanged(this@EdgeService).onStart { emit(Unit) },
                density,
            ) { keys, _, _ -> keys }
                .mapLatest { keys -> keys to resolveWithIcons(keys.toList()) }
                .collect { (keys, apps) ->
                    if (apps == null) {
                        // Failed: keep what we had; with nothing yet, let the drawer resolve on open.
                        if (resolved.value == null) resolved.value = emptyMap()
                        return@collect
                    }
                    val byKey = apps.associateBy { it.key }
                    unresolvable = keys - byKey.keys
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
        val recent = mutableStateOf<List<LaunchableApp>?>(if (recents.hasAccess()) null else emptyList())
        lifecycleScope.launch {
            val keys: List<AppKey>
            try {
                keys = repo.settings.first().apps
                host.openDrawer(
                    apps = pinnedApps(keys),
                    recent = recent,
                    onLaunch = { app, bounds -> catalog.launch(app, bounds) },
                    onReorder = { shownOrder ->
                        lifecycleScope.launch { repo.update { it.copy(apps = it.apps.withVisibleOrder(shownOrder)) } }
                    },
                    onOpenSettings = {
                        startActivity(Intent(this@EdgeService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                )
            } finally {
                opening = false
            }
            recentJob?.cancel()
            recentJob = lifecycleScope.launch {
                recent.value = withContext(Dispatchers.Default) {
                    val start = SystemClock.elapsedRealtime()
                    runCatching { catalog.recent(keys, recents).withBitmaps(iconSizePx()) }
                        .onFailure { Log.w(TAG, "Could not read recent apps", it) }
                        .getOrDefault(emptyList())
                        .also { Log.d(TAG, "Recent apps read in ${SystemClock.elapsedRealtime() - start} ms") }
                }
            }
        }
    }

    /**
     * [keys] in order, from the cache (waiting for the first background resolve if needed); any
     * it hasn't resolved yet (e.g. just pinned) are resolved now.
     */
    private suspend fun pinnedApps(keys: List<AppKey>): List<LaunchableApp> {
        val cache = resolved.filterNotNull().first()
        val missing = keys.filter { it !in cache && it !in unresolvable }
        val fresh = if (missing.isEmpty()) emptyMap() else resolveWithIcons(missing).orEmpty().associateBy { it.key }
        return keys.mapNotNull { cache[it] ?: fresh[it] }
    }

    /**
     * Resolves [keys] with icons drawn, off the main thread. A failure (e.g. a profile removed
     * mid-resolve) is logged and gives null rather than crashing the service.
     */
    private suspend fun resolveWithIcons(keys: List<AppKey>): List<LaunchableApp>? = withContext(Dispatchers.Default) {
        try {
            catalog.resolve(keys).withBitmaps(iconSizePx())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not resolve pinned apps", e)
            null
        }
    }

    private fun onDisplayChanged() {
        host.relayout()
        density.value = overlayContext.resources.displayMetrics.density
    }

    private fun iconSizePx(): Int = (DRAWER_ICON_SIZE.value * overlayContext.resources.displayMetrics.density).roundToInt()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlayContext === this) onDisplayChanged()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        appOps.stopWatchingMode(overlayPermissionListener)
        if (overlayContext !== this) overlayContext.unregisterComponentCallbacks(configCallbacks)
        host.removeAll()
        super.onDestroy()
    }

    /** Overlay windows get a dedicated window context on API 30+, as the platform recommends. */
    private fun createOverlayContext(): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return this
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
        private const val TAG = "EdgeDrawer"
        private const val CHANNEL_ID = "edge_handle"
        private const val NOTIFICATION_ID = 1
        private const val FOREGROUND_RETRY_MS = 500L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EdgeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EdgeService::class.java))
        }
    }
}
