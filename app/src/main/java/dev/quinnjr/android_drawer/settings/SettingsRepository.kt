package dev.quinnjr.android_drawer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("drawer_settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<DrawerSettings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun update(transform: (DrawerSettings) -> DrawerSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[ENABLED] = next.enabled
            prefs[SIDE] = next.side.name
            prefs[Y_FRACTION] = next.yFraction
            prefs[HEIGHT] = next.handleHeightDp
            prefs[ALPHA] = next.handleAlpha
            prefs[COLOR] = next.handleColor
            prefs[APPS] = encodeApps(next.apps)
            prefs[ICONS_LOCKED] = next.iconsLocked
        }
    }

    private fun Preferences.toSettings(): DrawerSettings {
        val defaults = DrawerSettings()
        return DrawerSettings(
            enabled = this[ENABLED] ?: defaults.enabled,
            side = this[SIDE]?.let { runCatching { Side.valueOf(it) }.getOrNull() } ?: defaults.side,
            yFraction = this[Y_FRACTION] ?: defaults.yFraction,
            handleHeightDp = this[HEIGHT] ?: defaults.handleHeightDp,
            handleAlpha = this[ALPHA] ?: defaults.handleAlpha,
            handleColor = this[COLOR] ?: defaults.handleColor,
            apps = this[APPS]?.let(::decodeApps) ?: defaults.apps,
            iconsLocked = this[ICONS_LOCKED] ?: defaults.iconsLocked,
        )
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val SIDE = stringPreferencesKey("side")
        val Y_FRACTION = floatPreferencesKey("y_fraction")
        val HEIGHT = intPreferencesKey("handle_height_dp")
        val ALPHA = floatPreferencesKey("handle_alpha")
        val COLOR = longPreferencesKey("handle_color")
        val APPS = stringPreferencesKey("apps")
        val ICONS_LOCKED = booleanPreferencesKey("icons_locked")
    }
}
