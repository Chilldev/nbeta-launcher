package com.mali.nbeta.data.apps

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Two weeks of app launches from the system's usage history, by hour of day. Needs "Usage access"; without it
 * everything here is empty and suggestions rely on Nbeta's own launch history.
 */
class UsageHistory(private val context: Context) {
    class Usage(val launches: Int, val hours: List<Int>)

    @Volatile private var cache: Map<String, Usage> = emptyMap()
    @Volatile private var loadedAt = 0L

    fun granted(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** Cheap to call: recomputed at most every few hours, on the calling (background) thread. */
    fun byPackage(): Map<String, Usage> {
        val now = System.currentTimeMillis()
        if (now - loadedAt < 3 * 3600_000L) return cache
        loadedAt = now
        if (!granted()) return emptyMap<String, Usage>().also { cache = it }
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val counts = HashMap<String, IntArray>()
        val cal = java.util.Calendar.getInstance()
        runCatching {
            val events = usm.queryEvents(now - 14L * 86_400_000L, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                cal.timeInMillis = e.timeStamp
                counts.getOrPut(e.packageName) { IntArray(24) }[cal.get(java.util.Calendar.HOUR_OF_DAY)]++
            }
        }
        cache = counts.mapValues { (_, h) -> Usage(h.sum(), h.toList()) }
        return cache
    }
}
