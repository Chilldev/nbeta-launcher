package com.mali.nbeta.system

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.widget.Toast
import com.mali.nbeta.R

/**
 * Fingerprint / face / screen-lock check before opening locked apps. Uses the platform prompt, so the credential
 * never touches Nbeta. A successful unlock is remembered briefly so a quick retry doesn't ask twice.
 */
object AppLock {
    private const val GRACE_MS = 30_000L
    private var unlockedAt = 0L

    fun available(activity: Activity): Boolean = activity.getSystemService(KeyguardManager::class.java).isDeviceSecure

    fun recentlyUnlocked() = SystemClock.elapsedRealtime() - unlockedAt < GRACE_MS

    fun relock() {
        unlockedAt = 0L
    }

    fun authenticate(activity: Activity, title: String, onSuccess: () -> Unit) {
        if (recentlyUnlocked()) return onSuccess()
        if (!available(activity)) {
            Toast.makeText(activity, R.string.lock_needs_screen_lock, Toast.LENGTH_LONG).show()
            return
        }
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle(activity.getString(R.string.lock_prompt_subtitle))
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        // A failure to even show the prompt must never open the app (or crash the launcher).
        runCatching { prompt(builder, activity, onSuccess) }.onFailure {
            android.util.Log.w("AppLock", "Prompt unavailable", it)
            Toast.makeText(activity, R.string.lock_needs_screen_lock, Toast.LENGTH_LONG).show()
        }
    }

    private fun prompt(builder: BiometricPrompt.Builder, activity: Activity, onSuccess: () -> Unit) {
        builder.build().authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    unlockedAt = SystemClock.elapsedRealtime()
                    onSuccess()
                }
            },
        )
    }
}
