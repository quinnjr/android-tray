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
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> UsageEvent.Kind.LEFT
                UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> UsageEvent.Kind.RESET
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

    fun stopped(pkg: String, className: String? = null) = paused(pkg, className)

    /** Nothing survives a shutdown or startup on screen, even without its pause being recorded. */
    fun shutdown() = resumed.clear()

    /** Applies [events], which must be oldest first. */
    fun replay(events: List<UsageEvent>) = apply {
        for (e in events) {
            when (e.kind) {
                UsageEvent.Kind.RESUMED -> resumed(e.pkg, e.time, e.className)
                UsageEvent.Kind.LEFT -> paused(e.pkg, e.className)
                UsageEvent.Kind.RESET -> shutdown()
            }
        }
    }

    /** The activity last resumed in [pkg], if known. */
    fun lastActivity(pkg: String): String? = lastActivity[pkg]

    /**
     * At most two apps (split screen) are really on screen; an older app still marked resumed
     * just never had its pause recorded (e.g. it crashed).
     */
    private fun onScreen(): Set<String> = resumed.entries
        .sortedByDescending { it.value }
        .map { it.key.first }
        .distinct()
        .take(MAX_ON_SCREEN)
        .toSet()

    /** Packages newest first, skipping on-screen, [excluded] and non-[launchable] ones. */
    fun pick(excluded: Set<String>, launchable: Set<String>, limit: Int = Int.MAX_VALUE): List<String> {
        val onScreen = onScreen()
        return lastUsed.entries
            .filter { (pkg, _) -> pkg !in onScreen && pkg !in excluded && pkg in launchable }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }

    private companion object {
        const val MAX_ON_SCREEN = 2
    }
}
