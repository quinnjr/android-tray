package dev.quinnjr.android_drawer.apps

import android.content.res.Configuration
import android.util.DisplayMetrics
import androidx.compose.ui.unit.Dp
import dev.quinnjr.android_drawer.settings.AppKey
import kotlin.math.roundToInt

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
 * Take a [Ticket] with [begin] before reading app data, and draw with it.
 */
class DrawnAppCache(maxSize: Int = Int.MAX_VALUE) {
    /** A generation and the icon size of the same config, taken together. */
    class Ticket internal constructor(internal val generation: Int, internal val iconSizePx: Int)

    private val cache = IconCache<AppKey, LaunchableApp>(maxSize)
    private val lock = Any()
    private var config: DrawConfig? = null

    /** Clears the cache if [config] differs from the one it was filled for. */
    fun use(config: DrawConfig) = synchronized(lock) {
        if (config != this.config) {
            cache.clear()
            this.config = config
        }
    }

    /** Starts a load: call before reading the app data to draw. Needs a config set with [use]. */
    fun begin(): Ticket = synchronized(lock) {
        Ticket(cache.generation(), checkNotNull(config) { "No DrawConfig set" }.iconSizePx)
    }

    operator fun get(key: AppKey): LaunchableApp? = cache[key]

    /** [app] with its icon drawn, kept unless its data was invalidated since [ticket] was taken. */
    fun draw(app: LaunchableApp, ticket: Ticket): LaunchableApp {
        if (app.bitmap != null) return app
        return app.withBitmap(ticket.iconSizePx).also { cache.put(app.key, it, ticket.generation) }
    }

    /** One app changed ([pkg]), or several or a whole profile (null). */
    fun onAppChanged(pkg: String?) {
        if (pkg == null) cache.clear() else cache.forget { it.isIn(pkg) }
    }

    fun clear() = cache.clear()
}
