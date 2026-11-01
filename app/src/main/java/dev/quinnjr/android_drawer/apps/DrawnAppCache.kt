package dev.quinnjr.android_drawer.apps

import android.content.res.Configuration
import android.util.DisplayMetrics
import androidx.compose.ui.unit.Dp
import dev.quinnjr.android_drawer.settings.AppKey
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** What drawn app icons and labels depend on: icon size, language and dark mode (themed icons). */
data class DrawConfig(val iconSizePx: Int, val locales: String, val nightMode: Int) {
    companion object {
        fun of(c: Configuration, iconSize: Dp) = DrawConfig(
            iconSizePx = (iconSize.value * c.densityDpi / DisplayMetrics.DENSITY_DEFAULT).roundToInt(),
            locales = c.locales.toLanguageTags(),
            nightMode = c.uiMode and Configuration.UI_MODE_NIGHT_MASK,
        )
    }
}

/**
 * Apps with their icons drawn, kept across loads. Emptied when the [DrawConfig] changes or an app
 * changes; values drawn from data read before such a change are never kept (see [IconCache]).
 */
class DrawnAppCache(maxSize: Int = Int.MAX_VALUE) {
    private val cache = IconCache<AppKey, LaunchableApp>(maxSize)
    private val lock = Any()
    private var config: DrawConfig? = null

    /** Clears the cache if [config] differs from the one it was filled for. Call before [load]. */
    fun use(config: DrawConfig) = synchronized(lock) {
        if (config != this.config) {
            cache.clear()
            this.config = config
        }
    }

    operator fun get(key: AppKey): LaunchableApp? = cache[key]

    /**
     * Loads apps with [load], which gets each one through the [AppSource] it is given: cached
     * apps as they are, others made and drawn right away. The invalidation generation and icon
     * size are taken together before anything is read, so an app whose data changes meanwhile is
     * drawn but not kept. Stops early if the calling coroutine is cancelled.
     */
    suspend fun load(load: (obtain: AppSource) -> List<LaunchableApp>): List<LaunchableApp> {
        val (generation, iconSizePx) = synchronized(lock) {
            cache.generation() to checkNotNull(config) { "No DrawConfig set" }.iconSizePx
        }
        val context = currentCoroutineContext()
        return load { key, make ->
            cache[key] ?: run {
                context.ensureActive()
                make().withBitmap(iconSizePx).also { cache.put(key, it, generation) }
            }
        }
    }

    /** One app changed ([pkg]), or several or a whole profile (null). */
    fun onAppChanged(pkg: String?) {
        if (pkg == null) {
            cache.clear()
        } else {
            cache.forget { it.isIn(pkg) }
        }
    }

    /** Keeps only [keys] (e.g. the apps still pinned). */
    fun retain(keys: Set<AppKey>) = cache.retain(keys)
}
