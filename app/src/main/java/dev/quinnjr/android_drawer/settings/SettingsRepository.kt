package dev.quinnjr.android_drawer.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

// A corrupt file (e.g. power lost mid-write) is replaced with defaults rather than crashing.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    "drawer_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The app's settings: one instance per process ([get]), holding them in memory. A change is applied
 * there at once (every reader sees it immediately), then saved; if saving fails it is reverted.
 * This process is the file's only writer, so after the first read memory is the truth.
 */
class SettingsRepository private constructor(context: Context) {
    private val store = context.applicationContext.dataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val stateLock = Any()
    private val current = MutableStateFlow<DrawerSettings?>(null)

    /** The settings; null until first read. */
    val state: StateFlow<DrawerSettings?> = current.asStateFlow()

    /** The settings as they change (after the first read). */
    val settings: Flow<DrawerSettings> = current.filterNotNull()

    init {
        // Retried (with backoff) on any error rather than given up on or crashing the app. Nothing
        // is made up meanwhile: defaults (handle on, no apps) could undo what the user chose.
        scope.launch {
            val stored = store.data
                .retryWhen { cause, attempt ->
                    Log.w(TAG, "Could not read settings; retrying", cause)
                    delay((attempt + 1).coerceAtMost(MAX_RETRY_STEPS) * RETRY_STEP_MS)
                    true
                }
                .first()
                .toSettings()
            current.compareAndSet(null, stored)
        }
    }

    /**
     * Applies [transform] to the settings now (every reader sees it at once) and saves them; the
     * result says whether saving worked. If it didn't, the change is reverted (unless a later one
     * was made on top: its own save then writes this one too). Saves complete even if the caller
     * goes away, so memory and disk can't drift apart. False straight away if the settings haven't
     * been read yet.
     */
    fun change(transform: (DrawerSettings) -> DrawerSettings): Deferred<Boolean> {
        val (before, next) = synchronized(stateLock) {
            val before = current.value ?: return CompletableDeferred(false)
            val next = transform(before)
            current.value = next
            before to next
        }
        return scope.async {
            val saved = writeLock.withLock {
                try {
                    store.edit { prefs -> write(prefs, next) }
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Could not save settings", e)
                    false
                }
            }
            if (!saved) {
                synchronized(stateLock) {
                    if (current.value == next) {
                        current.value = before
                    }
                }
            }
            saved
        }
    }

    /**
     * [change], waiting for the save: true if it worked. Waits (briefly) for the first read; if the
     * settings still can't be read, false.
     */
    suspend fun update(transform: (DrawerSettings) -> DrawerSettings): Boolean {
        withTimeoutOrNull(READ_WAIT_MS) { current.filterNotNull().first() } ?: return false
        return change(transform).await()
    }

    private fun write(prefs: MutablePreferences, next: DrawerSettings) {
        prefs[ENABLED] = next.enabled
        prefs[SIDE] = next.side.name
        prefs[Y_FRACTION] = next.yFraction
        prefs[HEIGHT] = next.handleHeightDp
        prefs[ALPHA] = next.handleAlpha
        prefs[COLOR] = next.handleColor
        prefs[APPS] = encodeApps(next.apps)
        prefs[ICONS_LOCKED] = next.iconsLocked
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

    companion object {
        @Volatile private var instance: SettingsRepository? = null

        /** The process-wide instance. */
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) { instance ?: SettingsRepository(context).also { instance = it } }

        private val ENABLED = booleanPreferencesKey("enabled")
        private val SIDE = stringPreferencesKey("side")
        private val Y_FRACTION = floatPreferencesKey("y_fraction")
        private val HEIGHT = intPreferencesKey("handle_height_dp")
        private val ALPHA = floatPreferencesKey("handle_alpha")
        private val COLOR = longPreferencesKey("handle_color")
        private val APPS = stringPreferencesKey("apps")
        private val ICONS_LOCKED = booleanPreferencesKey("icons_locked")
        private const val RETRY_STEP_MS = 1_000L
        private const val MAX_RETRY_STEPS = 30L
        private const val READ_WAIT_MS = 5_000L
    }
}
