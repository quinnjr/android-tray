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

    /** Activity resumes and pauses between [since] and [now]. */
    internal fun history(since: Long, now: Long): UsageHistory {
        val history = UsageHistory()
        val events = usage.queryEvents(since, now) ?: return history
        val event = UsageEvents.Event()
        while (events.getNextEvent(event)) {
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> history.resumed(event.packageName, event.timeStamp, event.className)
                UsageEvents.Event.ACTIVITY_PAUSED -> history.paused(event.packageName)
                UsageEvents.Event.ACTIVITY_STOPPED -> history.stopped(event.packageName)
                UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> history.shutdown()
            }
        }
        return history
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

/**
 * Each app's latest use and the activity used, plus which apps are still on screen (resumed and
 * not yet paused) — the app the drawer was opened over, or both halves of a split screen.
 */
internal class UsageHistory {
    private val lastUsed = HashMap<String, Long>()
    private val lastActivity = HashMap<String, String>()
    private val resumed = HashSet<String>()

    fun resumed(pkg: String, time: Long, className: String? = null) {
        if (time >= (lastUsed[pkg] ?: Long.MIN_VALUE)) {
            lastUsed[pkg] = time
            if (className != null) lastActivity[pkg] = className
        }
        resumed += pkg
    }

    fun paused(pkg: String) {
        resumed -= pkg
    }

    fun stopped(pkg: String) = paused(pkg)

    /** Nothing survives a shutdown or startup on screen, even without its pause being recorded. */
    fun shutdown() = resumed.clear()

    /** The activity last resumed in [pkg], if known. */
    fun lastActivity(pkg: String): String? = lastActivity[pkg]

    /**
     * At most two apps (split screen) are really on screen; an older app still marked resumed
     * just never had its pause recorded (e.g. it crashed).
     */
    private fun onScreen(): Set<String> =
        resumed.sortedByDescending { lastUsed[it] ?: Long.MIN_VALUE }.take(MAX_ON_SCREEN).toSet()

    /** Up to [limit] packages, newest first, skipping on-screen, [excluded] and non-[launchable] ones. */
    fun pick(excluded: Set<String>, launchable: Set<String>, limit: Int): List<String> {
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
