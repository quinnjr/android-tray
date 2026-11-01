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
import android.widget.Toast
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
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
    private val pinned = MutableStateFlow<Pinned?>(null)
    /**
     * The pinned apps' keys in their saved order (null until the settings are read): one shared
     * reading for the pinned apps, the drawer's order and recents.
     */
    private lateinit var pinnedKeys: StateFlow<List<AppKey>?>
    /** Bumped at once on any change to a pinned app (or to many/all apps), before any debounce. */
    private val pinnedAppsChanges = MutableStateFlow(0)

    /**
     * The pinned [keys] in their saved order and [kept]: the latest load for them, or the last good
     * one while reloading or after a failure (see keep()).
     */
    private data class Pinned(val keys: List<AppKey>, val kept: KeptLoad<LaunchableApp>) {
        /** How current these are, given the retries asked for (the one place it's worked out). */
        fun status(retryAsked: Int): PinnedStatus = kept.status(keys.toSet(), retryAsked)
    }
    /**
     * The settings as last read (the handle isn't shown before they are). Everything here (handle,
     * pinned apps, drawer) reads the one repository state, so they never disagree about which apps
     * are pinned.
     */
    private val latestSettings: DrawerSettings? get() = repo.state.value
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
        repo = SettingsRepository.get(this)
        catalog = AppCatalog(this)
        recents = RecentApps(this)
        host = OverlayHost(
            context = overlayContext,
            onOpen = ::openDrawer,
            onHandleDropped = { side, fraction ->
                lifecycleScope.launch {
                    // The handle goes back where it was (put there directly: the undo may never show
                    // in the settings flow); say why (no drawer is open to show it in).
                    if (!repo.update { it.copy(side = side, yFraction = fraction) }) {
                        latestSettings?.let(host::showHandle)
                        toastSaveFailed()
                    }
                }
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
            appsChanged(this@EdgeService).collect(::onAppChanged)
        }
        pinnedKeys = repo.state.map { it?.apps }.distinctUntilChanged()
            .stateIn(lifecycleScope, SharingStarted.Eagerly, repo.state.value?.apps)
        lifecycleScope.launch { pinnedApps().collect { pinned.value = it } }
    }

    /**
     * The pinned apps as they change: on every change the last list is kept, marked reloading,
     * until the reload lands. After a change to the apps themselves it waits for a burst (e.g. many
     * updates) to settle before reloading.
     */
    private fun pinnedApps(): Flow<Pinned?> {
        val keys = pinnedKeys.filterNotNull()
        // Loaded by the set of pinned apps: a reorder needs no reload (and shows no spinner).
        // The app changes the last load waited for (see below).
        var settledChanges = 0
        val loaded: Flow<LoadedPinned<LaunchableApp>?> = combine(
            keys.map { it.toSet() }.distinctUntilChanged(),
            pinnedAppsChanges,
            displayConfig.filterNotNull(),
            pinnedRetry,
        ) { set, changes, _, retry -> Triple(set, changes, retry) }
            .transformLatest { (set, changes, retry) ->
                // Every trigger marks the reload at once (e.g. a retry asked for by opening the
                // drawer). After app changes it waits for them to settle: each new one restarts
                // this, so a burst (e.g. many updates) loads once, whatever else triggers it.
                emit(null)
                if (changes != settledChanges) {
                    delay(APPS_CHANGED_SETTLE_MS)
                    settledChanges = changes
                }
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
                    // Retried here only if it failed outright. Apps that couldn't be listed keep
                    // their earlier copy and count as failed, so opening the drawer tries them
                    // again (once: see keep()).
                    if (apps != null) {
                        break
                    }
                }
                emit(LoadedPinned(set, apps?.apps?.associateBy { it.key }, unknown = apps?.unknown.orEmpty(), retry))
            }
        // While reloading, or after a reload failed, the last good load is kept (so an open drawer
        // keeps its grid, scroll and any press under way); the current saved order is applied after,
        // so a kept load still shows a reorder saved meanwhile and leaves out apps unpinned since.
        val kept = loaded.scan<LoadedPinned<LaunchableApp>?, KeptLoad<LaunchableApp>?>(null) { last, next -> last.keep(next) }
        // The status reflects the current keys (and, in the drawer, any retry asked for), so it never
        // shows a load for another set, or a failure being retried, as current.
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
        // ready right after boot); the reload it starts shows as such. (Not while a load is
        // already running: that would only restart it.)
        if (pinned.value?.status(pinnedRetry.value) == PinnedStatus.FAILED) {
            pinnedRetry.value++
        }
        // Until that retry lands, the failure shows as reloading.
        val retryAsked = pinnedRetry.value
        // Recents depend on what was just used, so they're read on every open and fill in when
        // ready; until then the drawer keeps their space if it expects any.
        val expectRecents = recents.hasAccess()
        val recent = mutableStateOf<List<LaunchableApp>?>(null)
        // What the drawer shows about the pinned apps (the grid, its status, "no apps" vs
        // "unavailable") comes from this one value, shown at once (null until loaded; usually it
        // already is).
        val shownPinned = mutableStateOf(pinned.value)
        // Their order: the settings' order, mirrored here as Compose state so a drag's new order
        // (applied to the settings at once) shows in the same frame.
        val order = mutableStateOf(latestSettings?.apps.orEmpty())
        // A short message in the drawer (e.g. an app that couldn't be started), and a trigger to read
        // recents again.
        val notice = mutableStateOf<String?>(null)
        var noticeTimer: Job? = null
        val recentRefresh = MutableStateFlow(0)
        // This drawer's work: cancelled when this drawer goes (or fails to show), never by
        // another drawer's callbacks, e.g. one still closing when this one opens.
        val drawerJob = lifecycleScope.launch {
            // Follows the pinned apps exactly while open (marked while reloading, so a removed app
            // can't be launched from the list still shown).
            pinned.collect { p -> shownPinned.value = p }
        }
        lifecycleScope.launch(drawerJob) {
            pinnedKeys.filterNotNull().collect { keys -> order.value = keys }
        }

        /** Shows [text] in the drawer for a moment (a toast would sit behind it). */
        fun showNotice(text: String) {
            notice.value = text
            // Each notice gets its full time: a newer one replaces the older one's timer.
            noticeTimer?.cancel()
            noticeTimer = lifecycleScope.launch(drawerJob) {
                delay(NOTICE_MS)
                notice.value = null
            }
        }
        var recentLoad: Job? = null
        // A drawer that can't be shown is cleaned up by the host, which calls onClosed.
        host.openDrawer(
            // The loaded apps in the settings' current order (also over a list kept from before the
            // pinned set changed: apps unpinned since are left out, ones not loaded yet come later).
            apps = derivedStateOf { shownPinned.value?.let { p -> p.kept.load.ordered(order.value).orEmpty() } },
            status = derivedStateOf { shownPinned.value?.status(retryAsked) ?: PinnedStatus.RELOADING },
            hasPinned = derivedStateOf { order.value.isNotEmpty() },
            notice = notice,
            recent = recent,
            expectRecents = expectRecents,
            // False if the app couldn't be started: say so in the drawer (a toast would sit behind
            // it). If it's gone (e.g. uninstalled while the list reloaded), refresh the list it was in
            // now rather than wait for the change to be noticed; a paused profile needs no refresh.
            onLaunch = { app, bounds ->
                val result = catalog.launch(app, bounds)
                if (result != LaunchResult.STARTED) {
                    showNotice(getString(R.string.app_unavailable, app.label))
                }
                if (result == LaunchResult.GONE) {
                    // As if the system had reported it: drop it from the caches, then reload.
                    onAppChanged(app.key.pkg)
                    if (app.key !in shownPinned.value?.keys.orEmpty()) {
                        recentRefresh.value++
                    }
                }
                result == LaunchResult.STARTED
            },
            onReorder = { shownOrder ->
                // The settings take it at once (saving in the background, and reverting it if that
                // fails, which the order follows); mirrored here so it shows in this frame.
                val saved = repo.change { it.copy(apps = it.apps.withVisibleOrder(shownOrder)) }
                repo.state.value?.let { order.value = it.apps }
                // Not tied to this drawer: a drag saved as the drawer closes still reports failure.
                lifecycleScope.launch {
                    if (!saved.await()) {
                        if (drawerJob.isActive) {
                            // Put back directly: the undo may never show in the keys flow (it can
                            // land before that flow saw the change, leaving it nothing new to emit).
                            repo.state.value?.let { order.value = it.apps }
                            showNotice(getString(R.string.settings_save_failed))
                        } else {
                            toastSaveFailed()
                        }
                    }
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
                        combine(pinnedKeys.map { it.orEmpty().toSet() }.distinctUntilChanged(), recentRefresh) { keys, _ -> keys }
                            .collectLatest { keys -> recent.value = recentApps(keys.toList()) }
                    }
                }
            },
        )
    }

    /** Says a change couldn't be saved (and was undone), where no drawer is open to say it in. */
    private fun toastSaveFailed() {
        Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
    }

    /** An app changed ([pkg]; null: several or a whole profile): drop it from the caches, reload if pinned. */
    private fun onAppChanged(pkg: String?) {
        catalog.invalidate()
        pinnedIcons.onAppChanged(pkg)
        recentIcons.onAppChanged(pkg)
        if (affectsPinned(pkg)) {
            pinnedAppsChanges.value++
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
