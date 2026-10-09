package com.mali.nbeta.data.feed

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mali.nbeta.R
import com.mali.nbeta.data.LauncherSettings
import com.mali.nbeta.data.LinkOpener
import com.mali.nbeta.ui.reader.ReaderActivity

/** Posts breaking-news notifications for new stories from alert sources or matching alert keywords. */
class NewsNotifier(private val context: Context) {
    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(items: List<FeedItem>, s: LauncherSettings) {
        if (items.isEmpty() || !canPost()) return
        ensureChannel()
        val nm = NotificationManagerCompat.from(context)
        val titles = s.feedSources.associate { it.id to it.title }
        for (item in items) {
            val open = if (s.linkOpener == LinkOpener.Reader) ReaderActivity.intent(context, item.link, item.id, titles[item.sourceId])
            else Intent(Intent.ACTION_VIEW, Uri.parse(item.link))
            val pi = PendingIntent.getActivity(
                context, item.id.hashCode(), open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_news)
                .setContentTitle(item.title)
                .setContentText(titles[item.sourceId] ?: item.summary)
                .setSubText(titles[item.sourceId])
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.summary ?: item.title))
                .setWhen(item.published)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setGroup(GROUP)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setContentIntent(pi)
                .build()
            runCatching { nm.notify(item.id.hashCode(), n) }
        }
        if (items.size > 1) {
            val summary = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_news)
                .setContentTitle(context.resources.getQuantityString(R.plurals.news_new_stories, items.size, items.size))
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(SUMMARY_ID, summary) }
        }
    }

    private fun ensureChannel() {
        // Re-registering an existing channel only updates its name and description (e.g. after a language change).
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.news_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.news_channel_description)
            },
        )
    }

    companion object {
        /** Picks what deserves an alert from freshly fetched items. */
        fun select(fresh: List<FeedItem>, s: LauncherSettings, alreadyNotified: Set<String>): List<FeedItem> {
            if (!s.newsAlerts) return emptyList()
            val keywords = KeywordMatcher(s.alertKeywords)
            val muted = KeywordMatcher(s.mutedKeywords)
            val recent = System.currentTimeMillis() - 3 * 3600_000L
            return fresh.asSequence()
                .filter { it.id !in alreadyNotified && it.published > recent && !muted.matches(it) }
                .filter { it.sourceId in s.alertSources || keywords.matches(it) }
                .sortedByDescending { it.published }
                .take(MAX_PER_REFRESH)
                .toList()
        }

        private const val CHANNEL = "news"
        private const val GROUP = "news"
        private const val SUMMARY_ID = 0x4e42
        private const val MAX_PER_REFRESH = 3
    }
}
