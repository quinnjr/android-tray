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
import java.util.concurrent.CopyOnWriteArrayList
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
 * there at once (every reader sees it immediately), then saved; if saving fails, memory goes back
 * to what is on disk. This process is the file's only writer, and it's read once: an Auto Backup
 * restore shuts the app down first, so the restored file is read when it next starts.
 */
class SettingsRepository private constructor(context: Context) {
    private val store = context.applicationContext.dataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val stateLock = Any()

    /**
     * One state of the settings in memory: [undo] if it went back to the disk's after a save
     * failed. A plain class, so each one is new to the StateFlow even if equal to an earlier one.
     */
    private class Revision(val settings: DrawerSettings, val undo: Boolean = false)

    private val current = MutableStateFlow<Revision?>(null)
    /** Declared before init, which may already call them (see show()). */
    private val observers = CopyOnWriteArrayList<(DrawerSettings) -> Unit>()
    /** Counts changes made in memory (under [stateLock]); [onDiskVersion] has caught up when none is pending. */
    private var version = 0L
    /** The settings as last read from or written to disk, and the [version] they were (under [stateLock]). */
    private var onDisk: DrawerSettings? = null
    private var onDiskVersion = 0L
    /** Changes up to this [version] were undone after a failed save (under [stateLock]). */
    private var undoneThrough = 0L

    /** The settings now; null until first read. */
    val value: DrawerSettings? get() = current.value?.settings

    /**
     * The settings as they change (after the first read): each value that differs from the one
     * last delivered to this collector, and every undo of a failed save, even back to a value it
     * already has (it may have missed the failed one, and still be showing it ahead of time,
     * e.g. a dragged handle).
     */
    val settings: Flow<DrawerSettings> = flow {
        var last: DrawerSettings? = null
        current.filterNotNull().collect { revision ->
            if (revision.undo || revision.settings != last) {
                last = revision.settings
                emit(revision.settings)
            }
        }
    }

    init {
        // A read error is retried with backoff (up to every 30s) for as long as it lasts rather
        // than made up for: defaults (handle on, no apps) could undo what the user chose, and
        // giving up would leave this process without settings for good. Meanwhile changes
        // report failure (see update()).
        scope.launch {
            val stored = store.data
                .retryWhen { cause, attempt ->
                    // Not an I/O error (e.g. storage not ready yet): probably a bug, so loudly.
                    if (cause is IOException) {
                        Log.w(TAG, "Could not read settings; retrying", cause)
                    } else {
                        Log.e(TAG, "Could not read settings; retrying", cause)
                    }
                    delay((attempt + 1).coerceAtMost(MAX_RETRY_STEPS) * RETRY_STEP_MS)
                    true
                }
                .first()
                .toSettings()
            synchronized(stateLock) {
                onDisk = stored
                show(stored)
            }
        }
    }

    /** Makes [settings] the current ones, as a new revision (under [stateLock]). */
    private fun show(settings: DrawerSettings, undo: Boolean = false) {
        current.value = Revision(settings, undo)
        observers.forEach { it(settings) }
    }

    /**
     * Calls [observer] with the settings now (once read) and on every change, synchronously, on
     * whichever thread makes it: for state that must follow a change in the same frame (e.g.
     * Compose state, which may be written from any thread). It's called under the repository's
     * lock, so the calls arrive in order: it must only record the value (no blocking, no calls
     * back into the repository). Returns a function that stops it.
     */
    fun observe(observer: (DrawerSettings) -> Unit): () -> Unit {
        synchronized(stateLock) {
            observers += observer
            current.value?.let { observer(it.settings) }
        }
        return { observers -= observer }
    }

    /**
     * Applies [transform] to the settings now (every reader sees it at once) and saves them; the
     * result says whether this change reached the disk. Each save writes the latest settings, so
     * saves finishing out of order never leave an older value on disk. If saving fails, memory goes
     * back to what is on disk (every unsaved change is undone). The change is [sanitized] first.
     * Saves complete even if the caller goes away. [onResult] (if given) is told the outcome once
     * known, even if its caller's scope is gone by then: on a background thread after a save, or
     * straight away (on the caller's thread, before change() returns) if there's nothing to save
     * or the settings haven't been read. Never under the repository's lock. True at once if
     * nothing changes; false at once if the settings haven't been read.
     */
    fun change(
        transform: (DrawerSettings) -> DrawerSettings,
        onResult: (saved: Boolean) -> Unit = {},
    ): Deferred<Boolean> {
        // Worked out under the lock; [onResult] only ever runs outside it.
        val mine: Long? = synchronized(stateLock) {
            val before = current.value?.settings
            val next = before?.let(transform)?.sanitized()
            when {
                before == null -> NOT_READ
                // Nothing to do (no new revision, no write), unless an earlier change is still
                // being saved: then this one's outcome is that save's (which may yet undo it).
                next == before && version == onDiskVersion -> null
                next == before -> version
                else -> {
                    show(checkNotNull(next))
                    ++version
                }
            }
        }
        return when (mine) {
            NOT_READ -> done(false, onResult)
            null -> done(true, onResult)
            else -> scope.async { writeLock.withLock { save(mine) }.also(onResult) }
        }
    }

    private fun done(saved: Boolean, onResult: (Boolean) -> Unit): Deferred<Boolean> {
        onResult(saved)
        return CompletableDeferred(saved)
    }

    /** Saves the latest settings unless change [mine] is already on disk or undone (under [writeLock]). */
    private suspend fun save(mine: Long): Boolean {
        val (next, nextVersion) = synchronized(stateLock) {
            // Undone after a save failed, or already written by a later change's save (which
            // wrote this one too). In that order: an undo also brings the disk version up.
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
            // Not retried (with a full disk, say, it would only fail again): back to what's on
            // disk, undoing every change not saved yet, including any made meanwhile (their
            // own saves then report failure without trying).
            synchronized(stateLock) {
                show(checkNotNull(onDisk), undo = true)
                undoneThrough = version
                // Memory matches the disk again: nothing pending.
                onDiskVersion = version
            }
            return false
        }
    }

    /**
     * [change], once the settings have been read (waiting briefly), waiting for the save: true if
     * it worked; false if not, or if the settings still can't be read.
     */
    suspend fun update(transform: (DrawerSettings) -> DrawerSettings): Boolean {
        withTimeoutOrNull(READ_WAIT_MS) { current.filterNotNull().first() } ?: return false
        return change(transform = transform).await()
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
        ).sanitized()
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
        /** change(): the settings haven't been read (versions start at 1). */
        private const val NOT_READ = 0L
    }
}
