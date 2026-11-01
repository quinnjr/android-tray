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
                UsageEvents.Event.ACTIVITY_RESUMED -> history.resumed(event.packageName, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED -> history.paused(event.packageName)
            }
        }
        return history
    }

    /** The home screen app, which is always just behind the drawer and never worth listing. */
    fun homePackage(): String? = context.packageManager
        .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
}

/**
 * Each app's latest use, plus which apps are still on screen (resumed and not yet paused) —
 * the app the drawer was opened over, or both halves of a split screen.
 */
internal class UsageHistory {
    private val lastUsed = HashMap<String, Long>()
    private val onScreen = HashSet<String>()

    fun resumed(pkg: String, time: Long) {
        if (time > (lastUsed[pkg] ?: Long.MIN_VALUE)) lastUsed[pkg] = time
        onScreen += pkg
    }

    fun paused(pkg: String) {
        onScreen -= pkg
    }

    /** Up to [limit] packages, newest first, skipping on-screen, [excluded] and non-[launchable] ones. */
    fun pick(excluded: Set<String>, launchable: Set<String>, limit: Int): List<String> = lastUsed.entries
        .filter { (pkg, _) -> pkg !in onScreen && pkg !in excluded && pkg in launchable }
        .sortedByDescending { it.value }
        .take(limit)
        .map { it.key }
}
