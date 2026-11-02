package dev.quinnjr.android_drawer.settings

enum class Side { LEFT, RIGHT }

/** Identifies a launcher activity in a specific user profile. */
data class AppKey(val component: String, val userSerial: Long) {
    /** The app's package name. */
    val pkg: String get() = component.substringBefore('/')

    fun encode(): String = "$userSerial|$component"

    companion object {
        fun decode(s: String): AppKey? {
            val sep = s.indexOf('|')
            if (sep <= 0) { return null }
            val serial = s.substring(0, sep).toLongOrNull() ?: return null
            val component = s.substring(sep + 1)
            // flattenToString() form is always "package/class".
            if (!component.contains('/') || component.startsWith('/') || component.endsWith('/')) { return null }
            return AppKey(component, serial)
        }
    }
}

/** Allowed handle opacity: never fully invisible, so it can still be found. */
val HANDLE_ALPHA_RANGE = 0.2f..1f

/** This opacity, kept within [HANDLE_ALPHA_RANGE] (NaN, which no range comparison catches, counts as opaque). */
fun Float.toHandleAlpha(): Float = if (isNaN()) { HANDLE_ALPHA_RANGE.endInclusive } else { coerceIn(HANDLE_ALPHA_RANGE) }

/**
 * Whether a change to [pkg] (null: several apps or a whole profile) concerns the pinned apps, so
 * they need reloading.
 */
fun DrawerSettings.concernsPackage(pkg: String?): Boolean = pkg == null || apps.any { it.pkg == pkg }

data class DrawerSettings(
    val enabled: Boolean = true,
    val side: Side = Side.RIGHT,
    val yFraction: Float = 0.35f,
    val handleHeightDp: Int = 120,
    val handleAlpha: Float = 0.6f,
    val handleColor: Long = 0xFFB0B0B0,
    val apps: List<AppKey> = emptyList(),
    /** When true, icons can't be dragged to reorder them in the open drawer. */
    val iconsLocked: Boolean = false,
)

/**
 * Moves [key] by [delta] places among the [shown] apps, from this (the latest saved) order, so
 * quick repeated moves each apply; apps not shown keep their place.
 */
internal fun List<AppKey>.movedAmongShown(shown: Set<AppKey>, key: AppKey, delta: Int): List<AppKey> {
    val visible = filter { it in shown }
    val from = visible.indexOf(key)
    val to = from + delta
    if (from < 0 || to !in visible.indices) { return this }
    return withVisibleOrder(visible.moved(from, to))
}

/** This list with the item at [from] moved to [to]; unchanged if either is out of range. */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices) { return this }
    return toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * Applies an order chosen in the drawer, which only shows the apps it could resolve. Those apps
 * take each other's slots in [visible] order; the rest (e.g. a paused work profile's) stay put.
 */
internal fun List<AppKey>.withVisibleOrder(visible: List<AppKey>): List<AppKey> {
    val movable = visible.filter { it in this }.distinct()
    val movableSet = movable.toSet()
    val next = movable.iterator()
    // hasNext(): a duplicated key (which decodeApps already drops) mustn't exhaust the iterator.
    return map { if (it in movableSet && next.hasNext()) { next.next() } else { it } }
}

internal fun encodeApps(apps: List<AppKey>): String = apps.joinToString("\n") { it.encode() }

/** Duplicates (e.g. from a restored or hand-edited file) are dropped: every list relies on unique keys. */
internal fun decodeApps(s: String): List<AppKey> = s.lineSequence().mapNotNull(AppKey::decode).distinct().toList()

/**
 * These items arranged by [order] (matched by [key]): keys in [order] without an item are skipped,
 * and items whose key isn't in [order] come after, in their own order (cf. [valuesIn], which drops them).
 */
internal fun <T, K> List<T>.arrangedBy(order: List<K>, key: (T) -> K): List<T> {
    val byKey = associateBy(key)
    val inOrder = order.toSet()
    return order.mapNotNull(byKey::get) + filter { key(it) !in inOrder }
}

/** The values for the keys in [order], in that order; keys without a value are skipped (others dropped). */
internal fun <K, V> Map<K, V>.valuesIn(order: List<K>): List<V> = order.mapNotNull(::get)
