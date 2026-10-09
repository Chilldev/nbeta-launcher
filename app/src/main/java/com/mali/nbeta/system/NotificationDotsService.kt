package com.mali.nbeta.system

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

private val markReadTitle = Regex("(?i)mark.{0,8}read|^read$|مقروء|تمت القراءة")

/** One notification as the launcher shows it. Holds the original for its intents and actions; never persisted. */
@Immutable
class NotifItem(
    val key: String,
    val packageKey: String,
    val title: String,
    val text: String,
    val time: Long,
    val largeIcon: Icon?,
    val count: Int,
    internal val sbn: StatusBarNotification,
) {
    val reply: Notification.Action? = sbn.notification.actions?.firstOrNull { a ->
        a.remoteInputs?.any { it.allowFreeFormInput } == true
    }
    val markRead: Notification.Action? = sbn.notification.actions?.firstOrNull { a ->
        (Build.VERSION.SDK_INT >= 28 && a.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) ||
            a.title?.toString()?.let { markReadTitle.containsMatchIn(it) } == true
    }
    val canOpen get() = sbn.notification.contentIntent != null
    val dismissable get() = sbn.isClearable
}

/**
 * Notification dots, counts and the messages shown in app menus and search.
 *
 * Callbacks arrive on the launcher's main thread, so each event is an O(1) map update using the notification it
 * carries; only the initial snapshot calls getActiveNotifications (which parcels every notification), on a
 * background thread. Notification content stays in memory only.
 */
class NotificationDotsService : NotificationListenerService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val byKey = LinkedHashMap<String, NotifItem>()
    private val serials = HashMap<UserHandle, Long>()

    override fun onListenerConnected() {
        instance = WeakReference(this)
        worker.execute {
            val active = try {
                activeNotifications.orEmpty()
            } catch (_: Exception) {
                return@execute
            }
            val ranking = currentRanking
            synchronized(byKey) {
                byKey.clear()
                for (sbn in active) if (counts(sbn, ranking)) byKey[sbn.key] = item(sbn)
                publish()
            }
        }
    }

    override fun onListenerDisconnected() {
        synchronized(byKey) { byKey.clear() }
        dots.value = emptyMap()
        items.value = emptyList()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        synchronized(byKey) {
            if (counts(sbn, rankingMap)) byKey[sbn.key] = item(sbn) else byKey.remove(sbn.key)
            publish()
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

    private fun item(sbn: StatusBarNotification): NotifItem {
        val n = sbn.notification
        val extras = n.extras
        // Messaging-style notifications carry the conversation; show its newest message with the sender.
        val messages = (extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: emptyArray()).mapNotNull { it as? Bundle }
        val last = messages.lastOrNull()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val title = conversation ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val sender = last?.getCharSequence("sender")?.toString()
        val text = last?.getCharSequence("text")?.let { t -> if (conversation != null && sender != null) "$sender: $t" else t.toString() }
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return NotifItem(
            key = sbn.key,
            packageKey = packageKey(sbn),
            title = title,
            text = text,
            time = sbn.postTime,
            largeIcon = n.getLargeIcon(),
            // A messaging notification's own number (e.g. "5 new messages") counts for that many; otherwise one each.
            count = n.number.coerceAtLeast(1),
            sbn = sbn,
        )
    }

    private fun publish() {
        val nextDots = HashMap<String, Int>()
        for (it in byKey.values) nextDots[it.packageKey] = (nextDots[it.packageKey] ?: 0) + it.count
        if (nextDots != dots.value) dots.value = nextDots
        items.value = byKey.values.sortedByDescending { it.time }
    }

    companion object {
        private const val TAG = "Notifications"
        private val dots = MutableStateFlow<Map<String, Int>>(emptyMap())
        private val items = MutableStateFlow<List<NotifItem>>(emptyList())
        private var instance: WeakReference<NotificationDotsService>? = null

        /** "package#userSerial" -> number of notifications. */
        val packagesWithDots: StateFlow<Map<String, Int>> = dots

        /** Current notifications, newest first. */
        val notifications: StateFlow<List<NotifItem>> = items

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, NotificationDotsService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Sends a notification's PendingIntent from the (visible) launcher, allowed to start the target activity. */
        private fun send(context: Context, pi: PendingIntent, fill: Intent? = null): Boolean = try {
            val opts = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic().apply {
                    @Suppress("DEPRECATION")
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }.toBundle()
            } else null
            pi.send(context, 0, fill, null, null, null, opts)
            true
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent failed", e)
            false
        }

        /** Opens the notification's target (usually the exact chat) and clears it if the app asked for that. */
        fun open(context: Context, item: NotifItem): Boolean {
            val pi = item.sbn.notification.contentIntent ?: return false
            val ok = send(context, pi)
            if (ok && item.sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) dismiss(item)
            return ok
        }

        /** Inline reply through the app's own reply action (works for WhatsApp, Slack, Telegram, Messages…). */
        fun reply(context: Context, item: NotifItem, text: String): Boolean {
            val action = item.reply ?: return false
            val inputs = action.remoteInputs ?: return false
            val intent = Intent()
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            return send(context, action.actionIntent, intent)
        }

        fun markRead(context: Context, item: NotifItem): Boolean {
            val action = item.markRead ?: return false
            return send(context, action.actionIntent)
        }

        fun dismiss(item: NotifItem) {
            runCatching { instance?.get()?.cancelNotification(item.key) }
        }
    }
}
