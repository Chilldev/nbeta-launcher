package com.mali.nbeta.system

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast

object GlobalActions {
    @SuppressLint("WrongConstant")
    fun expandNotifications(context: Context) = statusBar(context, "expandNotificationsPanel")

    @SuppressLint("WrongConstant")
    fun expandQuickSettings(context: Context) = statusBar(context, "expandSettingsPanel")

    private fun statusBar(context: Context, method: String) {
        try {
            val service = context.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod(method).invoke(service)
        } catch (e: Exception) {
            Log.w("GlobalActions", "$method unavailable", e)
        }
    }

    fun lockScreen(context: Context): Boolean = perform(context, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)

    fun recents(context: Context): Boolean = perform(context, AccessibilityService.GLOBAL_ACTION_RECENTS)

    private fun perform(context: Context, action: Int): Boolean {
        val svc = GestureAccessibilityService.instance
        if (svc != null) return svc.performGlobalAction(action)
        Toast.makeText(context, "Turn on “Nbeta gestures” in Accessibility to use this gesture", Toast.LENGTH_LONG).show()
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }
}
