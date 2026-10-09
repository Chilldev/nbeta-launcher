package com.mali.nbeta.data.search

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Patterns
import androidx.compose.runtime.Immutable
import com.mali.nbeta.data.SettingsRepository
import com.mali.nbeta.data.apps.AppEntry
import com.mali.nbeta.data.apps.AppRepository
import com.mali.nbeta.data.apps.AppShortcut
import com.mali.nbeta.data.apps.ShortcutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.net.URLEncoder

@Immutable
data class ContactHit(val id: Long, val lookupKey: String, val name: String, val photo: String?, val phone: String?)

@Immutable
data class SettingHit(val label: String, val action: String)

@Immutable
sealed interface WebHit {
    val title: String

    data class Url(val url: String) : WebHit {
        override val title get() = url
    }

    data class Search(val query: String, val engine: String, val url: String) : WebHit {
        override val title get() = "Search $engine for “$query”"
    }

    data class Store(val query: String) : WebHit {
        override val title get() = "Search Play Store for “$query”"
    }
}

@Immutable
data class SearchResults(
    val query: String,
    val apps: List<AppEntry> = emptyList(),
    val calc: String? = null,
    val shortcuts: List<AppShortcut> = emptyList(),
    val contacts: List<ContactHit> = emptyList(),
    val settings: List<SettingHit> = emptyList(),
    val web: List<WebHit> = emptyList(),
) {
    val isEmpty get() = apps.isEmpty() && calc == null && shortcuts.isEmpty() && contacts.isEmpty() && settings.isEmpty()
}

