package dev.quinnjr.android_drawer.apps

/**
 * A thread-safe cache of drawn icons (or anything slow to make), least recently used first out
 * beyond [maxSize]. Take [generation] before reading the data a value is made from; [put] then
 * drops the value if that data was invalidated meanwhile — by a [clear], or by a [forget] whose
 * predicate matches its key — so stale data can never be stored after the invalidation.
 */
class IconCache<K, V>(private val maxSize: Int = Int.MAX_VALUE) {
    private val lock = Any()
    private var generation = 0
    /** Values taken before this generation predate the last clear. */
    private var clearedAt = 0
    /** Recent forgets, as (generation after the forget, which keys it covered). */
    private val forgets = ArrayDeque<Pair<Int, (K) -> Boolean>>()
    private val entries = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > maxSize
    }

    fun generation(): Int = synchronized(lock) { generation }

    operator fun get(key: K): V? = synchronized(lock) { entries[key] }

    /** Stores [value] unless its key was invalidated since [generation] was taken. */
    fun put(key: K, value: V, generation: Int) = synchronized(lock) {
        if (generation < clearedAt) return@synchronized
        if (forgets.any { (at, covers) -> at > generation && covers(key) }) return@synchronized
        entries[key] = value
    }

    fun forget(predicate: (K) -> Boolean) = synchronized(lock) {
        generation++
        entries.keys.removeAll(predicate)
        forgets.addLast(generation to predicate)
        // Only a bounded history is kept; beyond it, err on the safe side as if cleared.
        if (forgets.size > MAX_FORGETS) {
            clearedAt = forgets.removeFirst().first
        }
    }

    fun clear() = synchronized(lock) {
        generation++
        clearedAt = generation
        forgets.clear()
        entries.clear()
    }

    private companion object {
        const val MAX_FORGETS = 64
    }
}
