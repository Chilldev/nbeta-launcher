package com.mali.nbeta.data.apps

import com.mali.nbeta.system.DiagLog
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.UserManager
import android.widget.Toast
import androidx.compose.runtime.Immutable
import com.mali.nbeta.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.plus

@Immutable
data class AppShortcut(
    val info: ShortcutInfo,
    val label: String,
    val appLabel: String,
) {
    /** A chat with a person or group (WhatsApp, Slack, Telegram, Messages…), as opposed to an app action. */
    val isConversation: Boolean = info.categories?.contains("android.shortcut.conversation") == true ||
        (android.os.Build.VERSION.SDK_INT >= 30 && info.isCached)

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

    private val reindexRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    @Volatile private var hadPermission = false

    init {
        // Package changes, profile list changes (e.g. after boot) and gaining the home role all rebuild the index.
        merge(apps.packageChanges, apps.profiles.map { "" }, reindexRequests)
            .debounce(1000)
            .onEach { reindex() }
            .launchIn(scope + Dispatchers.IO)
    }

    /** Call on resume: the shortcut permission appears only once the user picks Nbeta as home. */
    fun checkPermission() {
        if (!hadPermission && hasPermission()) reindexRequests.tryEmit("")
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
        if (e !is SecurityException && e !is IllegalStateException) DiagLog.w(TAG, "Shortcut query failed", e)
        emptyList()
    }

    private fun reindex() {
        hadPermission = hasPermission()
        if (!hadPermission) return
        val byPackage = apps.apps.value.groupBy { it.packageName to it.userSerial }
        val out = ArrayList<AppShortcut>()
        for (profile in apps.profiles.value) {
            if (profile.quiet) continue
            // Cached = conversation shortcuts apps keep for recent chats even after removing them from the dynamic list.
            val cached = if (android.os.Build.VERSION.SDK_INT >= 30) LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED else 0
            val q = LauncherApps.ShortcutQuery()
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or cached)
            for (s in query(q, profile.user).distinctBy { it.key }) {
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
            DiagLog.w(TAG, "Shortcut launch failed", e)
            Toast.makeText(context, R.string.home_shortcut_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    fun unpin(packageName: String, id: String, userSerial: Long) {
        val user = userManager.getUserForSerialNumber(userSerial) ?: return
        try {
            val q = LauncherApps.ShortcutQuery().setPackage(packageName).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val remaining = launcherApps.getShortcuts(q, user).orEmpty().map { it.id }.filter { it != id }
            launcherApps.pinShortcuts(packageName, remaining, user)
        } catch (e: Exception) {
            DiagLog.w(TAG, "Unpin failed", e)
        }
    }

    companion object {
        private const val TAG = "Shortcuts"
    }
}
