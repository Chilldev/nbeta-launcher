package com.mali.nbeta.data.apps

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.plus

@Immutable
data class AppShortcut(
    val info: ShortcutInfo,
    val label: String,
    val appLabel: String,
) {
    val key: String = "${info.`package`}/${info.id}#${info.userHandle.hashCode()}"
}

/**
 * App shortcuts (long-press menu entries and pinned "add to home screen" items). Requires being the default
 * launcher; every call degrades to empty results otherwise.
 */
class ShortcutRepository(
    private val context: Context,
    scope: CoroutineScope,
    private val apps: AppRepository,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    private val _all = MutableStateFlow<List<AppShortcut>>(emptyList())

    /** Index of every manifest/dynamic shortcut, for search. */
    val all: StateFlow<List<AppShortcut>> = _all

    init {
        apps.packageChanges.onStart { emit("") }.debounce(1500)
            .onEach { reindex() }
            .launchIn(scope + Dispatchers.IO)
    }

    fun hasPermission(): Boolean = try {
        launcherApps.hasShortcutHostPermission()
    } catch (_: Exception) {
        false
    }

    fun forApp(app: AppEntry): List<AppShortcut> {
        if (!hasPermission()) return emptyList()
        val q = LauncherApps.ShortcutQuery()
            .setPackage(app.packageName)
            .setActivity(app.component)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
        return query(q, app.user).sortedWith(compareBy({ !it.info.isDeclaredInManifest }, { it.info.rank }))
            .take(5)
            .map { AppShortcut(it.info, it.label, app.label) }
    }

    fun pinned(packageName: String, id: String, userSerial: Long): AppShortcut? {
        val user = userManager.getUserForSerialNumber(userSerial) ?: return null
        val q = LauncherApps.ShortcutQuery()
            .setPackage(packageName)
            .setShortcutIds(listOf(id))
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        return query(q, user).firstOrNull()
    }

    private fun query(q: LauncherApps.ShortcutQuery, user: android.os.UserHandle): List<AppShortcut> = try {
        launcherApps.getShortcuts(q, user).orEmpty().filter { it.isEnabled }.map {
            AppShortcut(it, (it.longLabel?.takeIf { l -> l.length <= 28 } ?: it.shortLabel ?: "").toString(), "")
        }
    } catch (e: Exception) {
        if (e !is SecurityException && e !is IllegalStateException) Log.w(TAG, "Shortcut query failed", e)
        emptyList()
    }

    private fun reindex() {
        if (!hasPermission()) return
        val byPackage = apps.apps.value.groupBy { it.packageName to it.userSerial }
        val out = ArrayList<AppShortcut>()
        for (profile in apps.profiles.value) {
            if (profile.quiet) continue
            val q = LauncherApps.ShortcutQuery()
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
            for (s in query(q, profile.user)) {
                val app = byPackage[s.info.`package` to profile.serial]?.firstOrNull() ?: continue
                out += s.copy(appLabel = app.label)
            }
        }
        _all.value = out
    }

    fun launch(shortcut: AppShortcut, bounds: Rect?, options: Bundle?) {
        try {
            launcherApps.startShortcut(shortcut.info, bounds, options)
        } catch (e: Exception) {
            Log.w(TAG, "Shortcut launch failed", e)
            Toast.makeText(context, "Shortcut unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    fun unpin(packageName: String, id: String, userSerial: Long) {
        val user = userManager.getUserForSerialNumber(userSerial) ?: return
        try {
            val q = LauncherApps.ShortcutQuery().setPackage(packageName).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val remaining = launcherApps.getShortcuts(q, user).orEmpty().map { it.id }.filter { it != id }
            launcherApps.pinShortcuts(packageName, remaining, user)
        } catch (e: Exception) {
            Log.w(TAG, "Unpin failed", e)
        }
    }

    companion object {
        private const val TAG = "Shortcuts"
    }
}
