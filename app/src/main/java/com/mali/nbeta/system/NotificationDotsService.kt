package com.mali.nbeta.system

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/**
 * Publishes which packages (per profile) have notifications, for the dots on app icons.
 *
 * Callbacks arrive on the launcher's main thread, so each event is an O(1) map update using the notification it
 * carries; only the initial snapshot calls getActiveNotifications (which parcels every notification), on a
 * background thread.
 */
class NotificationDotsService : NotificationListenerService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val byKey = HashMap<String, Pair<String, Int>>() // notification key -> ("package#userSerial", count)
    private val serials = HashMap<UserHandle, Long>()

    override fun onListenerConnected() {
        worker.execute {
            val active = try {
                activeNotifications.orEmpty()
            } catch (_: Exception) {
                return@execute
            }
            val ranking = currentRanking
            synchronized(byKey) {
                byKey.clear()
                for (sbn in active) if (counts(sbn, ranking)) byKey[sbn.key] = entry(sbn)
                publish()
            }
        }
    }

    override fun onListenerDisconnected() {
        synchronized(byKey) { byKey.clear() }
        dots.value = emptyMap()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        synchronized(byKey) {
            val changed = if (counts(sbn, rankingMap)) entry(sbn).let { byKey.put(sbn.key, it) != it } else byKey.remove(sbn.key) != null
            if (changed) publish()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        synchronized(byKey) { if (byKey.remove(sbn.key) != null) publish() }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun counts(sbn: StatusBarNotification, rankingMap: RankingMap?): Boolean {
        val n = sbn.notification
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val r = Ranking()
        return rankingMap?.getRanking(sbn.key, r) != true || r.canShowBadge()
    }

    private fun packageKey(sbn: StatusBarNotification): String {
        val serial = serials.getOrPut(sbn.user) { getSystemService(UserManager::class.java).getSerialNumberForUser(sbn.user) }
        return "${sbn.packageName}#$serial"
    }

    /** A messaging notification's own number (e.g. "5 new messages") counts for that many; otherwise one each. */
    private fun entry(sbn: StatusBarNotification) = packageKey(sbn) to sbn.notification.number.coerceAtLeast(1)

    private fun publish() {
        val next = HashMap<String, Int>()
        for ((pkg, n) in byKey.values) next[pkg] = (next[pkg] ?: 0) + n
        if (next != dots.value) dots.value = next
    }

    companion object {
        private val dots = MutableStateFlow<Map<String, Int>>(emptyMap())
        /** "package#userSerial" -> number of notifications. */
        val packagesWithDots: StateFlow<Map<String, Int>> = dots

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, NotificationDotsService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
