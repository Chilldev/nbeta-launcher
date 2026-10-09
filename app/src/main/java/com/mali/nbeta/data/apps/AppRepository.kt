package com.mali.nbeta.data.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import com.mali.nbeta.R
import com.mali.nbeta.data.HomeItem
import com.mali.nbeta.data.JsonStore
import com.mali.nbeta.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.text.Collator

enum class ProfileKind { Main, Work, Private, Clone }

@Immutable
data class AppEntry(
    val key: String,
    val component: ComponentName,
    val user: UserHandle,
    val userSerial: Long,
    val label: String,
    val originalLabel: String,
    val profile: ProfileKind,
    /** Changes whenever the APK changes; part of the icon cache key. */
    val version: Long,
) {
    val packageName: String get() = component.packageName
    val packageKey: String get() = "$packageName#$userSerial"
}

@Immutable
data class ProfileState(val user: UserHandle, val serial: Long, val kind: ProfileKind, val quiet: Boolean)

object AppKeys {
    fun of(component: ComponentName, serial: Long) = "${component.flattenToShortString()}#$serial"
    fun component(key: String): ComponentName? = ComponentName.unflattenFromString(key.substringBefore('#'))
}

@Serializable
private data class CachedApp(val c: String, val s: Long, val l: String, val k: ProfileKind, val v: Long)

@Serializable
data class LaunchStat(val count: Int = 0, val last: Long = 0, /** launches per hour of day */ val hours: List<Int> = emptyList())

class AppRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val myUser = Process.myUserHandle()
    private val snapshotFile = File(context.filesDir, "apps.json")
    private val snapshotSerializer = ListSerializer(CachedApp.serializer())

    private val raw = MutableStateFlow<List<AppEntry>>(emptyList())
    private val _profiles = MutableStateFlow<List<ProfileState>>(emptyList())
    val profiles: StateFlow<List<ProfileState>> = _profiles

    /** True once the real (non-cached) list has been read from the system. */
    val fresh = MutableStateFlow(false)

    val stats = JsonStore(
        File(context.filesDir, "launch_stats.json"),
        MapSerializer(String.serializer(), LaunchStat.serializer()),
        { emptyMap() },
        scope,
        debounceMs = 2000,
    )

    /** Every launchable activity across profiles, with user renames applied, sorted by label. */
    val apps: StateFlow<List<AppEntry>> = combine(
        raw,
        settings.flow.map { it.renamedApps }.distinctUntilChanged(),
    ) { list, renames ->
        if (renames.isEmpty()) list else list.map { a -> renames[a.key]?.let { a.copy(label = it) } ?: a }.sortedWith(labelOrder)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** What the drawer and search show. */
    val visibleApps: StateFlow<List<AppEntry>> = combine(
        apps,
        settings.flow.map { it.hiddenApps }.distinctUntilChanged(),
    ) { list, hidden -> if (hidden.isEmpty()) list else list.filterNot { it.key in hidden } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val byKey: StateFlow<Map<String, AppEntry>> = apps.map { l -> l.associateBy { it.key } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
    private val labelOrder = Comparator<AppEntry> { a, b -> collator.compare(a.label, b.label) }

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val packageChanges = MutableSharedFlow<String>(extraBufferCapacity = 16)

    fun start() {
        scope.launch(Dispatchers.IO) {
            loadSnapshot()
            refresh()
        }
        refreshRequests.debounce(250).onEach { refresh() }.launchIn(scope + Dispatchers.IO)
        // "Reset home layout" clears the flag; re-seed the defaults right away.
        settings.flow.map { it.layoutInitialized }.distinctUntilChanged().drop(1)
            .onEach { initialized -> if (!initialized) requestRefresh() }
            .launchIn(scope)
        launcherApps.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
            addAction("android.intent.action.PROFILE_AVAILABLE")
            addAction("android.intent.action.PROFILE_UNAVAILABLE")
            addAction("android.intent.action.PROFILE_ADDED")
            addAction("android.intent.action.PROFILE_REMOVED")
        }
        ContextCompat.registerReceiver(context, profileReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun requestRefresh() {
        refreshRequests.tryEmit(Unit)
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = packageNames.forEach(::changed)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = packageNames.forEach(::changed)
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = packageNames.forEach(::changed)
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = packageNames.forEach(::changed)
        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<android.content.pm.ShortcutInfo>, user: UserHandle) {
            packageChanges.tryEmit(packageName)
        }
    }

    private fun changed(packageName: String) {
        packageChanges.tryEmit(packageName)
        requestRefresh()
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = requestRefresh()
    }

    private fun loadSnapshot() {
        if (raw.value.isNotEmpty() || !snapshotFile.exists()) return
        try {
            val cached = com.mali.nbeta.data.AppJson.decodeFromString(snapshotSerializer, snapshotFile.readText())
            val users = HashMap<Long, UserHandle?>()
            val list = cached.mapNotNull { c ->
                val user = users.getOrPut(c.s) { userManager.getUserForSerialNumber(c.s) } ?: return@mapNotNull null
                val cn = ComponentName.unflattenFromString(c.c) ?: return@mapNotNull null
                AppEntry(AppKeys.of(cn, c.s), cn, user, c.s, c.l, c.l, c.k, c.v)
            }
            if (raw.value.isEmpty()) raw.value = list
        } catch (e: Exception) {
            Log.w(TAG, "Bad app snapshot", e)
        }
    }

    @Synchronized
    private fun refresh() {
        val start = System.nanoTime()
        val profileStates = launcherApps.profiles.map { user ->
            val serial = userManager.getSerialNumberForUser(user)
            ProfileState(user, serial, kindOf(user), user != myUser && userManager.isQuietModeEnabled(user))
        }
        val list = ArrayList<AppEntry>(256)
        for (p in profileStates) {
            // A locked private space must not reveal its apps.
            if (p.kind == ProfileKind.Private && p.quiet) continue
            val activities = try {
                launcherApps.getActivityList(null, p.user)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot list apps for ${p.user}", e)
                continue
            }
            for (info in activities) {
                val ai = info.applicationInfo
                val version = (ai.sourceDir?.let { File(it).lastModified() } ?: 0L) xor info.firstInstallTime
                val label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName }
                list += AppEntry(AppKeys.of(info.componentName, p.serial), info.componentName, p.user, p.serial, label, label, p.kind, version)
            }
        }
        list.sortWith(labelOrder)
        _profiles.value = profileStates
        raw.value = list
        fresh.value = true
        Log.d(TAG, "Loaded ${list.size} apps in ${(System.nanoTime() - start) / 1_000_000} ms")
        saveSnapshot(list)
        if (!settings.value.layoutInitialized) initDefaultLayout(list)
    }

    private fun saveSnapshot(list: List<AppEntry>) {
        try {
            val json = com.mali.nbeta.data.AppJson.encodeToString(
                snapshotSerializer,
                list.map { CachedApp(it.component.flattenToShortString(), it.userSerial, it.originalLabel, it.profile, it.version) },
            )
            val tmp = File(snapshotFile.parentFile, snapshotFile.name + ".tmp")
            tmp.writeText(json)
            tmp.renameTo(snapshotFile)
        } catch (e: Exception) {
            Log.w(TAG, "Could not save app snapshot", e)
        }
    }

    private fun kindOf(user: UserHandle): ProfileKind {
        if (user == myUser) return ProfileKind.Main
        if (Build.VERSION.SDK_INT >= 35) {
            val type = try {
                launcherApps.getLauncherUserInfo(user)?.userType
            } catch (_: Exception) {
                null
            }
            return when (type) {
                "android.os.usertype.profile.PRIVATE" -> ProfileKind.Private
                "android.os.usertype.profile.CLONE" -> ProfileKind.Clone
                else -> ProfileKind.Work
            }
        }
        return ProfileKind.Work
    }

    /** Pause/resume a work profile or lock/unlock private space. Only the default launcher may do this. */
    fun setQuietMode(profile: ProfileState, quiet: Boolean): Boolean = try {
        userManager.requestQuietModeEnabled(quiet, profile.user).also { requestRefresh() }
    } catch (e: Exception) {
        Log.w(TAG, "Quiet mode change refused", e)
        Toast.makeText(context, R.string.home_set_default_first, Toast.LENGTH_SHORT).show()
        false
    }

    fun launch(app: AppEntry, bounds: Rect?, options: Bundle?) {
        try {
            launcherApps.startMainActivity(app.component, app.user, bounds, options)
            recordLaunch(app.key)
        } catch (e: Exception) {
            Log.w(TAG, "Launch failed for ${app.key}", e)
            Toast.makeText(context, context.getString(R.string.home_couldnt_open, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    fun recordLaunch(key: String) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        stats.update { m ->
            val s = m[key] ?: LaunchStat()
            val hours = s.hours.ifEmpty { List(24) { 0 } }.toMutableList().also { it[hour] += 1 }
            m + (key to LaunchStat(s.count + 1, System.currentTimeMillis(), hours))
        }
    }

    val usage = UsageHistory(context)

    /**
     * Predicted apps for right now, like Pixel's suggestion row: how often and how recently you use an app, weighted
     * by how much of that use falls around this hour of the day. Launch history from Usage access (if granted)
     * fills in until Nbeta has its own.
     */
    fun suggestions(candidates: List<AppEntry>, count: Int, now: Long = System.currentTimeMillis()): List<AppEntry> {
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
        val st = stats.value
        val history = usage.byPackage()
        fun nearHour(h: List<Int>, total: Int): Double {
            if (h.size != 24 || total <= 0) return 0.0
            return (h[(hour + 23) % 24] + h[hour] * 2 + h[(hour + 1) % 24]).toDouble() / (total * 2)
        }
        return candidates.mapNotNull { app ->
            val own = st[app.key]
            val ownScore = own?.let { frecency(app.key, now) * (0.4 + nearHour(it.hours, it.count)) } ?: 0.0
            val h = history[app.packageName]
            val usageScore = h?.let { (it.launches / 14.0) * (0.4 + nearHour(it.hours, it.launches)) * 0.6 } ?: 0.0
            val score = ownScore + usageScore
            if (score > 0.0) app to score else null
        }.sortedByDescending { it.second }.take(count).map { it.first }
    }

    fun openAppInfo(app: AppEntry) {
        try {
            launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "App info failed", e)
        }
    }

    fun uninstall(app: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
            .putExtra(Intent.EXTRA_USER, app.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Uninstall failed", e)
        }
    }

    fun isSystemApp(app: AppEntry): Boolean = try {
        val ai = launcherApps.getApplicationInfo(app.packageName, 0, app.user)
        ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
    } catch (_: Exception) {
        false
    }

    /** Frecency: frequent and recent launches rank higher; used for suggestions and search ties. */
    fun frecency(key: String, now: Long = System.currentTimeMillis()): Double {
        val s = stats.value[key] ?: return 0.0
        val ageHours = (now - s.last) / 3_600_000.0
        return s.count * (1.0 / (1.0 + ageHours / 72.0))
    }

    private fun initDefaultLayout(list: List<AppEntry>) {
        val pm = context.packageManager
        val main = list.filter { it.profile == ProfileKind.Main }
        fun forIntent(intent: Intent): String? {
            val pkg = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName ?: return null
            if (pkg == "android") return null // resolver: no default chosen
            return main.firstOrNull { it.packageName == pkg }?.key
        }
        val dock = listOfNotNull(
            forIntent(Intent(Intent.ACTION_DIAL)),
            forIntent(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))),
            forIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)),
            forIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
        ).distinct()
        val preferred = listOf(
            "com.google.android.gm", "com.google.android.apps.maps", "com.google.android.apps.photos", "com.google.android.youtube",
            "com.android.vending", "com.google.android.calendar", "com.whatsapp", "com.android.settings",
        )
        val home = preferred.mapNotNull { p -> main.firstOrNull { it.packageName == p }?.key }.filterNot { it in dock }.take(8)
        settings.update { s ->
            if (s.layoutInitialized) s else s.copy(
                dockItems = dock.map { HomeItem.App(it) },
                pages = listOf(home.map { HomeItem.App(it) }),
                layoutInitialized = true,
            )
        }
    }

    companion object {
        private const val TAG = "AppRepository"
    }
}
