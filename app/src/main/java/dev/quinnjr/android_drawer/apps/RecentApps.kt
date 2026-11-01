package dev.quinnjr.android_drawer.apps

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process

/** Recently used apps, read from usage stats (needs the user to grant Usage access). */
class RecentApps(private val context: Context) {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val lock = Any()
    /** History up to [settledUntil], old enough that no more events for it can still arrive. */
    private var settled: UsageHistory? = null
    private var builtAt = 0L
    private var settledUntil = 0L

    /**
     * Runs [block] on the usage history of the last few days. Events are stored a moment after
     * they're stamped, so the last [SETTLE_MS] are re-read on every call and only older events are
     * kept between calls; the kept part is rebuilt from scratch once a day so it stays bounded.
     */
    internal fun <T> withHistory(block: (UsageHistory) -> T): T = synchronized(lock) {
        // Read the clock under the lock, so a call that waited for it never goes back in time.
        val now = System.currentTimeMillis()
        val settleAt = now - SETTLE_MS
        var h = settled
        if (h == null || now - builtAt > REBUILD_MS || settleAt < settledUntil) {
            h = UsageHistory().replay(events(now - LOOKBACK_MS, settleAt))
            builtAt = now
        } else {
            h.replay(events(settledUntil, settleAt))
        }
        settled = h
        settledUntil = settleAt
        // Recent events go on a copy, as they may still change; skip the copy when there are none.
        val tail = events(settleAt, now)
        block(if (tail.isEmpty()) h else h.copy().replay(tail))
    }

    fun hasAccess(): Boolean {
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        // MODE_DEFAULT defers to the permission itself (e.g. granted with `pm grant`, or on some OEM builds).
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** The activity events that matter for recents between [since] and [until], oldest first. */
    internal fun events(since: Long, until: Long): List<UsageEvent> {
        val events = usage.queryEvents(since, until) ?: return emptyList()
        val out = ArrayList<UsageEvent>()
        val event = UsageEvents.Event()
        while (events.getNextEvent(event)) {
            val kind = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEvent.Kind.RESUMED
                // Not ACTIVITY_STOPPED: it always follows a pause, and can arrive after a newer
                // instance of the same activity resumed (list → detail of one class), so it would
                // wrongly take the app off screen. DESTROYED covers activities torn down without a
                // recorded pause (e.g. a crash), where the platform reports it.
                UsageEvents.Event.ACTIVITY_PAUSED, ACTIVITY_DESTROYED -> UsageEvent.Kind.LEFT
                // Everything on screen pauses then, and resumes again (with a new event) after.
                UsageEvents.Event.DEVICE_SHUTDOWN,
                UsageEvents.Event.DEVICE_STARTUP,
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                -> UsageEvent.Kind.RESET
                else -> continue
            }
            out += UsageEvent(kind, event.packageName, event.className, event.timeStamp)
        }
        return out
    }

    /**
     * Home screen apps, which are always just behind the drawer and never worth listing. With no
     * default launcher chosen the system resolver answers instead, so every real launcher counts.
     */
    fun homePackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pm = context.packageManager
        val default = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (default != null && default != SYSTEM_RESOLVER) return setOf(default)
        // Settings' fallback home activity has a negative priority; it is not a launcher.
        return pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.priority >= 0 }
            .mapTo(HashSet()) { it.activityInfo.packageName }
    }

    private companion object {
        const val SYSTEM_RESOLVER = "android"
        const val LOOKBACK_MS = 3L * 24 * 60 * 60 * 1000
        const val REBUILD_MS = 24L * 60 * 60 * 1000
        const val SETTLE_MS = 60L * 1000
        /**
         * UsageEvents.Event.ACTIVITY_DESTROYED, which isn't in the public SDK. Handled in case the
         * platform delivers it; if not, screen-off/lock still clears a crashed app (see reset()).
         */
        const val ACTIVITY_DESTROYED = 24
    }
}

internal data class UsageEvent(val kind: Kind, val pkg: String, val className: String?, val time: Long) {
    enum class Kind { RESUMED, LEFT, RESET }
}

/**
 * Each app's latest use and the activity used, plus which apps are still on screen — the app the
 * drawer was opened over, or both halves of a split screen. On-screen state is kept per activity:
 * opening a sub-screen resumes the new activity before the old one stops.
 */
internal class UsageHistory {
    private val lastUsed = HashMap<String, Long>()
    private val lastActivity = HashMap<String, String>()
    /** Resumed, not yet paused or stopped activities, by (package, class), with their resume time. */
    private val resumed = HashMap<Pair<String, String>, Long>()

    fun resumed(pkg: String, time: Long, className: String? = null) {
        if (time >= (lastUsed[pkg] ?: Long.MIN_VALUE)) {
            lastUsed[pkg] = time
            if (className != null) lastActivity[pkg] = className
        }
        resumed[pkg to className.orEmpty()] = time
    }

    fun paused(pkg: String, className: String? = null) {
        resumed.remove(pkg to className.orEmpty())
    }

    /**
     * Nothing stays on screen across a shutdown, startup, screen-off or lock, even an app whose
     * pause was never recorded (e.g. it crashed); what is really shown resumes again afterwards.
     */
    fun reset() = resumed.clear()

    /** Applies [events], which must be oldest first. */
    fun replay(events: List<UsageEvent>) = apply {
        for (e in events) {
            when (e.kind) {
                UsageEvent.Kind.RESUMED -> resumed(e.pkg, e.time, e.className)
                UsageEvent.Kind.LEFT -> paused(e.pkg, e.className)
                UsageEvent.Kind.RESET -> reset()
            }
        }
    }

    /** The activity last resumed in [pkg], if known. */
    fun lastActivity(pkg: String): String? = lastActivity[pkg]

    /** An independent copy, so recent events can be applied without changing this one. */
    fun copy(): UsageHistory = UsageHistory().also {
        it.lastUsed.putAll(lastUsed)
        it.lastActivity.putAll(lastActivity)
        it.resumed.putAll(resumed)
    }

    /**
     * The (at most two, for split screen) most recently resumed apps still resumed. An app whose
     * pause was never recorded (e.g. it crashed) is cleared by its destroyed event if delivered,
     * or else by the next [reset] (screen-off or lock).
     */
    private fun onScreen(): Set<String> = resumed.entries
        .sortedByDescending { it.value }
        .map { it.key.first }
        .distinct()
        .take(MAX_ON_SCREEN)
        .toSet()

    /** Packages newest first, skipping on-screen, [excluded] and non-[launchable] ones. */
    fun pick(excluded: Set<String>, launchable: Set<String>): List<String> {
        val onScreen = onScreen()
        return lastUsed.entries
            .filter { (pkg, _) -> pkg !in onScreen && pkg !in excluded && pkg in launchable }
            .sortedByDescending { it.value }
            .map { it.key }
    }

    private companion object {
        const val MAX_ON_SCREEN = 2
    }
}
