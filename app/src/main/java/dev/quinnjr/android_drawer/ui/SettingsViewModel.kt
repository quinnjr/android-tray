package dev.quinnjr.android_drawer.ui

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.quinnjr.android_drawer.apps.AppCatalog
import dev.quinnjr.android_drawer.apps.LaunchableApp
import dev.quinnjr.android_drawer.apps.appsChanged
import dev.quinnjr.android_drawer.apps.withBitmaps
import dev.quinnjr.android_drawer.overlay.clampHandleHeightDp
import dev.quinnjr.android_drawer.service.EdgeService
import dev.quinnjr.android_drawer.settings.AppKey
import dev.quinnjr.android_drawer.settings.DrawerSettings
import dev.quinnjr.android_drawer.settings.SettingsRepository
import dev.quinnjr.android_drawer.settings.movedAmongShown
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)
    private val catalog = AppCatalog(app)
    private val iconSizePx = (SETTINGS_ICON_SIZE.value * app.resources.displayMetrics.density).roundToInt()

    val settings: StateFlow<DrawerSettings?> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** All launchable apps; null while loading. */
    val allApps: StateFlow<List<LaunchableApp>?> = appsChanged(app)
        .onStart { emit(Unit) }
        // Icons drawn here, off the main thread, rather than while the list scrolls.
        .map { catalog.allApps().withBitmaps(iconSizePx) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

internal fun List<AppKey>.toggled(key: AppKey): List<AppKey> = if (key in this) this - key else this + key