class SearchEngine(
    private val context: Context,
    scope: CoroutineScope,
    private val apps: AppRepository,
    private val shortcuts: ShortcutRepository,
    private val settings: SettingsRepository,
) {
    private class IndexedApp(val app: AppEntry, val s: Searchable)
    private class IndexedShortcut(val sc: AppShortcut, val s: Searchable)

    // Rebuilt only when the underlying lists change, so a keystroke is a linear scan over prepared strings.
    private val appIndex: StateFlow<List<IndexedApp>> = apps.visibleApps
        .map { l -> l.map { IndexedApp(it, Searchable(it.label, it.packageName)) } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val shortcutIndex: StateFlow<List<IndexedShortcut>> = shortcuts.all
        .map { l -> l.map { IndexedShortcut(it, Searchable(it.label, it.appLabel)) } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val settingsIndex by lazy { SYSTEM_SETTINGS.map { it to Searchable(it.label) } }

    suspend fun search(raw: String): SearchResults = coroutineScope {
        val query = raw.trim()
        if (query.isEmpty()) return@coroutineScope SearchResults(raw)
        val q = TextFold.fold(query)
        val cfg = settings.value
        val contactsJob = if (cfg.searchContacts && query.length >= 2 && hasContacts()) async(Dispatchers.IO) { contacts(query) } else null

        val now = System.currentTimeMillis()
        val appHits = appIndex.value.mapNotNull { ia ->
            val sc = Matcher.score(q, ia.s)
            if (sc == 0) null else ia.app to sc + (apps.frecency(ia.app.key, now) * 8).coerceAtMost(90.0).toInt()
        }.sortedByDescending { it.second }.take(12).map { it.first }

        val shortcutHits = if (cfg.searchShortcuts && q.length >= 2) {
            shortcutIndex.value.mapNotNull { s -> Matcher.score(q, s.s).takeIf { it >= 600 }?.let { s.sc to it } }
                .sortedByDescending { it.second }.take(5).map { it.first }
        } else emptyList()

        val settingHits = if (cfg.searchSettings && q.length >= 3) {
            settingsIndex.mapNotNull { (h, s) -> Matcher.score(q, s).takeIf { it >= 600 }?.let { h to it } }
                .sortedByDescending { it.second }.take(3).map { it.first }
        } else emptyList()

        val calc = if (cfg.searchCalculator) Calculator.evaluate(query) else null

        val web = buildList {
            if (looksLikeUrl(query)) add(WebHit.Url(if (query.contains("://")) query else "https://$query"))
            val enc = URLEncoder.encode(query, "UTF-8")
            add(WebHit.Search(query, cfg.webEngine.label, cfg.webEngine.template.replace("%s", enc)))
            add(WebHit.Store(query))
        }

        SearchResults(
            query = raw,
            apps = appHits,
            calc = calc,
            shortcuts = shortcutHits,
            contacts = contactsJob?.await().orEmpty(),
            settings = settingHits,
            web = web,
        )
    }

    private fun looksLikeUrl(q: String) = !q.contains(' ') && q.contains('.') && Patterns.WEB_URL.matcher(q).matches()

    private fun hasContacts() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private suspend fun contacts(query: String): List<ContactHit> = withContext(Dispatchers.IO) {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
        val proj = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
        )
        val out = ArrayList<ContactHit>()
        try {
            context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < 4) {
                    val id = c.getLong(0)
                    val phone = if (c.getInt(4) > 0) firstPhone(id) else null
                    out += ContactHit(id, c.getString(1) ?: "", c.getString(2) ?: continue, c.getString(3), phone)
                }
            }
        } catch (_: Exception) {
        }
        out
    }

    private fun firstPhone(contactId: Long): String? = try {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC",
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    fun webIntent(hit: WebHit): Intent = when (hit) {
        is WebHit.Url -> Intent(Intent.ACTION_VIEW, Uri.parse(hit.url))
        is WebHit.Search -> Intent(Intent.ACTION_VIEW, Uri.parse(hit.url))
        is WebHit.Store -> Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(hit.query)}"))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        val SYSTEM_SETTINGS = listOf(
            SettingHit("Wi‑Fi", Settings.ACTION_WIFI_SETTINGS),
            SettingHit("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
            SettingHit("Mobile network", Settings.ACTION_DATA_ROAMING_SETTINGS),
            SettingHit("Network & internet", Settings.ACTION_WIRELESS_SETTINGS),
            SettingHit("Hotspot & tethering", "android.settings.TETHER_SETTINGS"),
            SettingHit("Airplane mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
            SettingHit("Display & brightness", Settings.ACTION_DISPLAY_SETTINGS),
            SettingHit("Wallpaper", Intent.ACTION_SET_WALLPAPER),
            SettingHit("Sound & vibration", Settings.ACTION_SOUND_SETTINGS),
            SettingHit("Battery", Intent.ACTION_POWER_USAGE_SUMMARY),
            SettingHit("Apps", Settings.ACTION_APPLICATION_SETTINGS),
            SettingHit("Default apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            SettingHit("Notifications", "android.settings.ALL_APPS_NOTIFICATION_SETTINGS"),
            SettingHit("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            SettingHit("Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            SettingHit("Security & privacy", Settings.ACTION_SECURITY_SETTINGS),
            SettingHit("Privacy", Settings.ACTION_PRIVACY_SETTINGS),
            SettingHit("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS),
            SettingHit("Date & time", Settings.ACTION_DATE_SETTINGS),
            SettingHit("Language & input", Settings.ACTION_LOCALE_SETTINGS),
            SettingHit("Keyboard", Settings.ACTION_INPUT_METHOD_SETTINGS),
            SettingHit("Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            SettingHit("About phone", Settings.ACTION_DEVICE_INFO_SETTINGS),
            SettingHit("NFC", Settings.ACTION_NFC_SETTINGS),
            SettingHit("Cast", Settings.ACTION_CAST_SETTINGS),
            SettingHit("Data usage", Settings.ACTION_DATA_USAGE_SETTINGS),
            SettingHit("VPN", Settings.ACTION_VPN_SETTINGS),
            SettingHit("Do not disturb", Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS),
            SettingHit("Night light", Settings.ACTION_NIGHT_DISPLAY_SETTINGS),
            SettingHit("Users", "android.settings.USER_SETTINGS"),
            SettingHit("Accounts", Settings.ACTION_SYNC_SETTINGS),
            SettingHit("System update", "android.settings.SYSTEM_UPDATE_SETTINGS"),
        )
    }
}
