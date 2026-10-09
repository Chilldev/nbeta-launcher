package com.mali.nbeta.ui.feed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession

/**
 * Keeps a warmed-up Custom Tabs session while the feed is on screen: the browser process is started ahead of time
 * and the top story is hinted with mayLaunchUrl, so opening an article skips most of the browser's cold start.
 */
class CustomTabs(private val context: Context) {
    private var session: CustomTabsSession? = null
    private var bound = false
    private val connection = object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
            client.warmup(0)
            session = client.newSession(null)
            pending?.let { mayLaunch(it) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            session = null
            bound = false
        }
    }
    private var pending: String? = null

    fun warmup() {
        if (bound) return
        val pkg = CustomTabsClient.getPackageName(context, null) ?: return
        bound = runCatching { CustomTabsClient.bindCustomTabsService(context, pkg, connection) }.getOrDefault(false)
    }

    fun mayLaunch(url: String) {
        val s = session
        if (s == null) {
            pending = url
            return
        }
        pending = null
        runCatching { s.mayLaunchUrl(Uri.parse(url), null, null) }
    }

    fun open(url: String, dark: Boolean, useCustomTab: Boolean) {
        val uri = Uri.parse(url)
        if (!useCustomTab) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            return
        }
        try {
            CustomTabsIntent.Builder(session)
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_ON)
                .setUrlBarHidingEnabled(true)
                .setColorScheme(if (dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
                .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().build())
                .build()
                .launchUrl(context, uri)
        } catch (_: Exception) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
    }

    fun unbind() {
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        session = null
    }
}
