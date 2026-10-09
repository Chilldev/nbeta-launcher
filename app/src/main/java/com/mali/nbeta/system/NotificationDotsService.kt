package com.mali.nbeta.system

import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Publishes which packages (per profile) have notifications, for the dots on app icons. */
class NotificationDotsService : NotificationListenerService() {
    override fun onListenerConnected() = publish()
    override fun onListenerDisconnected() {
        dots.value = emptySet()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = publish()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = publish()

    private fun publish() {
        val um = getSystemService(UserManager::class.java)
        val active = try {
            activeNotifications.orEmpty()
        } catch (_: Exception) {
            return
        }
        dots.value = active.asSequence()
            .filter { !it.isOngoing && it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter { n -> ranking(n)?.canShowBadge() != false }
            .map { "${it.packageName}#${um.getSerialNumberForUser(it.user)}" }
            .toSet()
    }

    private fun ranking(sbn: StatusBarNotification): Ranking? {
        val r = Ranking()
        return if (currentRanking?.getRanking(sbn.key, r) == true) r else null
    }

    companion object {
        private val dots = MutableStateFlow<Set<String>>(emptySet())
        val packagesWithDots: StateFlow<Set<String>> = dots

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, NotificationDotsService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
