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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /** Drawer contents, resolved ahead of time; null until the first resolve finishes. */
    private var drawerApps: List<LaunchableApp>? = null
    private var drawerKeys: List<AppKey>? = null
    private var opening = false
    /** An order saved from the drawer that the settings flow hasn't caught up with yet. */
    private var pendingKeys: List<AppKey>? = null
    private var foreground = false
    private var retryScheduled = false

    private val appOps by lazy { getSystemService(AppOpsManager::class.java) }
    private val overlayPermissionListener = AppOpsManager.OnOpChangedListener { _, pkg ->
        // Revoking "Display over other apps" doesn't kill us; the windows just vanish.
        if (pkg == packageName) mainHandler.post { if (!Settings.canDrawOverlays(this)) stopSelf() }
    }

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = host.relayout()

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
        // Resolve icons off the main thread ahead of time so the drawer opens instantly.
        lifecycleScope.launch {
            combine(
                repo.settings.map { it.apps }.distinctUntilChanged(),
                appsChanged(this@EdgeService).onStart { emit(Unit) },
            ) { keys, _ -> keys }
                .mapLatest { keys -> keys to withContext(Dispatchers.Default) { catalog.resolve(keys).withBitmaps(iconSizePx()) } }
                .collect { (keys, apps) ->
                    // A resolve that started before a drawer reorder would put the old order back.
                    val pending = pendingKeys
                    if (pending != null && keys != pending) return@collect
                    drawerKeys = keys
                    drawerApps = apps
                }
        }
    }

    private fun openDrawer() {
        // Further swipes while the drawer is being prepared would only redo the same work.
        if (opening || host.isDrawerOpen) return
        opening = true
        lifecycleScope.launch {
            try {
                showDrawer()
            } finally {
                opening = false
            }
        }
    }

    private suspend fun showDrawer() {
        val pinnedKeys = drawerKeys ?: repo.settings.first().apps
        // Opened before the first resolve finished: resolve now rather than show "No apps yet".
        val apps = drawerApps ?: withContext(Dispatchers.Default) { catalog.resolve(pinnedKeys).withBitmaps(iconSizePx()) }
        val recent = mutableStateOf(emptyList<LaunchableApp>())
        host.openDrawer(
            apps = apps,
            recent = recent,
            onLaunch = { app, bounds -> catalog.launch(app, bounds) },
            onReorder = ::saveDrawerOrder,
            onOpenSettings = {
                startActivity(Intent(this@EdgeService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
        // Recents depend on what was just used, so they're read on every open and slot in when ready.
        recent.value = withContext(Dispatchers.Default) {
            val start = SystemClock.elapsedRealtime()
            runCatching { catalog.recent(pinnedKeys, recents).withBitmaps(iconSizePx()) }
                .onFailure { Log.w(TAG, "Could not read recent apps", it) }
                .getOrDefault(emptyList())
                .also { Log.d(TAG, "Recent apps read in ${SystemClock.elapsedRealtime() - start} ms") }
        }
    }

    /**
     * Saves an order chosen in the drawer, and applies it to the cached drawer contents at once so
     * a drawer reopened before the save lands doesn't show (and then re-save) the old order.
     */
    private fun saveDrawerOrder(visibleOrder: List<AppKey>) {
        val keys = drawerKeys
        val shown = drawerApps
        if (keys != null && shown != null) {
            val reordered = keys.withVisibleOrder(visibleOrder)
            val byKey = shown.associateBy { it.key }
            drawerKeys = reordered
            drawerApps = reordered.mapNotNull { byKey[it] }
            pendingKeys = reordered
        }
        lifecycleScope.launch {
            try {
                repo.update { it.copy(apps = it.apps.withVisibleOrder(visibleOrder)) }
            } finally {
                pendingKeys = null
            }
        }
    }

    private fun iconSizePx(): Int = (DRAWER_ICON_SIZE.value * overlayContext.resources.displayMetrics.density).roundToInt()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlayContext === this) host.relayout()
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
