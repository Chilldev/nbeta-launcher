package com.mali.nbeta.data.search

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Patterns
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.mali.nbeta.R
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
data class SettingHit(@StringRes val label: Int, val action: String)

@Immutable
sealed interface WebHit {
    data class Url(val url: String) : WebHit

    data class Search(val query: String, val engine: String, val url: String) : WebHit

    data class Store(val query: String) : WebHit
}

@Immutable
data class EventHit(val id: Long, val title: String, val begin: Long, val allDay: Boolean, val color: Int)

@Immutable
data class SearchResults(
    val query: String,
    val apps: List<AppEntry> = emptyList(),
    val calc: String? = null,
    /** "5 km = 3.10686 mi" */
    val conversion: String? = null,
    val events: List<EventHit> = emptyList(),
    val shortcuts: List<AppShortcut> = emptyList(),
    /** Recent chats from messaging apps (conversation shortcuts). */
    val people: List<AppShortcut> = emptyList(),
    /** Current notifications whose text matches. */
    val messages: List<com.mali.nbeta.system.NotifItem> = emptyList(),
    val contacts: List<ContactHit> = emptyList(),
    val settings: List<SettingHit> = emptyList(),
    val web: List<WebHit> = emptyList(),
) {
    val isEmpty get() = apps.isEmpty() && calc == null && conversion == null && events.isEmpty() && shortcuts.isEmpty() && people.isEmpty() && messages.isEmpty() && contacts.isEmpty() && settings.isEmpty()
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

    // Labels are matched in the current language, so the index is rebuilt when the locale changes.
    @Volatile
    private var settingsIndex: Pair<java.util.Locale, List<Pair<SettingHit, List<Searchable>>>>? = null

    /** Each setting is findable by its name in the current language and in English ("battery" works in Arabic too). */
    private fun settingsIndex(): List<Pair<SettingHit, List<Searchable>>> {
        val locale = context.resources.configuration.locales[0]
        settingsIndex?.let { (l, index) -> if (l == locale) return index }
        val english = if (locale.language == "en") null else {
            val cfg = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) }
            context.createConfigurationContext(cfg)
        }
        return SYSTEM_SETTINGS.map { hit ->
            hit to listOfNotNull(Searchable(context.getString(hit.label)), english?.let { Searchable(it.getString(hit.label)) })
        }.also { settingsIndex = locale to it }
    }

    suspend fun search(raw: String): SearchResults = coroutineScope {
        val query = raw.trim()
        if (query.isEmpty()) return@coroutineScope SearchResults(raw)
        val q = TextFold.fold(query)
        val cfg = settings.value
        val contactsJob = if (cfg.searchContacts && query.length >= 2 && hasContacts()) async(Dispatchers.IO) { contacts(query) } else null
        val eventsJob = if (cfg.searchEvents && query.length >= 3 && hasCalendar()) async(Dispatchers.IO) { events(query) } else null

        val now = System.currentTimeMillis()
        val appHits = appIndex.value.mapNotNull { ia ->
            val sc = Matcher.score(q, ia.s)
            if (sc == 0) null else ia.app to sc + (apps.frecency(ia.app.key, now) * 8).coerceAtMost(90.0).toInt()
        }.sortedByDescending { it.second }.take(12).map { it.first }

        val (conversationIndex, actionIndex) = shortcutIndex.value.partition { it.sc.isConversation }
        val shortcutHits = if (cfg.searchShortcuts && q.length >= 2) {
            actionIndex.mapNotNull { s -> Matcher.score(q, s.s).takeIf { it >= 600 }?.let { s.sc to it } }
                .sortedByDescending { it.second }.take(5).map { it.first }
        } else emptyList()
        // People: match the chat name only (not the app name), so "slack" doesn't list every Slack chat.
        val peopleHits = if (cfg.searchShortcuts && q.length >= 2) {
            conversationIndex.mapNotNull { s -> Matcher.score(q, Searchable(s.sc.label)).takeIf { it >= 600 }?.let { s.sc to it } }
                .sortedByDescending { it.second }.distinctBy { it.first.label to it.first.appLabel }.take(6).map { it.first }
        } else emptyList()
        val messageHits = if (q.length >= 2) {
            com.mali.nbeta.system.NotificationDotsService.notifications.value
                .filter { TextFold.fold(it.title + " " + it.text).contains(q) }
                .take(4)
        } else emptyList()

        val settingHits = if (cfg.searchSettings && q.length >= 3) {
            settingsIndex().mapNotNull { (h, names) -> names.maxOf { Matcher.score(q, it) }.takeIf { it >= 600 }?.let { h to it } }
                .sortedByDescending { it.second }.take(3).map { it.first }
        } else emptyList()

        val calc = if (cfg.searchCalculator) Calculator.evaluate(query) else null
        val conversion = if (cfg.searchCalculator && calc == null) Converter.convert(query) else null

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
            people = peopleHits,
            messages = messageHits,
            conversion = conversion,
            events = eventsJob?.await().orEmpty(),
            shortcuts = shortcutHits,
            contacts = contactsJob?.await().orEmpty(),
            settings = settingHits,
            web = web,
        )
    }

    private fun looksLikeUrl(q: String) = !q.contains(' ') && q.contains('.') && Patterns.WEB_URL.matcher(q).matches()

    private fun hasCalendar() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Upcoming (and the last day's) calendar events whose title matches, soonest first. */
    private fun events(query: String): List<EventHit> {
        val now = System.currentTimeMillis()
        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now - 86_400_000L)
            android.content.ContentUris.appendId(it, now + 60L * 86_400_000L)
        }.build()
        val proj = arrayOf(
            android.provider.CalendarContract.Instances.EVENT_ID,
            android.provider.CalendarContract.Instances.TITLE,
            android.provider.CalendarContract.Instances.BEGIN,
            android.provider.CalendarContract.Instances.ALL_DAY,
            android.provider.CalendarContract.Instances.DISPLAY_COLOR,
        )
        val out = ArrayList<EventHit>()
        try {
            context.contentResolver.query(
                uri, proj, "${android.provider.CalendarContract.Instances.TITLE} LIKE ?", arrayOf("%$query%"),
                "${android.provider.CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 4) {
                    out += EventHit(c.getLong(0), c.getString(1) ?: continue, c.getLong(2), c.getInt(3) == 1, c.getInt(4))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

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
            SettingHit(R.string.search_setting_wifi, Settings.ACTION_WIFI_SETTINGS),
            SettingHit(R.string.search_setting_bluetooth, Settings.ACTION_BLUETOOTH_SETTINGS),
            SettingHit(R.string.search_setting_mobile_network, Settings.ACTION_DATA_ROAMING_SETTINGS),
            SettingHit(R.string.search_setting_network, Settings.ACTION_WIRELESS_SETTINGS),
            SettingHit(R.string.search_setting_hotspot, "android.settings.TETHER_SETTINGS"),
            SettingHit(R.string.search_setting_airplane, Settings.ACTION_AIRPLANE_MODE_SETTINGS),
            SettingHit(R.string.search_setting_display, Settings.ACTION_DISPLAY_SETTINGS),
            SettingHit(R.string.common_wallpaper, Intent.ACTION_SET_WALLPAPER),
            SettingHit(R.string.search_setting_sound, Settings.ACTION_SOUND_SETTINGS),
            SettingHit(R.string.search_setting_battery, Intent.ACTION_POWER_USAGE_SUMMARY),
            SettingHit(R.string.search_setting_apps, Settings.ACTION_APPLICATION_SETTINGS),
            SettingHit(R.string.search_setting_default_apps, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            SettingHit(R.string.common_notifications, "android.settings.ALL_APPS_NOTIFICATION_SETTINGS"),
            SettingHit(R.string.search_setting_storage, Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            SettingHit(R.string.common_location, Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            SettingHit(R.string.search_setting_security, Settings.ACTION_SECURITY_SETTINGS),
            SettingHit(R.string.search_setting_privacy, Settings.ACTION_PRIVACY_SETTINGS),
            SettingHit(R.string.search_setting_accessibility, Settings.ACTION_ACCESSIBILITY_SETTINGS),
            SettingHit(R.string.search_setting_date, Settings.ACTION_DATE_SETTINGS),
            SettingHit(R.string.search_setting_language, Settings.ACTION_LOCALE_SETTINGS),
            SettingHit(R.string.search_setting_keyboard, Settings.ACTION_INPUT_METHOD_SETTINGS),
            SettingHit(R.string.search_setting_developer, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            SettingHit(R.string.search_setting_about, Settings.ACTION_DEVICE_INFO_SETTINGS),
            SettingHit(R.string.search_setting_nfc, Settings.ACTION_NFC_SETTINGS),
            SettingHit(R.string.search_setting_cast, Settings.ACTION_CAST_SETTINGS),
            SettingHit(R.string.search_setting_data_usage, Settings.ACTION_DATA_USAGE_SETTINGS),
            SettingHit(R.string.search_setting_vpn, Settings.ACTION_VPN_SETTINGS),
            SettingHit(R.string.search_setting_dnd, Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS),
            SettingHit(R.string.search_setting_night_light, Settings.ACTION_NIGHT_DISPLAY_SETTINGS),
            SettingHit(R.string.search_setting_users, "android.settings.USER_SETTINGS"),
            SettingHit(R.string.search_setting_accounts, Settings.ACTION_SYNC_SETTINGS),
            SettingHit(R.string.search_setting_system_update, "android.settings.SYSTEM_UPDATE_SETTINGS"),
        )
    }
}
