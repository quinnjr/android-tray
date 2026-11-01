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
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// A corrupt file (e.g. power lost mid-write) is replaced with defaults rather than crashing.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    "drawer_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * The app's settings: one instance per process ([get]), holding them in memory. A change is applied
 * there at once (every reader sees it immediately), then saved; if saving fails, memory goes back
 * to what is on disk. This process is the file's only writer, and it's read once: an Auto Backup
 * restore shuts the app down first, so the restored file is read when it next starts.
 */
class SettingsRepository private constructor(context: Context) {
    private val store = context.applicationContext.dataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val stateLock = Any()

    /** One state of the settings in memory; each is new ([n]), even one equal to an earlier one. */
    private class Revision(val settings: DrawerSettings, val n: Long)

    private val current = MutableStateFlow<Revision?>(null)
    /** Completes once the settings are read: false if reading them was given up on. */
    private val read = CompletableDeferred<Boolean>()
    /** Counts the [Revision]s (under [stateLock]). */
    private var revisions = 0L
    /** Counts changes made in memory (under [stateLock]). */
    private var version = 0L
    /** The settings as last read from or written to disk, and the [version] they were (under [stateLock]). */
    private var onDisk: DrawerSettings? = null
    private var onDiskVersion = 0L
    /** Changes up to this [version] were undone after a failed save (under [stateLock]). */
    private var undoneThrough = 0L

    /** The settings now; null until first read. */
    val value: DrawerSettings? get() = current.value?.settings

    /**
     * The settings as they change (after the first read). Every change is delivered, including
     * one undone after its save failed: going back to an earlier value is a change too, so anyone
     * who showed the failed one ahead of time (e.g. a dragged handle) is put back.
     */
    val settings: Flow<DrawerSettings> = current.filterNotNull().map { it.settings }

    init {
        // A read error is retried with backoff rather than made up for: defaults (handle on, no
        // apps) could undo what the user chose. I/O errors (e.g. storage not ready) are retried
        // for as long as they last; anything else a few times, then given up on and logged (the
        // settings stay unread: no handle, and changes report failure).
        scope.launch {
            var otherErrors = 0
            val stored = store.data
                .retryWhen { cause, attempt ->
                    val retry = cause is IOException || otherErrors++ < OTHER_ERROR_RETRIES
                    if (retry) {
                        Log.w(TAG, "Could not read settings; retrying", cause)
                        delay((attempt + 1).coerceAtMost(MAX_RETRY_STEPS) * RETRY_STEP_MS)
                    }
                    retry
                }
                .catch { e -> Log.e(TAG, "Could not read settings; giving up", e) }
                .firstOrNull()
                ?.toSettings()
            if (stored != null) {
                synchronized(stateLock) {
                    onDisk = stored
                    show(stored)
                }
            }
            read.complete(stored != null)
        }
    }

    /** Makes [settings] the current ones, as a new revision (under [stateLock]). */
    private fun show(settings: DrawerSettings) {
        current.value = Revision(settings, ++revisions)
    }

    /**
     * Applies [transform] to the settings now (every reader sees it at once) and saves them; the
     * result says whether this change reached the disk. Each save writes the latest settings, so
     * saves finishing out of order never leave an older value on disk. If saving fails, it's tried
     * again with any later change made meanwhile; otherwise memory goes back to what is on disk.
     * Saves complete even if the caller goes away. True at once if nothing changes; false at once
     * if the settings haven't been read.
     */
    fun change(transform: (DrawerSettings) -> DrawerSettings): Deferred<Boolean> {
        val mine = synchronized(stateLock) {
            val before = current.value?.settings ?: return CompletableDeferred(false)
            val next = transform(before)
            if (next == before) {
                return CompletableDeferred(true)
            }
            show(next)
            ++version
        }
        return scope.async { writeLock.withLock { save(mine) } }
    }

    /** Saves the latest settings unless change [mine] is already on disk or undone (under [writeLock]). */
    private suspend fun save(mine: Long): Boolean {
        while (true) {
            val (next, nextVersion) = synchronized(stateLock) {
                // Undone after a save failed, or already written by a later change's save (which
                // wrote this one too).
                if (undoneThrough >= mine) {
                    return false
                }
                if (onDiskVersion >= mine) {
                    return true
                }
                checkNotNull(current.value).settings to version
            }
            try {
                store.edit { prefs -> write(prefs, next) }
                synchronized(stateLock) {
                    onDisk = next
                    onDiskVersion = nextVersion
                }
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not save settings", e)
                synchronized(stateLock) {
                    // Nothing newer to try: back to what's on disk.
                    if (version == nextVersion) {
                        show(checkNotNull(onDisk))
                        undoneThrough = nextVersion
                        return false
                    }
                }
                // A later change came in meanwhile: try again with it.
            }
        }
    }

    /**
     * [change], once the settings have been read, waiting for the save: true if it worked (false
     * if the settings couldn't be read).
     */
    suspend fun update(transform: (DrawerSettings) -> DrawerSettings): Boolean {
        if (!read.await()) {
            return false
        }
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
        private const val OTHER_ERROR_RETRIES = 3
    }
}
