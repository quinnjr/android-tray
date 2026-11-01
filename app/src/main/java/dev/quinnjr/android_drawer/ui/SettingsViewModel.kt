package dev.quinnjr.android_drawer.ui

import android.app.Application
import android.content.res.Configuration
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.quinnjr.android_drawer.apps.AppCatalog
import dev.quinnjr.android_drawer.apps.DrawConfig
import dev.quinnjr.android_drawer.apps.DrawnAppCache
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.apps.appChanges
import dev.quinnjr.android_drawer.overlay.clampHandleHeightDp
import dev.quinnjr.android_drawer.service.EdgeService
import dev.quinnjr.android_drawer.settings.AppKey
import dev.quinnjr.android_drawer.settings.DrawerSettings
import dev.quinnjr.android_drawer.settings.SettingsRepository
import dev.quinnjr.android_drawer.settings.movedAmongShown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)
    private val catalog = AppCatalog(app)
    /** Apps with icons drawn, kept across reloads of the list. */
    private val icons = DrawnAppCache()

    /** Follows the activity's configuration (it is recreated on changes; this ViewModel isn't). */
    private val drawConfig = MutableStateFlow<DrawConfig?>(null)

    val settings: StateFlow<DrawerSettings?> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** All launchable apps; null while loading. */
    val allApps: StateFlow<List<LaunchableApp>?> = combine(
        // A changed app loses its drawn icon at once; a burst of updates reloads the list once.
        // App changes aren't watched while nobody is subscribed, so a new subscription starts afresh.
        appChanges(app, onChange = icons::onAppChanged).onStart { icons.clear() },
        drawConfig.filterNotNull(),
    ) { _, config -> config }
        // Icons drawn here, off the main thread, rather than while the list scrolls; only apps
        // without a drawn icon are loaded and drawn, and a newer change cancels the work in progress.
        .mapLatest { config ->
            icons.use(config)
            // Taken before reading the apps, so an icon of an app that changes meanwhile isn't kept.
            val ticket = icons.begin()
            catalog.allApps(cached = { icons[it] }).map { app ->
                currentCoroutineContext().ensureActive()
                icons.draw(app, ticket)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Call from the activity on creation (including after a configuration change). */
    fun setConfiguration(configuration: Configuration) {
        drawConfig.value = DrawConfig.of(configuration, SETTINGS_ICON_SIZE)
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        repo.update { it.copy(enabled = enabled) }
        syncService()
    }

    /** Starts or stops the overlay to match settings and permission (call on resume). */
    fun syncService() = viewModelScope.launch {
        val app = getApplication<Application>()
        val enabled = repo.settings.first().enabled
        if (enabled && Settings.canDrawOverlays(app)) EdgeService.start(app) else EdgeService.stop(app)
    }

    fun toggleApp(key: AppKey) = update { it.copy(apps = it.apps.toggled(key)) }
    /**
     * Moves [key] by [delta] places among [shown], the pinned apps the settings screen could
     * resolve; apps it couldn't show (e.g. a paused work profile's) keep their place.
     */
    fun moveApp(shown: Set<AppKey>, key: AppKey, delta: Int) =
        update { it.copy(apps = it.apps.movedAmongShown(shown, key, delta)) }
    fun setHeight(dp: Int) = update { it.copy(handleHeightDp = clampHandleHeightDp(dp)) }
    fun setAlpha(alpha: Float) = update { it.copy(handleAlpha = alpha.coerceIn(0.2f, 1f)) }
    fun setColor(color: Long) = update { it.copy(handleColor = color) }
    fun setIconsLocked(locked: Boolean) = update { it.copy(iconsLocked = locked) }

    private fun update(transform: (DrawerSettings) -> DrawerSettings) {
        viewModelScope.launch { repo.update(transform) }
    }
}

internal fun List<AppKey>.toggled(key: AppKey): List<AppKey> = if (key in this) this - key else this + key
