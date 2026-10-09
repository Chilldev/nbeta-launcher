package com.mali.nbeta.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.mali.nbeta.NbetaApp

/** Install session results: asks the user to confirm when Android requires it, reports failures. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the process is replaced; nothing to do
            else -> {
                val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed ($status)"
                val msg = when {
                    "VERIFICATION_FAILURE" in raw -> context.getString(com.mali.nbeta.R.string.update_blocked_play_protect)
                    status == PackageInstaller.STATUS_FAILURE_ABORTED -> context.getString(com.mali.nbeta.R.string.update_cancelled)
                    else -> raw
                }
                (context.applicationContext as NbetaApp).graph.updater.failed(msg)
            }
        }
    }
}
