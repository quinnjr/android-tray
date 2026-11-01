package dev.quinnjr.android_drawer.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.quinnjr.android_drawer.TAG
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen

// A corrupt file (e.g. power lost mid-write) is replaced with defaults rather than crashing.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    "drawer_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    /**
     * The settings, kept up to date. If the file can't be read the flow keeps retrying (with
     * backoff) rather than ending, so long-lived collectors still see later changes; until a first
     * read succeeds, defaults are emitted so nobody waits forever.
     */
    val settings: Flow<DrawerSettings> = flow {
        var readOnce = false
        emitAll(
            store.data
                .onEach { readOnce = true }
                .retryWhen { cause, attempt ->
                    if (cause !is IOException) {
                        return@retryWhen false
                    }
                    Log.w(TAG, "Could not read settings; retrying", cause)
                    if (!readOnce) {
                        emit(emptyPreferences())
                    }
                    delay((attempt + 1).coerceAtMost(MAX_RETRY_STEPS) * RETRY_STEP_MS)
                    true
                },
        )
    }
        .map { it.toSettings() }
        .distinctUntilChanged()

    /** Applies [transform]; a failed write (e.g. storage full) is logged rather than crashing the caller. */
    suspend fun update(transform: (DrawerSettings) -> DrawerSettings) {
        try {
            write(transform)
        } catch (e: IOException) {
            Log.e(TAG, "Could not save settings", e)
        }
    }

    private suspend fun write(transform: (DrawerSettings) -> DrawerSettings) {
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
        const val RETRY_STEP_MS = 1_000L
        const val MAX_RETRY_STEPS = 30L
    }
}
