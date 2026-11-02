package dev.quinnjr.android_drawer.ui

import android.app.Application
import android.content.res.Configuration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.quinnjr.android_drawer.apps.AppCatalog
import dev.quinnjr.android_drawer.apps.DrawConfig
import dev.quinnjr.android_drawer.apps.DrawnAppCache
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.apps.appsChanged
import dev.quinnjr.android_drawer.apps.orOnFailure
import dev.quinnjr.android_drawer.apps.reloads
import dev.quinnjr.android_drawer.apps.resolveDrawn
import dev.quinnjr.android_drawer.overlay.clampHandleHeightDp
import dev.quinnjr.android_drawer.service.EdgeService
import dev.quinnjr.android_drawer.settings.AppKey
import dev.quinnjr.android_drawer.settings.DrawerSettings
import dev.quinnjr.android_drawer.settings.SettingsRepository
import dev.quinnjr.android_drawer.settings.concernsPackage
import dev.quinnjr.android_drawer.settings.movedAmongShown
import dev.quinnjr.android_drawer.settings.toHandleAlpha
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository.get(app)
    private val catalog = AppCatalog(app)
    /** Apps with icons drawn, kept across reloads of the list. */
    private val icons = DrawnAppCache()

    /** Follows the activity's configuration (it is recreated on changes; this ViewModel isn't). */
    private val drawConfig = MutableStateFlow<DrawConfig?>(null)

    /** Held until collected, so one landing while the activity is recreated isn't lost. */
    private val failedSaves = Channel<Unit>(Channel.CONFLATED)

    /** Emits when a change couldn't be saved (and so was undone); collect from one place. */
    val saveFailures: Flow<Unit> = failedSaves.receiveAsFlow()

    /** The settings; null until first read. */
    val settings: StateFlow<DrawerSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, repo.value)

    /** App changes, watched for as long as this ViewModel lives so [icons] never misses one. */
    private val appChanges: Flow<String?> = appsChanged(app)
        .onEach(icons::onAppChanged)
        .shareIn(viewModelScope, SharingStarted.Eagerly)

    /** The pinned apps, for the settings screen; null while loading. */
    val chosenApps: StateFlow<PinnedApps?> = combine(
        repo.settings.map { it.apps }.distinctUntilChanged(),
        // Only changes to pinned apps (or to many apps or a whole profile) matter here.
        appChanges.reloads { pkg -> settings.value?.concernsPackage(pkg) ?: (pkg == null) },
        drawConfig.filterNotNull(),
    ) { keys, _, config -> keys to config }
        .mapLatest { (keys, config) ->
            icons.use(config)
            catalog.resolveDrawn(icons, keys)
                ?.let { PinnedApps(keys, it.apps, it.unavailable, failed = it.unknown.isNotEmpty()) }
                ?: PinnedApps(keys, emptyList(), emptySet(), failed = true)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every launchable app, for the app picker (only loaded while it is shown); null while loading. */
    val allApps: StateFlow<List<LaunchableApp>?> = combine(appChanges.reloads(), drawConfig.filterNotNull()) { _, config -> config }
        .mapLatest { config ->
            icons.use(config)
            orOnFailure("apps", emptyList()) { icons.load { obtain -> catalog.allApps(obtain) } }
        }
        // Once the picker is gone, keep only the pinned apps' icons rather than every app's.
        .onCompletion { settings.value?.let { s -> icons.retain(s.apps.toSet()) } }
        .flowOn(Dispatchers.Default)
        // Not kept once the picker is gone: every app's drawn icon would stay in memory otherwise.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)


    /** Call from the activity on creation (including after a configuration change). */
    fun setConfiguration(configuration: Configuration) {
        drawConfig.value = DrawConfig.of(configuration, SETTINGS_ICON_SIZE)
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        save { it.copy(enabled = enabled) }
        syncService()
    }

    /** Starts or stops the overlay to match settings and permission (call on resume). */
    fun syncService() = viewModelScope.launch {
        val app = getApplication<Application>()
        EdgeService.sync(app, repo.settings.first().enabled)
    }

    fun toggleApp(key: AppKey) = update { it.copy(apps = it.apps.toggled(key)) }
    /**
     * Moves [key] by [delta] places among the pinned apps the settings screen shows; apps it
     * couldn't show (e.g. a paused work profile's) keep their place.
     */
    fun moveApp(key: AppKey, delta: Int) {
        val shown = chosenApps.value?.apps?.mapTo(HashSet()) { it.key } ?: return
        update { it.copy(apps = it.apps.movedAmongShown(shown, key, delta)) }
    }
    fun setHeight(dp: Int) = update { it.copy(handleHeightDp = clampHandleHeightDp(dp)) }
    fun setAlpha(alpha: Float) = update { it.copy(handleAlpha = alpha.toHandleAlpha()) }
    fun setColor(color: Long) = update { it.copy(handleColor = color) }
    fun setIconsLocked(locked: Boolean) = update { it.copy(iconsLocked = locked) }

    /**
     * Unpins [unavailable] (as shown to the user): only those, so an app pinned since, or one
     * that was merely still loading, is never removed by mistake.
     */
    fun unpin(unavailable: Set<AppKey>) = update { s -> s.copy(apps = s.apps - unavailable) }

    private fun update(transform: (DrawerSettings) -> DrawerSettings) {
        viewModelScope.launch { save(transform) }
    }

    private suspend fun save(transform: (DrawerSettings) -> DrawerSettings) {
        if (!repo.update(transform)) {
            failedSaves.trySend(Unit)
        }
    }
}

internal fun List<AppKey>.toggled(key: AppKey): List<AppKey> = if (key in this) { this - key } else { this + key }
