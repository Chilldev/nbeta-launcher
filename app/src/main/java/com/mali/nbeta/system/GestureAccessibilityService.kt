package com.mali.nbeta.system

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Only used for global actions (lock screen, recents). It subscribes to no events and reads no content. */
class GestureAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        var instance: GestureAccessibilityService? = null
            private set
    }
}
