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
import dev.quinnjr.android_drawer.apps.APPS_CHANGED_SETTLE_MS
import dev.quinnjr.android_drawer.apps.AppCatalog
import dev.quinnjr.android_drawer.apps.DrawConfig
import dev.quinnjr.android_drawer.apps.DrawnAppCache
import dev.quinnjr.android_drawer.apps.LaunchResult
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.apps.RecentApps
import dev.quinnjr.android_drawer.apps.Resolved
import dev.quinnjr.android_drawer.apps.appsChanged
import dev.quinnjr.android_drawer.apps.orOnFailure
import dev.quinnjr.android_drawer.apps.resolveDrawn
import dev.quinnjr.android_drawer.overlay.DRAWER_ICON_SIZE
import dev.quinnjr.android_drawer.overlay.OverlayHost
import dev.quinnjr.android_drawer.overlay.PinnedStatus
import dev.quinnjr.android_drawer.settings.AppKey
import dev.quinnjr.android_drawer.settings.DrawerSettings
import dev.quinnjr.android_drawer.settings.SettingsRepository
import dev.quinnjr.android_drawer.settings.arrangedBy
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
     * from everything they depend on (pinned keys, changes to those apps, the display config).
     * While a reload runs, or after it failed, the last list is kept and marked as such (launching
     * from it is safe: a vanished app just isn't started). Null until the first load.
     */
    private lateinit var pinned: StateFlow<Pinned?>
    /** Bumped at once on any change to a pinned app (or to many/all apps), before any debounce. */
    private val pinnedAppsChangedAt = MutableStateFlow(0L)

    /**
     * The pinned [keys] in their saved order and the [apps] for them (null if loading failed), from
     * [kept]: the latest load, or the last good one while reloading or after a failure (see keep()).
     */
    private data class Pinned(val keys: List<AppKey>, val kept: KeptLoad<LaunchableApp>) {
        val apps: List<LaunchableApp>? by lazy { kept.load.ordered(keys) }

        /** How current these are, given the retries asked for so far (the one place it's worked out). */
        fun status(retryRequested: Int) = kept.status(keys.toSet(), retryRequested)
    }
    /** The settings the handle is showing; null until first read. */
    private lateinit var settings: StateFlow<DrawerSettings?>
    /** The settings as last read (the handle isn't shown before they are). */
    private val latestSettings: DrawerSettings? get() = settings.value
    /** The last order chosen in the drawer (see [PendingOrder]). Main thread only. */
    private var pendingOrder by mutableStateOf<PendingOrder?>(null)
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
                    pinnedAppsChangedAt.value = SystemClock.elapsedRealtime()
                }
            }
        }
        pinned = pinnedApps().stateIn(lifecycleScope, SharingStarted.Eagerly, null)
    }

    /**
     * The pinned apps as they change: on every change the last list is kept, marked reloading,
     * until the reload lands. After a change to the apps themselves it waits for a burst (e.g. many
     * updates) to settle before reloading.
     */
    private fun pinnedApps(): Flow<Pinned?> {
        val keys = settings.filterNotNull().map { it.apps }.distinctUntilChanged()
        // Loaded by the set of pinned apps: a reorder needs no reload (and shows no spinner).
        val loaded: Flow<LoadedPinned<LaunchableApp>?> = combine(
            keys.map { it.toSet() }.distinctUntilChanged(),
            pinnedAppsChangedAt,
            displayConfig.filterNotNull(),
            pinnedRetry,
        ) { set, changedAt, config, retry -> LoadInputs(set, changedAt, config, retry) }
            .transformLatest { inputs ->
                val set = inputs.set
                // Every change marks a reload at once; it loads once the last app change has
                // settled (a burst, e.g. many updates, loads once), whatever else triggered it.
                emit(null)
                delay(inputs.settleDelay(SystemClock.elapsedRealtime(), APPS_CHANGED_SETTLE_MS))
                // Sized to the pinned apps (no LRU limit to thrash), minus any unpinned since.
                pinnedIcons.retain(set)
                // A few attempts with backoff (e.g. LauncherApps not ready after boot), then the
                // failure is reported and it stops; opening a drawer asks for another go. Not
                // forever: this service runs all the time, mostly with nobody looking.
                var apps: Resolved? = null
                for (attempt in 0 until LOAD_ATTEMPTS) {
                    if (attempt > 0) {
                        delay(LOAD_RETRY_MS shl (attempt - 1))
                    }
                    apps = withContext(Dispatchers.Default) { catalog.resolveDrawn(pinnedIcons, set.toList()) }
                    if (apps != null) {
                        break
                    }
                }
                emit(LoadedPinned(set, apps?.apps?.associateBy { it.key }, inputs.retry))
            }
        // While reloading, or after a reload failed, the last good load is kept (so an open drawer
        // keeps its grid, scroll and any press under way); the current saved order is applied after,
        // so a kept load still shows a reorder saved meanwhile and leaves out apps unpinned since.
        val kept = loaded.scan<LoadedPinned<LaunchableApp>?, KeptLoad<LaunchableApp>?>(null) { last, next -> last.keep(next) }
        // The status reflects the current keys and any retry asked for, so the drawer never shows a
        // load for another set, or a failure being retried, as current.
        return combine(keys, kept) { order, k -> k?.let { Pinned(order, it) } }.distinctUntilChanged()
    }

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
        // (Not while a load is already running: that would only restart it.)
        if (pinned.value?.status(pinnedRetry.value) == PinnedStatus.FAILED) {
            pinnedRetry.value++
        }
        val retryAsked = pinnedRetry.value
        // Recents depend on what was just used, so they're read on every open and fill in when
        // ready; until then the drawer keeps their space if it expects any.
        val expectRecents = recents.hasAccess()
        val recent = mutableStateOf<List<LaunchableApp>?>(null)
        // What the drawer shows about the pinned apps (the grid, its status, "no apps" vs
        // "unavailable") comes from this one value, shown at once (null until loaded; usually it
        // already is). Its status counts a retry just asked for as reloading.
        val shownPinned = mutableStateOf(pinned.value)
        // A short message in the drawer (e.g. an app that couldn't be started), and a trigger to read
        // recents again.
        val notice = mutableStateOf<String?>(null)
        val recentRefresh = MutableStateFlow(0)
        // This drawer's work: cancelled when this drawer goes (or fails to show), never by
        // another drawer's callbacks, e.g. one still closing when this one opens.
        val drawerJob = lifecycleScope.launch {
            // Follows the pinned apps exactly while open (marked while reloading, so a removed app
            // can't be launched from the list still shown).
            pinned.collect { p -> shownPinned.value = p }
        }
        var recentLoad: Job? = null
        val added = host.openDrawer(
            // An order chosen here is applied on top in the same frame, while it still applies (also
            // over a list kept from before the pinned set changed: its apps in that order, others after).
            apps = derivedStateOf {
                shownPinned.value?.let { p ->
                    val apps = p.apps.orEmpty()
                    pendingOrder?.takeIf { it.appliesTo(latestSettings?.apps) }
                        ?.let { pending -> apps.arrangedBy(pending.order) { it.key } }
                        ?: apps
                }
            },
            // Counting the retry just asked for, so it shows as reloading at once.
            status = derivedStateOf { shownPinned.value?.status(retryAsked) ?: PinnedStatus.RELOADING },
            hasPinned = derivedStateOf { shownPinned.value?.keys?.isNotEmpty() == true },
            notice = notice,
            recent = recent,
            expectRecents = expectRecents,
            // False if the app couldn't be started: say so in the drawer (a toast would sit behind
            // it). If it's gone (e.g. uninstalled while the list reloaded), refresh the list it was in
            // now rather than wait for the change to be noticed; a paused profile needs no refresh.
            onLaunch = { app, bounds ->
                val result = catalog.launch(app, bounds)
                if (result != LaunchResult.STARTED) {
                    val text = getString(R.string.app_unavailable, app.label)
                    notice.value = text
                    lifecycleScope.launch(drawerJob) {
                        delay(NOTICE_MS)
                        if (notice.value == text) {
                            notice.value = null
                        }
                    }
                }
                if (result == LaunchResult.GONE) {
                    if (app.key in shownPinned.value?.keys.orEmpty()) {
                        pinnedAppsChangedAt.value = SystemClock.elapsedRealtime()
                    } else {
                        recentRefresh.value++
                    }
                }
                result == LaunchResult.STARTED
            },
            onReorder = { shownOrder ->
                val saved = latestSettings?.apps
                if (saved != null) {
                    val pending = PendingOrder(base = saved, order = saved.withVisibleOrder(shownOrder))
                    pendingOrder = pending
                    lifecycleScope.launch { savePendingOrder(pending, shownOrder) }
                }
            },
            onOpenSettings = {
                startActivity(Intent(this@EdgeService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onClosed = { drawerJob.cancel() },
            // Only once the drawer has laid out room for them (none in a short landscape drawer).
            onRecentsNeeded = {
                if (expectRecents && recentLoad?.isActive != true) {
                    // A child of this drawer's job, so it's cancelled with it.
                    recentLoad = lifecycleScope.launch(drawerJob) {
                        // They leave out the pinned apps (the set in settings: one read per change,
                        // reordering doesn't count), and are read again if one of them turned out gone.
                        combine(settings.map { it?.apps.orEmpty().toSet() }.distinctUntilChanged(), recentRefresh) { keys, _ -> keys }
                            .collectLatest { keys -> recent.value = recentApps(keys.toList()) }
                    }
                }
            },
        )
        if (!added) {
            // E.g. the window couldn't be added: reset the host, so the handle comes back and the
            // drawer can be opened again (this also cancels the drawer's work).
            host.removeDrawer()
            drawerJob.cancel()
        }
    }

    /** Whether a change to [pkg] (null: several apps or a whole profile) concerns the pinned apps. */
    private fun affectsPinned(pkg: String?): Boolean =
        pkg == null || latestSettings?.apps.orEmpty().any { it.pkg == pkg }

    /**
     * Saves a drawer order. It needs no clearing once saved (it then matches the settings, and is
     * ignored if they change otherwise): only a failed save drops it. If the write produced a
     * different order (the apps changed meanwhile), that is what's shown.
     */
    private suspend fun savePendingOrder(pending: PendingOrder, shownOrder: List<AppKey>) {
        var written: List<AppKey>? = null
        val ok = repo.update { s -> s.copy(apps = s.apps.withVisibleOrder(shownOrder)).also { written = it.apps } }
        if (pendingOrder != pending) {
            return
        }
        val final = written
        pendingOrder = if (ok && final != null) { pending.copy(order = final) } else { null }
    }

    /**
     * An order chosen in the drawer over the saved order [base]. It applies while the settings still
     * show [base] (not saved yet) or [order] (saved; the pinned apps catching up), so it never needs
     * clearing in time: once the settings show anything else (another write), it no longer applies.
     */
    private data class PendingOrder(val base: List<AppKey>, val order: List<AppKey>) {
        fun appliesTo(saved: List<AppKey>?) = saved == base || saved == order
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
        /** First retry delay for a failed pinned-apps load; doubled for each further one. */
        private const val LOAD_RETRY_MS = 1_000L
        /** Pinned-apps load attempts (1s, 2s, 4s apart) before reporting failure and stopping. */
        private const val LOAD_ATTEMPTS = 4
        private const val NOTICE_MS = 2_500L
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
