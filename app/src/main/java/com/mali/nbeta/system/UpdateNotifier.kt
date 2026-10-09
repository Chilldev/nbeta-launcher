package com.mali.nbeta.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mali.nbeta.R
import com.mali.nbeta.data.update.Release
import com.mali.nbeta.ui.settings.SettingsActivity

object UpdateNotifier {
    private const val CHANNEL = "updates"

    fun notify(context: Context, release: Release) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, "updates").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_news)
            .setContentTitle(context.getString(R.string.update_available_title, release.versionName))
            .setContentText(context.getString(R.string.update_available_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(0x5550, n) }
    }
}
