package com.mali.nbeta.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mali.nbeta.AppGraph
import com.mali.nbeta.BuildConfig
import com.mali.nbeta.NbetaApp
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

enum class Page(val title: String) {
    Root("Nbeta settings"), Appearance("Appearance"), Home("Home screen"), Drawer("App drawer"), Icons("Icons"),
    Gestures("Gestures"), Search("Search"), Feed("Feed"), Weather("Weather & glance"), Hidden("Hidden apps"), Backup("Backup & restore"),
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

    SettingsScaffold(page.title, if (page == Page.Root) null else back) {
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
    item { ClickPref("Appearance", "Theme, colours, wallpaper dimming", Icons.Default.Face) { go(Page.Appearance) } }
    item { ClickPref("Home screen", "Grid, dock, search bar, at-a-glance", Icons.Default.Home) { go(Page.Home) } }
    item { ClickPref("App drawer", "Columns, sorting, keyboard, hidden apps", Icons.AutoMirrored.Filled.List) { go(Page.Drawer) } }
    item { ClickPref("Icons", "Shape, icon packs, themed icons, dots", Icons.Default.AccountBox) { go(Page.Icons) } }
    item { ClickPref("Gestures", "Swipe down, double-tap", Icons.Default.ThumbUp) { go(Page.Gestures) } }
    item { ClickPref("Search", "Contacts, calculator, shortcuts, web", Icons.Default.Search) { go(Page.Search) } }
    item { ClickPref("Feed", "Sources, refresh, reading", Icons.Default.DateRange) { go(Page.Feed) } }
    item { ClickPref("Weather & glance", "Location, units", Icons.Default.LocationOn) { go(Page.Weather) } }
    item { ClickPref("Backup & restore", "Export or import your setup", Icons.Default.Build) { go(Page.Backup) } }
    item { ClickPref("About", "Nbeta ${BuildConfig.VERSION_NAME}", Icons.Default.Info) {} }
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
        Text("Make Nbeta your home app", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            "Shortcuts, work profile controls and pinning need Nbeta to be the default home app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) request.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
            else context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }) { Text("Set as default") }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appearance(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    item { ChoicePref("Theme", ThemeMode.entries, s.themeMode, { it.name }) { v -> set { it.copy(themeMode = v) } } }
    if (Build.VERSION.SDK_INT >= 31) item {
        SwitchPref("Wallpaper colours", "Use Material You colours from your wallpaper", s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }
    }
    item {
        ChoicePref("Text on wallpaper", TextOnWallpaper.entries, s.textOnWallpaper, {
            when (it) { TextOnWallpaper.Auto -> "Automatic (from wallpaper)"; TextOnWallpaper.Light -> "Light"; TextOnWallpaper.Dark -> "Dark" }
        }) { v -> set { it.copy(textOnWallpaper = v) } }
    }
    item { SliderPref("Dim wallpaper", s.wallpaperDim, 0f..0.6f, 11, { "${(it * 100).roundToInt()}%" }) { v -> set { it.copy(wallpaperDim = v) } } }
    item { SliderPref("Drawer opacity", s.drawerOpacity, 0.6f..1f, 7, { "${(it * 100).roundToInt()}%" }) { v -> set { it.copy(drawerOpacity = v) } } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.home(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    item { SliderPref("Columns", s.homeColumns.toFloat(), 3f..6f, 2, { it.roundToInt().toString() }) { v -> set { it.copy(homeColumns = v.roundToInt()) } } }
    item { SliderPref("Icon size", s.iconSizeDp.toFloat(), 44f..72f, 6, { "${it.roundToInt()} dp" }) { v -> set { it.copy(iconSizeDp = v.roundToInt()) } } }
    item { SwitchPref("Labels on home screen", null, s.homeLabels) { v -> set { it.copy(homeLabels = v) } } }
    item { SwitchPref("Dock", "A row of favourites above the navigation bar", s.showDock) { v -> set { it.copy(showDock = v) } } }
    item { SwitchPref("Search bar", null, s.showHomeSearch) { v -> set { it.copy(showHomeSearch = v) } } }
    item { SectionHeader("At a glance") }
    item { SwitchPref("Clock", null, s.glanceClock) { v -> set { it.copy(glanceClock = v) } } }
    item { SwitchPref("Weather", null, s.glanceWeather) { v -> set { it.copy(glanceWeather = v) } } }
    item { SwitchPref("Next calendar event", null, s.glanceCalendar) { v -> set { it.copy(glanceCalendar = v) } } }
    item { SwitchPref("Next alarm", null, s.glanceAlarm) { v -> set { it.copy(glanceAlarm = v) } } }
    item { SectionHeader("Layout") }
    item {
        ClickPref("Reset home layout", "Restore the default dock and favourites") {
            set { it.copy(homeItems = emptyList(), dockItems = emptyList(), layoutInitialized = false) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.drawer(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit, go: (Page) -> Unit) {
    item { SliderPref("Columns", s.drawerColumns.toFloat(), 3f..7f, 3, { it.roundToInt().toString() }) { v -> set { it.copy(drawerColumns = v.roundToInt()) } } }
    item { SwitchPref("Labels", null, s.drawerLabels) { v -> set { it.copy(drawerLabels = v) } } }
    item { ChoicePref("Sort apps", DrawerSort.entries, s.drawerSort, { if (it == DrawerSort.Alphabetical) "A to Z" else "Most used" }) { v -> set { it.copy(drawerSort = v) } } }
    item { SwitchPref("Open keyboard automatically", "Start typing as soon as the drawer opens", s.autoKeyboard) { v -> set { it.copy(autoKeyboard = v) } } }
    item { SwitchPref("Suggested apps row", "Your most used apps, learnt on device", s.showSuggestions) { v -> set { it.copy(showSuggestions = v) } } }
    item { ClickPref("Hidden apps", "${s.hiddenApps.size} hidden", Icons.Default.Lock) { go(Page.Hidden) } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.icons(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit, graph: AppGraph) {
    item {
        ChoicePref("Icon shape", IconShape.entries, s.iconShape, {
            when (it) { IconShape.System -> "System default"; IconShape.RoundedSquare -> "Rounded square"; else -> it.name }
        }) { v -> set { it.copy(iconShape = v) } }
    }
    item { IconPackPref(s, set) }
    if (Build.VERSION.SDK_INT >= 33) item {
        SwitchPref("Themed icons", "Tint icons that support it with your wallpaper colours", s.themedIcons) { v -> set { it.copy(themedIcons = v) } }
    }
    item { NotificationDotsPref(s, set) }
    item {
        val context = LocalContext.current
        ClickPref("Clear icon cache", "Re-render every icon") {
            graph.icons.clearAll()
            Toast.makeText(context, "Icons will be redrawn", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun IconPackPref(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = LocalContext.current
    val packs by produceState(emptyList<Pair<String, String>>()) { value = withContext(Dispatchers.IO) { IconPack.installed(context) } }
    val options = listOf<Pair<String?, String>>(null to "System icons") + packs
    ChoicePref("Icon pack", options, options.firstOrNull { it.first == s.iconPack } ?: options.first(), { if (packs.isEmpty() && it.first == null) "System icons (no packs installed)" else it.second }) { v ->
        set { it.copy(iconPack = v.first) }
    }
}

@Composable
private fun NotificationDotsPref(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = LocalContext.current
    val granted = remember { NotificationDotsService.isEnabled(context) }
    SwitchPref(
        "Notification dots",
        if (granted) "Show a dot on apps with notifications" else "Needs notification access — tap to grant",
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
    item { ClickPref("Swipe up", "Open the app drawer") {} }
    item {
        ChoicePref("Swipe down", SwipeDownAction.entries, s.swipeDown, {
            when (it) { SwipeDownAction.Notifications -> "Notifications"; SwipeDownAction.QuickSettings -> "Quick settings"; SwipeDownAction.Search -> "Search"; SwipeDownAction.None -> "Nothing" }
        }) { v -> set { it.copy(swipeDown = v) } }
    }
    item {
        ChoicePref("Double-tap", DoubleTapAction.entries, s.doubleTap, { if (it == DoubleTapAction.LockScreen) "Lock screen" else "Nothing" }) { v -> set { it.copy(doubleTap = v) } }
    }
    item {
        val context = LocalContext.current
        val on = GestureAccessibilityService.instance != null
        ClickPref("Lock-screen permission", if (on) "Enabled" else "Double-tap to lock needs the “Nbeta gestures” accessibility service") {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
    item { ClickPref("Swipe right", "Open the feed") {} }
}

private fun androidx.compose.foundation.lazy.LazyListScope.search(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    item { ContactsPref(s, set) }
    item { SwitchPref("Calculator", "Type 12*7 or sqrt(2)", s.searchCalculator) { v -> set { it.copy(searchCalculator = v) } } }
    item { SwitchPref("App shortcuts", "Find actions like “New message” or “Scan QR”", s.searchShortcuts) { v -> set { it.copy(searchShortcuts = v) } } }
    item { SwitchPref("System settings", "Jump to Wi‑Fi, Bluetooth, Battery…", s.searchSettings) { v -> set { it.copy(searchSettings = v) } } }
    item { ChoicePref("Web search", WebEngine.entries, s.webEngine, { it.label }) { v -> set { it.copy(webEngine = v) } } }
}

@Composable
private fun ContactsPref(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    SwitchPref("Contacts", if (granted) "Call or message from search" else "Tap to allow contact access", s.searchContacts && granted) { v ->
        if (v && !granted) req.launch(Manifest.permission.READ_CONTACTS)
        set { it.copy(searchContacts = v) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.feed(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit, graph: AppGraph) {
    item { SwitchPref("Feed page", "Swipe right from home for your feed", s.feedEnabled) { v -> set { it.copy(feedEnabled = v) } } }
    item { SectionHeader("Sources") }
    item { AddSourceRow(graph) }
    items(s.feedSources, key = { it.id }) { src ->
        val cache by graph.feed.cache.collectAsStateWithLifecycle()
        val st = cache.status[src.id]
        val muted = src.id in cache.mutedSources
        ClickPref(
            src.title,
            when {
                st?.error != null -> "Error: ${st.error}"
                muted -> "Shown less in “For you” · tap to restore"
                st != null -> "${st.count} stories · ${src.url}"
                else -> src.url
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { set { it.copy(feedSources = it.feedSources.filterNot { f -> f.id == src.id }) } }) { Icon(Icons.Default.Delete, "Remove") }
                    Switch(src.enabled, { v -> set { it.copy(feedSources = it.feedSources.map { f -> if (f.id == src.id) f.copy(enabled = v) else f }) } })
                }
            },
        ) { if (muted) graph.feed.muteSource(src.id, false) }
    }
    val suggestions = DefaultFeeds.catalog.filter { c -> s.feedSources.none { it.url == c.url } }
    if (suggestions.isNotEmpty()) {
        item { SectionHeader("Suggestions") }
        items(suggestions, key = { "sug" + it.id }) { c ->
            ClickPref(c.title, c.siteUrl, Icons.Default.Add) {
                set { it.copy(feedSources = it.feedSources + c) }
                graph.scope.launch { graph.feed.refresh(c.id) }
            }
        }
    }
    item { SectionHeader("Reading") }
    item { ChoicePref("Default order", FeedOrder.entries, s.feedOrder, { if (it == FeedOrder.ForYou) "For you (learns what you open)" else "Latest first" }) { v -> set { it.copy(feedOrder = v) } } }
    item { ChoicePref("Open stories in", LinkOpener.entries, s.linkOpener, { if (it == LinkOpener.CustomTab) "In-app browser tab (fastest)" else "Browser app" }) { v -> set { it.copy(linkOpener = v) } } }
    item { SwitchPref("Fetch article images", "For stories whose feed has no picture", s.feedFetchImages) { v -> set { it.copy(feedFetchImages = v) } } }
    item { SectionHeader("Background refresh") }
    item {
        ChoicePref("Refresh every", listOf(0, 1, 2, 4, 8, 12), s.feedRefreshHours, { if (it == 0) "Only when opened" else "$it hour" + if (it > 1) "s" else "" }) { v ->
            set { it.copy(feedRefreshHours = v) }
        }
    }
    item { SwitchPref("Wi‑Fi only", null, s.feedWifiOnly) { v -> set { it.copy(feedWifiOnly = v) } } }
    item { SectionHeader("OPML") }
    item { OpmlPrefs(s, set, graph) }
    item { ClickPref("Clear stories", "Keeps saved stories") { graph.feed.clear() } }
}

@Composable
private fun AddSourceRow(graph: AppGraph) {
    var open by remember { mutableStateOf(false) }
    ClickPref("Add a source", "Website, feed URL, YouTube channel or subreddit", Icons.Default.Add) { open = true }
    if (!open) return
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text("Add source") },
        text = {
            Column {
                OutlinedTextField(text, { text = it; error = null }, singleLine = true, label = { Text("e.g. theverge.com") }, isError = error != null, supportingText = { error?.let { Text(it) } })
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Looking for a feed…")
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
                            error = "Already added"
                        } else {
                            graph.settings.update { it.copy(feedSources = it.feedSources + src) }
                            graph.scope.launch { graph.feed.refresh(src.id) }
                            open = false
                        }
                    } catch (e: Exception) {
                        error = e.message ?: "Couldn't find a feed"
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = { open = false }, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun OpmlPrefs(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit, graph: AppGraph) {
    val context = LocalContext.current
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
            Toast.makeText(context, "Imported ${found.size} feeds", Toast.LENGTH_SHORT).show()
            graph.scope.launch { graph.feed.refresh() }
        }
    }
    Column {
        ClickPref("Import OPML", "From another reader") { import.launch(arrayOf("*/*")) }
        ClickPref("Export OPML", "${s.feedSources.size} sources") { export.launch("nbeta-feeds.opml") }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.weather(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit, graph: AppGraph) {
    item { ChoicePref("Units", TempUnit.entries, s.tempUnit, { if (it == TempUnit.Celsius) "Celsius" else "Fahrenheit" }) { v -> set { it.copy(tempUnit = v) } } }
    item { WeatherLocationPref(s, set, graph) }
    item { ClickPref("Weather data", "Open-Meteo (no account, no tracking)") {} }
}

@Composable
private fun WeatherLocationPref(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit, graph: AppGraph) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) graph.glance.refresh(forceWeather = true) }
    ClickPref("Location", s.weatherCity ?: "Device location") { open = true }
    if (!open) return
    var city by remember { mutableStateOf(s.weatherCity.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Weather location") },
        text = { OutlinedTextField(city, { city = it }, singleLine = true, label = { Text("City") }) },
        confirmButton = {
            TextButton(enabled = city.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    val r = graph.glance.geocode(city)
                    busy = false
                    if (r == null) {
                        Toast.makeText(context, "City not found", Toast.LENGTH_SHORT).show()
                    } else {
                        set { it.copy(weatherCity = r.first, weatherLat = r.second, weatherLon = r.third) }
                        graph.glance.refresh(forceWeather = true)
                        open = false
                    }
                }
            }) { Text("Use city") }
        },
        dismissButton = {
            TextButton(onClick = {
                set { it.copy(weatherCity = null, weatherLat = null, weatherLon = null) }
                req.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                open = false
            }) { Text("Use device location") }
        },
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.hidden(s: LauncherSettings, set: ((LauncherSettings) -> LauncherSettings) -> Unit, graph: AppGraph) {
    item { Text("Hidden apps stay installed but no longer appear in the drawer or in search.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
            Toast.makeText(context, if (ok) "Settings restored" else "That file isn't an Nbeta backup", Toast.LENGTH_SHORT).show()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ClickPref("Export settings", "Layout, sources, hidden apps and preferences") { export.launch("nbeta-backup.json") }
        ClickPref("Import settings") { import.launch(arrayOf("application/json", "*/*")) }
        ClickPref("Reset everything", "Back to defaults") { confirmReset = true }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all settings?") },
            text = { Text("Your home layout, feed sources and preferences will return to their defaults.") },
            confirmButton = {
                TextButton(onClick = {
                    graph.settings.import("{}")
                    confirmReset = false
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
