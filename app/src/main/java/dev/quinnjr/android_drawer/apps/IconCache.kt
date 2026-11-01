package dev.quinnjr.android_drawer.apps

/**
 * A thread-safe cache of drawn icons (or anything slow to make), least recently used first out
 * beyond [maxSize]. Every [forget] or [clear] starts a new generation: take [generation] before
 * reading the data a value is made from, and [put] drops the value if the cache was invalidated
 * meanwhile, so stale data can never be stored after the invalidation.
 */
class IconCache<K, V>(private val maxSize: Int = Int.MAX_VALUE) {
    private val lock = Any()
    private var generation = 0
    private val entries = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > maxSize
    }

    fun generation(): Int = synchronized(lock) { generation }

    operator fun get(key: K): V? = synchronized(lock) { entries[key] }

    /** Stores [value] unless the cache was invalidated since [generation] was taken. */
    fun put(key: K, value: V, generation: Int) = synchronized(lock) {
        if (generation == this.generation) entries[key] = value
    }

    fun forget(predicate: (K) -> Boolean) = synchronized(lock) {
        generation++
        entries.keys.removeAll(predicate)
    }

    fun clear() = synchronized(lock) {
        generation++
        entries.clear()
    }
}
