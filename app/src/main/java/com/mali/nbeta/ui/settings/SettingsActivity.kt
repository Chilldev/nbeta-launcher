package com.mali.nbeta.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.mali.nbeta.data.FeedSource
import com.mali.nbeta.data.reddit.RedditClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mali.nbeta.AppGraph
import com.mali.nbeta.BuildConfig
import com.mali.nbeta.NbetaApp
import com.mali.nbeta.R
import com.mali.nbeta.data.DefaultFeeds
import com.mali.nbeta.data.DoubleTapAction
import com.mali.nbeta.data.DrawerSort
import com.mali.nbeta.data.FeedOrder
import com.mali.nbeta.data.IconShape
import com.mali.nbeta.data.LauncherSettings
import com.mali.nbeta.data.LinkOpener
import com.mali.nbeta.data.SwipeDownAction
import com.mali.nbeta.data.TempUnit
import com.mali.nbeta.data.TextOnWallpaper
import com.mali.nbeta.data.ThemeMode
import com.mali.nbeta.data.WebEngine
import com.mali.nbeta.data.apps.IconPack
import com.mali.nbeta.data.feed.Opml
import com.mali.nbeta.system.GestureAccessibilityService
import com.mali.nbeta.system.NotificationDotsService
import com.mali.nbeta.ui.common.LocalGraph
import com.mali.nbeta.ui.theme.NbetaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    private var requested by mutableStateOf<Pair<Page?, Long>>(null to 0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as NbetaApp).graph
        requested = pageOf(intent) to System.nanoTime()
        setContent {
            val s by graph.settings.flow.collectAsStateWithLifecycle()
            NbetaTheme(s) {
                CompositionLocalProvider(LocalGraph provides graph) {
                    SettingsApp(graph, s, requested) { finish() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requested = pageOf(intent) to System.nanoTime()
    }

    private fun pageOf(intent: Intent?) = intent?.getStringExtra(EXTRA_PAGE)?.let { p -> Page.entries.firstOrNull { it.name.equals(p, true) } }

    companion object {
        const val EXTRA_PAGE = "page"
    }
}

enum class Page(@StringRes val title: Int) {
    Root(R.string.settings), Appearance(R.string.settings_appearance), Home(R.string.settings_home), Drawer(R.string.settings_drawer),
    Icons(R.string.settings_icons), Gestures(R.string.settings_gestures), Search(R.string.common_search), Feed(R.string.settings_feed),
    Weather(R.string.settings_weather), Hidden(R.string.settings_hidden_apps), Backup(R.string.settings_backup),
}

@Composable
private fun SettingsApp(graph: AppGraph, s: LauncherSettings, requested: Pair<Page?, Long>, finish: () -> Unit) {
    var stack by remember { mutableStateOf(listOf(Page.Root)) }
    // A deep link (e.g. "Feed settings" from the feed page) always lands on that page, even if settings was open.
    androidx.compose.runtime.LaunchedEffect(requested) {
        stack = listOfNotNull(Page.Root, requested.first?.takeIf { it != Page.Root })
    }
    val page = stack.last()
    val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) else finish() }
    BackHandler(onBack = back)
    val go: (Page) -> Unit = { stack = stack + it }
    val set: ((LauncherSettings) -> LauncherSettings) -> Unit = { graph.settings.update(it) }

    SettingsScaffold(stringResource(page.title), if (page == Page.Root) null else back) {
        when (page) {
            Page.Root -> root(go)
            Page.Appearance -> appearance(s, set)
            Page.Home -> home(s, set)
            Page.Drawer -> drawer(s, set, go)
            Page.Icons -> icons(s, set, graph)
            Page.Gestures -> gestures(s, set)
            Page.Search -> search(s, set)
            Page.Feed -> feed(s, set, graph)
            Page.Weather -> weather(s, set, graph)
            Page.Hidden -> hidden(s, set, graph)
            Page.Backup -> backup(graph)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.root(go: (Page) -> Unit) {
    item { DefaultLauncherBanner() }
    item { PageLink(Page.Appearance, R.string.settings_appearance_summary, Icons.Default.Face, go) }
    item { PageLink(Page.Home, R.string.settings_home_summary, Icons.Default.Home, go) }
    item { PageLink(Page.Drawer, R.string.settings_drawer_summary, Icons.AutoMirrored.Filled.List, go) }
    item { PageLink(Page.Icons, R.string.settings_icons_summary, Icons.Default.AccountBox, go) }
    item { PageLink(Page.Gestures, R.string.settings_gestures_summary, Icons.Default.ThumbUp, go) }
    item { PageLink(Page.Search, R.string.settings_search_summary, Icons.Default.Search, go) }
    item { PageLink(Page.Feed, R.string.settings_feed_summary, Icons.Default.DateRange, go) }
    item { PageLink(Page.Weather, R.string.settings_weather_summary, Icons.Default.LocationOn, go) }
    item { PageLink(Page.Backup, R.string.settings_backup_summary, Icons.Default.Build, go) }
    item { ClickPref(stringResource(R.string.settings_about), stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME), Icons.Default.Info) {} }
}

@Composable
private fun PageLink(page: Page, @StringRes summary: Int, icon: ImageVector, go: (Page) -> Unit) {
    ClickPref(stringResource(page.title), stringResource(summary), icon) { go(page) }
}

@Composable
private fun DefaultLauncherBanner() {
    val context = LocalContext.current
    val isDefault = remember {
        val home = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        home?.activityInfo?.packageName == context.packageName
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    if (isDefault) return
    Column(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(20.dp),
    ) {
        Text(stringResource(R.string.settings_default_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            stringResource(R.string.settings_default_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) request.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
            else context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }) { Text(stringResource(R.string.settings_default_action)) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appearance(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    item { ChoicePref(stringResource(R.string.settings_theme), ThemeMode.entries, s.themeMode, { stringResource(it.label) }) { v -> set { it.copy(themeMode = v) } } }
    if (Build.VERSION.SDK_INT >= 31) item {
        SwitchPref(stringResource(R.string.settings_wallpaper_colours), stringResource(R.string.settings_wallpaper_colours_summary), s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }
    }
    item {
        ChoicePref(stringResource(R.string.settings_text_on_wallpaper), TextOnWallpaper.entries, s.textOnWallpaper, { stringResource(it.label) }) { v ->
            set { it.copy(textOnWallpaper = v) }
        }
    }
    item { SliderPref(stringResource(R.string.settings_dim_wallpaper), s.wallpaperDim, 0f..0.6f, 11, { percent(it) }) { v -> set { it.copy(wallpaperDim = v) } } }
    item { SliderPref(stringResource(R.string.settings_drawer_opacity), s.drawerOpacity, 0.6f..1f, 7, { percent(it) }) { v -> set { it.copy(drawerOpacity = v) } } }
    if (Build.VERSION.SDK_INT >= 33) item { LanguagePref() }
}

@Composable
private fun percent(fraction: Float) = stringResource(R.string.settings_percent, (fraction * 100).roundToInt())

/** Per-app language (Android 13+): the system picker lists the locales from the generated locale config. */
@RequiresApi(33)
@Composable
private fun LanguagePref() {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    ClickPref(stringResource(R.string.settings_language), locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }) {
        runCatching { context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${context.packageName}"))) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.home(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    item { SliderPref(stringResource(R.string.settings_columns), s.homeColumns.toFloat(), 3f..6f, 2, { stringResource(R.string.settings_number, it.roundToInt()) }) { v -> set { it.copy(homeColumns = v.roundToInt()) } } }
    item { SliderPref(stringResource(R.string.settings_icon_size), s.iconSizeDp.toFloat(), 44f..72f, 6, { stringResource(R.string.settings_dp, it.roundToInt()) }) { v -> set { it.copy(iconSizeDp = v.roundToInt()) } } }
    item { SwitchPref(stringResource(R.string.settings_home_labels), null, s.homeLabels) { v -> set { it.copy(homeLabels = v) } } }
    item { SwitchPref(stringResource(R.string.settings_dock), stringResource(R.string.settings_dock_summary), s.showDock) { v -> set { it.copy(showDock = v) } } }
    item { SwitchPref(stringResource(R.string.settings_search_bar), null, s.showHomeSearch) { v -> set { it.copy(showHomeSearch = v) } } }
    item { SectionHeader(stringResource(R.string.settings_at_a_glance)) }
    item { SwitchPref(stringResource(R.string.settings_clock), null, s.glanceClock) { v -> set { it.copy(glanceClock = v) } } }
    item { SwitchPref(stringResource(R.string.settings_glance_weather), null, s.glanceWeather) { v -> set { it.copy(glanceWeather = v) } } }
    item { SwitchPref(stringResource(R.string.settings_next_event), null, s.glanceCalendar) { v -> set { it.copy(glanceCalendar = v) } } }
    item { SwitchPref(stringResource(R.string.settings_next_alarm), null, s.glanceAlarm) { v -> set { it.copy(glanceAlarm = v) } } }
    item { SectionHeader(stringResource(R.string.settings_layout)) }
    item {
        ClickPref(stringResource(R.string.settings_reset_layout), stringResource(R.string.settings_reset_layout_summary)) {
            set { it.copy(homeItems = emptyList(), pages = emptyList(), dockItems = emptyList(), layoutInitialized = false) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.drawer(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit, go: (Page) -> Unit) {
    item { SliderPref(stringResource(R.string.settings_columns), s.drawerColumns.toFloat(), 3f..7f, 3, { stringResource(R.string.settings_number, it.roundToInt()) }) { v -> set { it.copy(drawerColumns = v.roundToInt()) } } }
    item { SwitchPref(stringResource(R.string.settings_labels), null, s.drawerLabels) { v -> set { it.copy(drawerLabels = v) } } }
    item { ChoicePref(stringResource(R.string.settings_sort), DrawerSort.entries, s.drawerSort, { stringResource(it.label) }) { v -> set { it.copy(drawerSort = v) } } }
    item { SwitchPref(stringResource(R.string.settings_auto_keyboard), stringResource(R.string.settings_auto_keyboard_summary), s.autoKeyboard) { v -> set { it.copy(autoKeyboard = v) } } }
    item { SwitchPref(stringResource(R.string.settings_suggestions_row), stringResource(R.string.settings_suggestions_row_summary), s.showSuggestions) { v -> set { it.copy(showSuggestions = v) } } }
    item { ClickPref(stringResource(R.string.settings_hidden_apps), pluralStringResource(R.plurals.settings_hidden_count, s.hiddenApps.size, s.hiddenApps.size), Icons.Default.Lock) { go(Page.Hidden) } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.icons(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit, graph: AppGraph) {
    item {
        ChoicePref(stringResource(R.string.settings_icon_shape), IconShape.entries, s.iconShape, { stringResource(it.label) }) { v -> set { it.copy(iconShape = v) } }
    }
    item { IconPackPref(s, set) }
    if (Build.VERSION.SDK_INT >= 33) item {
        SwitchPref(stringResource(R.string.settings_themed_icons), stringResource(R.string.settings_themed_icons_summary), s.themedIcons) { v -> set { it.copy(themedIcons = v) } }
    }
    item { NotificationDotsPref(s, set) }
    item {
        val context = LocalContext.current
        ClickPref(stringResource(R.string.settings_clear_icon_cache), stringResource(R.string.settings_clear_icon_cache_summary)) {
            graph.icons.clearAll()
            Toast.makeText(context, R.string.settings_icons_redrawn, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun IconPackPref(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = LocalContext.current
    val packs by produceState(emptyList<Pair<String, String>>()) { value = withContext(Dispatchers.IO) { IconPack.installed(context) } }
    val options = listOf<Pair<String?, String>>(null to stringResource(R.string.settings_icon_pack_system)) + packs
    val selected = options.firstOrNull { it.first == s.iconPack } ?: options.first()
    ChoicePref(stringResource(R.string.settings_icon_pack), options, selected, { if (packs.isEmpty() && it.first == null) stringResource(R.string.settings_icon_pack_none) else it.second }) { v ->
        set { it.copy(iconPack = v.first) }
    }
}

@Composable
private fun NotificationDotsPref(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = LocalContext.current
    val granted = remember { NotificationDotsService.isEnabled(context) }
    SwitchPref(
        stringResource(R.string.settings_dots),
        stringResource(if (granted) R.string.settings_dots_on else R.string.settings_dots_needs_access),
        s.notificationDots && granted,
    ) { v ->
        if (v && !granted) {
            val intent = if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, NotificationDotsService::class.java).flattenToString())
            } else Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            runCatching { context.startActivity(intent) }
        }
        set { it.copy(notificationDots = v) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.gestures(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    item { ClickPref(stringResource(R.string.settings_swipe_up), stringResource(R.string.settings_swipe_up_summary)) {} }
    item {
        ChoicePref(stringResource(R.string.settings_swipe_down), SwipeDownAction.entries, s.swipeDown, { stringResource(it.label) }) { v -> set { it.copy(swipeDown = v) } }
    }
    item {
        ChoicePref(stringResource(R.string.settings_double_tap), DoubleTapAction.entries, s.doubleTap, { stringResource(it.label) }) { v -> set { it.copy(doubleTap = v) } }
    }
    item {
        val context = LocalContext.current
        val on = GestureAccessibilityService.instance != null
        ClickPref(stringResource(R.string.settings_lock_permission), stringResource(if (on) R.string.settings_enabled else R.string.settings_lock_permission_needed)) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
    item { ClickPref(stringResource(R.string.settings_swipe_to_feed), stringResource(R.string.settings_swipe_to_feed_summary)) {} }
}

private fun androidx.compose.foundation.lazy.LazyListScope.search(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    item { ContactsPref(s, set) }
    item { SwitchPref(stringResource(R.string.settings_calculator), stringResource(R.string.settings_calculator_summary), s.searchCalculator) { v -> set { it.copy(searchCalculator = v) } } }
    item { SwitchPref(stringResource(R.string.settings_app_shortcuts), stringResource(R.string.settings_app_shortcuts_summary), s.searchShortcuts) { v -> set { it.copy(searchShortcuts = v) } } }
    item { SwitchPref(stringResource(R.string.settings_system_settings), stringResource(R.string.settings_system_settings_summary), s.searchSettings) { v -> set { it.copy(searchSettings = v) } } }
    item { ChoicePref(stringResource(R.string.settings_web_search), WebEngine.entries, s.webEngine, { it.label }) { v -> set { it.copy(webEngine = v) } } }
}

@Composable
private fun ContactsPref(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    SwitchPref(
        stringResource(R.string.common_contacts),
        stringResource(if (granted) R.string.settings_contacts_on else R.string.settings_contacts_off),
        s.searchContacts && granted,
    ) { v ->
        if (v && !granted) req.launch(Manifest.permission.READ_CONTACTS)
        set { it.copy(searchContacts = v) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.feed(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit, graph: AppGraph) {
    item { SwitchPref(stringResource(R.string.settings_feed_page), stringResource(R.string.settings_feed_page_summary), s.feedEnabled) { v -> set { it.copy(feedEnabled = v) } } }
    item { SectionHeader(stringResource(R.string.settings_sources)) }
    item { AddSourceRow(graph) }
    items(s.feedSources, key = { it.id }) { src ->
        val cache by graph.feed.cache.collectAsStateWithLifecycle()
        val st = cache.status[src.id]
        val muted = src.id in cache.mutedSources
        ClickPref(
            src.title,
            when {
                st?.error != null -> stringResource(R.string.settings_source_error, st.error)
                muted -> stringResource(R.string.settings_source_muted)
                st != null -> pluralStringResource(R.plurals.settings_source_stories, st.count, st.count, src.url)
                else -> src.url
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.newsAlerts) {
                        val alerting = src.id in s.alertSources
                        IconButton(onClick = { set { it.copy(alertSources = if (alerting) it.alertSources - src.id else it.alertSources + src.id) } }) {
                            Icon(
                                Icons.Default.Notifications,
                                stringResource(if (alerting) R.string.settings_stop_alerts else R.string.settings_start_alerts, src.title),
                                tint = if (alerting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    IconButton(onClick = { set { it.copy(feedSources = it.feedSources.filterNot { f -> f.id == src.id }) } }) { Icon(Icons.Default.Delete, stringResource(R.string.common_remove)) }
                    Switch(src.enabled, { v -> set { it.copy(feedSources = it.feedSources.map { f -> if (f.id == src.id) f.copy(enabled = v) else f }) } })
                }
            },
        ) { if (muted) graph.feed.muteSource(src.id, false) }
    }
    item { RedditSection(s, set, graph) }
    val suggestions = DefaultFeeds.catalog.filter { c -> s.feedSources.none { it.url == c.url } }
    if (suggestions.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.settings_suggestions)) }
        items(suggestions, key = { "sug" + it.id }) { c ->
            ClickPref(c.title, c.siteUrl, Icons.Default.Add) {
                set { it.copy(feedSources = it.feedSources + c) }
                graph.scope.launch { graph.feed.refresh(c.id) }
            }
        }
    }
    item { SectionHeader(stringResource(R.string.settings_reading)) }
    item { ChoicePref(stringResource(R.string.settings_default_order), FeedOrder.entries, s.feedOrder, { stringResource(it.label) }) { v -> set { it.copy(feedOrder = v) } } }
    item { ChoicePref(stringResource(R.string.settings_open_in), LinkOpener.entries, s.linkOpener, { stringResource(it.label) }) { v -> set { it.copy(linkOpener = v) } } }
    item { SwitchPref(stringResource(R.string.settings_fetch_images), stringResource(R.string.settings_fetch_images_summary), s.feedFetchImages) { v -> set { it.copy(feedFetchImages = v) } } }
    item { SectionHeader(stringResource(R.string.settings_muted_keywords)) }
    item {
        KeywordListPref(
            stringResource(R.string.settings_hide_mentioning),
            stringResource(R.string.settings_hide_mentioning_hint),
            s.mutedKeywords,
        ) { list -> set { it.copy(mutedKeywords = list) } }
    }
    item { SectionHeader(stringResource(R.string.settings_breaking_news)) }
    item { NewsAlertsPref(s, set) }
    if (s.newsAlerts) {
        item { ClickPref(stringResource(R.string.settings_alert_sources), pluralStringResource(R.plurals.settings_alert_sources_summary, s.alertSources.size, s.alertSources.size)) {} }
        item {
            val context = LocalContext.current
            ClickPref(stringResource(R.string.settings_check_now), stringResource(R.string.settings_check_now_summary)) {
                graph.scope.launch {
                    graph.feed.refresh(notify = true)
                    Toast.makeText(context, R.string.settings_checked, Toast.LENGTH_SHORT).show()
                }
            }
        }
        item {
            KeywordListPref(stringResource(R.string.settings_alert_me_about), stringResource(R.string.settings_alert_me_about_hint), s.alertKeywords) { list -> set { it.copy(alertKeywords = list) } }
        }
    }
    item { SectionHeader(stringResource(R.string.settings_background_refresh)) }
    item {
        ChoicePref(
            stringResource(R.string.settings_refresh_every),
            listOf(0, 1, 2, 4, 8, 12),
            s.feedRefreshHours,
            { if (it == 0) stringResource(R.string.settings_refresh_manual) else pluralStringResource(R.plurals.settings_refresh_hours, it, it) },
        ) { v ->
            set { it.copy(feedRefreshHours = v) }
        }
    }
    item { SwitchPref(stringResource(R.string.settings_wifi_only), null, s.feedWifiOnly) { v -> set { it.copy(feedWifiOnly = v) } } }
    item { SectionHeader(stringResource(R.string.settings_opml)) }
    item { OpmlPrefs(s, set, graph) }
    item { ClickPref(stringResource(R.string.settings_clear_stories), stringResource(R.string.settings_clear_stories_summary)) { graph.feed.clear() } }
}

@Composable
private fun AddSourceRow(graph: AppGraph) {
    var open by remember { mutableStateOf(false) }
    ClickPref(stringResource(R.string.settings_add_source), stringResource(R.string.settings_add_source_summary), Icons.Default.Add) { open = true }
    if (!open) return
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val alreadyAdded = stringResource(R.string.settings_already_added)
    val notFound = stringResource(R.string.settings_no_feed_found)
    AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text(stringResource(R.string.settings_add_source_title)) },
        text = {
            Column {
                OutlinedTextField(text, { text = it; error = null }, singleLine = true, label = { Text(stringResource(R.string.settings_add_source_hint)) }, isError = error != null, supportingText = { error?.let { Text(it) } })
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.settings_looking_for_feed))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val src = graph.feed.discover(text)
                        if (graph.settings.value.feedSources.any { it.url == src.url }) {
                            error = alreadyAdded
                        } else {
                            graph.settings.update { it.copy(feedSources = it.feedSources + src) }
                            graph.scope.launch { graph.feed.refresh(src.id) }
                            open = false
                        }
                    } catch (e: Exception) {
                        error = e.message ?: notFound
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.common_add)) }
        },
        dismissButton = { TextButton(onClick = { open = false }, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun OpmlPrefs(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit, graph: AppGraph) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(Opml.export(s.feedSources).toByteArray()) } }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { Opml.import(it) } }.getOrNull().orEmpty() }
            set { st -> st.copy(feedSources = st.feedSources + found.filter { f -> st.feedSources.none { it.url == f.url } }) }
            Toast.makeText(context, resources.getQuantityString(R.plurals.settings_imported_feeds, found.size, found.size), Toast.LENGTH_SHORT).show()
            graph.scope.launch { graph.feed.refresh() }
        }
    }
    Column {
        ClickPref(stringResource(R.string.settings_import_opml), stringResource(R.string.settings_import_opml_summary)) { import.launch(arrayOf("*/*")) }
        ClickPref(stringResource(R.string.settings_export_opml), pluralStringResource(R.plurals.settings_source_count, s.feedSources.size, s.feedSources.size)) { export.launch("nbeta-feeds.opml") }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.weather(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit, graph: AppGraph) {
    item { ChoicePref(stringResource(R.string.settings_units), TempUnit.entries, s.tempUnit, { stringResource(it.label) }) { v -> set { it.copy(tempUnit = v) } } }
    item { WeatherLocationPref(s, set, graph) }
    item { ClickPref(stringResource(R.string.settings_weather_data), stringResource(R.string.settings_weather_data_summary)) {} }
}

@Composable
private fun WeatherLocationPref(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit, graph: AppGraph) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) graph.glance.refresh(forceWeather = true) }
    ClickPref(stringResource(R.string.common_location), s.weatherCity ?: stringResource(R.string.settings_device_location)) { open = true }
    if (!open) return
    var city by remember { mutableStateOf(s.weatherCity.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.settings_weather_location)) },
        text = { OutlinedTextField(city, { city = it }, singleLine = true, label = { Text(stringResource(R.string.settings_city)) }) },
        confirmButton = {
            TextButton(enabled = city.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    val r = graph.glance.geocode(city)
                    busy = false
                    if (r == null) {
                        Toast.makeText(context, R.string.settings_city_not_found, Toast.LENGTH_SHORT).show()
                    } else {
                        set { it.copy(weatherCity = r.first, weatherLat = r.second, weatherLon = r.third) }
                        graph.glance.refresh(forceWeather = true)
                        open = false
                    }
                }
            }) { Text(stringResource(R.string.settings_use_city)) }
        },
        dismissButton = {
            TextButton(onClick = {
                set { it.copy(weatherCity = null, weatherLat = null, weatherLon = null) }
                req.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                open = false
            }) { Text(stringResource(R.string.settings_use_device_location)) }
        },
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.hidden(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit, graph: AppGraph) {
    item { Text(stringResource(R.string.settings_hidden_explainer), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(graph.apps.apps.value, key = { it.key }) { app ->
        val hidden = app.key in s.hiddenApps
        ClickPref(app.label, app.packageName, trailing = { Checkbox(hidden, null) }) {
            set { it.copy(hiddenApps = if (hidden) it.hiddenApps - app.key else it.hiddenApps + app.key) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.backup(graph: AppGraph) {
    item { BackupPrefs(graph) }
}

@Composable
private fun BackupPrefs(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(graph.settings.export().toByteArray()) } }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { graph.settings.import(it.readBytes().decodeToString()) } }.isSuccess
            }
            Toast.makeText(context, if (ok) R.string.settings_restored else R.string.settings_not_a_backup, Toast.LENGTH_SHORT).show()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ClickPref(stringResource(R.string.settings_export), stringResource(R.string.settings_export_summary)) { export.launch("nbeta-backup.json") }
        ClickPref(stringResource(R.string.settings_import)) { import.launch(arrayOf("application/json", "*/*")) }
        ClickPref(stringResource(R.string.settings_reset_everything), stringResource(R.string.settings_reset_everything_summary)) { confirmReset = true }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    graph.settings.import("{}")
                    confirmReset = false
                }) { Text(stringResource(R.string.common_reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun RedditSection(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit, graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by graph.reddit.session.collectAsStateWithLifecycle()
    var setup by remember { mutableStateOf(false) }
    val clientId = s.redditClientId?.takeIf { it.isNotBlank() }
    val signIn: (String) -> Unit = { id ->
        runCatching { CustomTabsIntent.Builder().build().launchUrl(context, graph.reddit.authorizeUri(id)) }
            .onFailure { Toast.makeText(context, R.string.reddit_no_browser, Toast.LENGTH_SHORT).show() }
    }

    Column {
        SectionHeader(stringResource(R.string.reddit_section))
        val current = session
        if (current != null) {
            ClickPref(
                stringResource(R.string.reddit_signed_in_as, current.username ?: "…"),
                stringResource(R.string.reddit_signed_in_summary),
                Icons.Default.AccountBox,
            ) {}
            val hasHome = s.feedSources.any { graph.reddit.listingPath(it.url)?.startsWith("/best") == true }
            if (!hasHome) {
                val homeTitle = stringResource(R.string.reddit_home_title)
                ClickPref(stringResource(R.string.reddit_add_home), stringResource(R.string.reddit_add_home_summary), Icons.Default.Add) {
                    val url = RedditClient.HOME_FEED_URL
                    val src = FeedSource(com.mali.nbeta.data.feed.FeedParser.hash(url), url, homeTitle, "https://www.reddit.com")
                    set { it.copy(feedSources = it.feedSources + src) }
                    graph.scope.launch { graph.feed.refresh(src.id) }
                }
            }
            ClickPref(stringResource(R.string.reddit_sign_out), stringResource(R.string.reddit_sign_out_summary)) { scope.launch { graph.reddit.signOut() } }
        } else {
            ClickPref(
                stringResource(R.string.reddit_sign_in),
                if (clientId == null) stringResource(R.string.reddit_sign_in_setup) else stringResource(R.string.reddit_using_client, clientId.take(6)),
                Icons.Default.AccountBox,
            ) { if (clientId == null) setup = true else signIn(clientId) }
            if (clientId != null) ClickPref(stringResource(R.string.reddit_change_client)) { setup = true }
        }
    }

    if (setup) {
        var text by remember { mutableStateOf(clientId.orEmpty()) }
        val open: (String) -> Unit = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
        AlertDialog(
            onDismissRequest = { setup = false },
            title = { Text(stringResource(R.string.reddit_connect_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.reddit_setup_intro), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.reddit_setup_step1), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { open(RedditClient.ACCESS_REQUEST_URL) }) { Text(stringResource(R.string.reddit_open_access_form)) }
                    Text(stringResource(R.string.reddit_setup_step2), style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(RedditClient.REDIRECT_URI, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("Redirect URI", RedditClient.REDIRECT_URI))
                        }) { Text(stringResource(R.string.common_copy)) }
                    }
                    TextButton(onClick = { open(RedditClient.APPS_URL) }) { Text(stringResource(R.string.reddit_open_prefs)) }
                    Text(stringResource(R.string.reddit_setup_step3), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(text, { text = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.reddit_client_id)) })
                }
            },
            confirmButton = {
                TextButton(enabled = text.length >= 10, onClick = {
                    set { it.copy(redditClientId = text) }
                    setup = false
                    signIn(text)
                }) { Text(stringResource(R.string.reddit_save_sign_in)) }
            },
            dismissButton = { TextButton(onClick = { setup = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun KeywordListPref(title: String, hint: String, keywords: List<String>, onChange: (List<String>) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            keywords.forEach { k ->
                androidx.compose.material3.InputChip(
                    selected = false,
                    onClick = { onChange(keywords - k) },
                    label = { Text(k) },
                    trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.settings_remove_keyword, k), Modifier.size(16.dp)) },
                )
            }
            androidx.compose.material3.AssistChip(onClick = { adding = true }, label = { Text(stringResource(R.string.common_add)) }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) })
        }
    }
    if (adding) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(title) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(hint) }) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    val k = text.trim()
                    if (keywords.none { it.equals(k, ignoreCase = true) }) onChange(keywords + k)
                    adding = false
                }) { Text(stringResource(R.string.common_add)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun NewsAlertsPref(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) set { it.copy(newsAlerts = true) }
        else Toast.makeText(context, R.string.settings_notifications_off, Toast.LENGTH_SHORT).show()
    }
    SwitchPref(
        stringResource(R.string.settings_breaking_news),
        stringResource(if (s.feedRefreshHours == 0) R.string.settings_alerts_need_refresh else R.string.settings_alerts_summary),
        s.newsAlerts,
    ) { on ->
        if (on && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            set { it.copy(newsAlerts = on) }
        }
    }
}

@get:StringRes
private val ThemeMode.label
    get() = when (this) {
        ThemeMode.System -> R.string.settings_theme_system
        ThemeMode.Light -> R.string.settings_light
        ThemeMode.Dark -> R.string.settings_dark
    }

@get:StringRes
private val TextOnWallpaper.label
    get() = when (this) {
        TextOnWallpaper.Auto -> R.string.settings_text_auto
        TextOnWallpaper.Light -> R.string.settings_light
        TextOnWallpaper.Dark -> R.string.settings_dark
    }

@get:StringRes
private val DrawerSort.label
    get() = when (this) {
        DrawerSort.Alphabetical -> R.string.settings_sort_az
        DrawerSort.MostUsed -> R.string.settings_sort_most_used
    }

@get:StringRes
private val IconShape.label
    get() = when (this) {
        IconShape.System -> R.string.settings_shape_system
        IconShape.Circle -> R.string.settings_shape_circle
        IconShape.Squircle -> R.string.settings_shape_squircle
        IconShape.RoundedSquare -> R.string.settings_shape_rounded_square
        IconShape.Teardrop -> R.string.settings_shape_teardrop
    }

@get:StringRes
private val SwipeDownAction.label
    get() = when (this) {
        SwipeDownAction.Notifications -> R.string.common_notifications
        SwipeDownAction.QuickSettings -> R.string.settings_quick_settings
        SwipeDownAction.Search -> R.string.common_search
        SwipeDownAction.None -> R.string.settings_nothing
    }

@get:StringRes
private val DoubleTapAction.label
    get() = when (this) {
        DoubleTapAction.LockScreen -> R.string.settings_lock_screen
        DoubleTapAction.None -> R.string.settings_nothing
    }

@get:StringRes
private val FeedOrder.label
    get() = when (this) {
        FeedOrder.ForYou -> R.string.settings_order_for_you
        FeedOrder.Latest -> R.string.settings_order_latest
    }

@get:StringRes
private val LinkOpener.label
    get() = when (this) {
        LinkOpener.Reader -> R.string.settings_open_reader
        LinkOpener.CustomTab -> R.string.settings_open_custom_tab
        LinkOpener.Browser -> R.string.settings_open_browser
    }

@get:StringRes
private val TempUnit.label
    get() = when (this) {
        TempUnit.Celsius -> R.string.settings_celsius
        TempUnit.Fahrenheit -> R.string.settings_fahrenheit
    }
