package com.mali.nbeta.system

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Sends another app's PendingIntent from the visible launcher. Since Android 14 a sender targeting 34+ has to opt in to
 * lend its foreground status, or the target activity (a chat, a player) silently fails to open.
 */
fun PendingIntent.sendFromLauncher(context: Context, fill: Intent? = null): Boolean = try {
    val opts = if (Build.VERSION.SDK_INT >= 34) {
        ActivityOptions.makeBasic().apply {
            @Suppress("DEPRECATION")
            setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }.toBundle()
    } else null
    send(context, 0, fill, null, null, null, opts)
    true
} catch (e: Exception) {
    Log.w("PendingIntents", "PendingIntent failed", e)
    false
}
