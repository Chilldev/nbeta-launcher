package com.mali.nbeta.ui.feed

import android.Manifest
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mali.nbeta.R
import com.mali.nbeta.data.FeedOrder
import com.mali.nbeta.data.FeedSource
import com.mali.nbeta.data.LinkOpener
import com.mali.nbeta.data.WidgetPlacement
import com.mali.nbeta.data.feed.FeedCache
import com.mali.nbeta.data.feed.FeedFilter
import com.mali.nbeta.data.feed.FeedItem
import com.mali.nbeta.data.glance.WeatherCodes
import com.mali.nbeta.ui.LauncherController
import com.mali.nbeta.ui.common.LocalGraph
import com.mali.nbeta.ui.home.formatTemp
import com.mali.nbeta.ui.settings.SettingsActivity
import com.mali.nbeta.ui.reader.ReaderActivity
import com.mali.nbeta.ui.theme.isDark
import com.mali.nbeta.ui.widgets.WidgetFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun FeedPage(c: LauncherController, active: Boolean) {
    val graph = LocalGraph.current
    val feed = graph.feed
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val cache by feed.cache.collectAsStateWithLifecycle()
    val refreshing by feed.refreshing.collectAsStateWithLifecycle()
    val filter by feed.filter.collectAsStateWithLifecycle()
    val setFilter: (FeedFilter) -> Unit = { feed.filter.value = it }
    val dark = isDark(settings)
    val scope = rememberCoroutineScope()
    val listState = c.feedListState
    val sources = remember(settings.feedSources) { settings.feedSources.associateBy { it.id } }

    // Ranked off the main thread in the repository; available immediately when the page comes back.
    val arranged by feed.arranged.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val searchResults by produceState(emptyList<FeedItem>(), query, cache) {
        value = if (query.isBlank()) emptyList() else withContext(Dispatchers.Default) { feed.search(query) }
    }
    val items = if (searching && query.isNotBlank()) searchResults else arranged
    androidx.activity.compose.BackHandler(enabled = searching) { searching = false; query = "" }

    LaunchedEffect(active) {
        if (active) {
            feed.refreshIfStale()
            graph.glance.refresh()
            if (settings.linkOpener != LinkOpener.Browser) c.activity.customTabs.warmup()
        }
    }
    LaunchedEffect(active, items.firstOrNull()?.link) {
        val first = items.firstOrNull() ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        when (settings.linkOpener) {
            LinkOpener.CustomTab -> c.activity.customTabs.mayLaunch(first.link)
            // Reader: extract the top stories ahead of time (unmetered networks only) so they open instantly.
            LinkOpener.Reader -> graph.reader.prefetch(items.take(4).map { it.link })
            LinkOpener.Browser -> Unit
        }
    }

    val open: (FeedItem) -> Unit = { item ->
        feed.markRead(item)
        when (settings.linkOpener) {
            LinkOpener.Reader -> c.start(
                ReaderActivity.intent(c.activity, item.link, item.id, sources[item.sourceId]?.title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            else -> c.activity.customTabs.open(item.link, dark, settings.linkOpener == LinkOpener.CustomTab)
        }
    }

    var muteFor by remember { mutableStateOf<FeedItem?>(null) }
    muteFor?.let { item -> MuteKeywordDialog(item, onDismiss = { muteFor = null }) { k -> graph.settings.update { it.copy(mutedKeywords = (it.mutedKeywords + k).distinct()) } } }

    val insets = WindowInsets.systemBars.asPaddingValues()
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { feed.refresh() } },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = insets.calculateTopPadding() + 8.dp, bottom = insets.calculateBottomPadding() + 24.dp, start = 12.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (searching) {
                    item(key = "search", contentType = "search") {
                        FeedSearchBar(query, { query = it }) { searching = false; query = "" }
                    }
                    if (query.isNotBlank() && items.isEmpty()) {
                        item(key = "no-results") { EmptyState(stringResource(R.string.feed_search_none), null) {} }
                    }
                } else {
                item(key = "header", contentType = "header") { FeedHeader(c, cache, refreshing, onSearch = { searching = true }) { scope.launch { feed.refresh() } } }
                if (!settings.setupCardDismissed) item(key = "setup", contentType = "setup") { SetupCard(c) }
                item(key = "media", contentType = "media") { com.mali.nbeta.ui.home.MediaCard() }
                item(key = "today", contentType = "today") { TodayCard(c) }
                val feedWidgets = settings.widgets.filter { it.placement == WidgetPlacement.Feed }
                items(feedWidgets, key = { "w${it.id}" }, contentType = { "widget" }) { slot ->
                    WidgetFrame(c, slot, Modifier.clip(RoundedCornerShape(24.dp)))
                }
                item(key = "filters", contentType = "filters") {
                    FilterRow(settings.feedSources, filter, settings.feedOrder, setFilter) { order ->
                        graph.settings.update { it.copy(feedOrder = order) }
                    }
                }
                }
                if (searching) {
                    // Results only (below).
                } else if (settings.feedSources.none { it.enabled }) {
                    item(key = "empty") {
                        EmptyState(stringResource(R.string.feed_empty_no_sources), stringResource(R.string.feed_add_sources)) {
                            c.start(Intent(c.activity, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, "feed").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                } else if (items.isEmpty() && cache.items.isEmpty()) {
                    if (refreshing) items(3, key = { "sk$it" }) { SkeletonCard() }
                    else item(key = "empty") { EmptyState(stringResource(R.string.feed_load_failed), stringResource(R.string.feed_try_again)) { scope.launch { feed.refresh() } } }
                } else if (items.isEmpty()) {
                    item(key = "none") { EmptyState(stringResource(if (filter == FeedFilter.Saved) R.string.feed_nothing_saved else R.string.feed_caught_up), null) {} }
                }
                items(items, key = { it.id }, contentType = { if (it.imageUrl != null) "hero" else "compact" }) { item ->
                    LaunchedEffect(item.id) { feed.requestImage(item) }
                    FeedCard(
                        item = item,
                        source = sources[item.sourceId],
                        read = item.id in cache.read,
                        saved = cache.saved.any { it.id == item.id },
                        onOpen = { open(item) },
                        onSave = { feed.toggleSaved(item) },
                        onHide = { feed.dismiss(item) },
                        onMute = { feed.muteSource(item.sourceId, true) },
                        onOnlySource = { setFilter(FeedFilter.Source(item.sourceId)) },
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${item.title}\n${item.link}")
                            c.start(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        onBrowser = { c.start(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(item.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                        onMuteKeyword = { muteFor = item },
                    )
                }
            }
        }
        // Keep the status bar legible over scrolled cards.
        Box(
            Modifier
                .fillMaxWidth()
                .height(insets.calculateTopPadding())
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
        )
    }
    }
}

@Composable
private fun FeedHeader(c: LauncherController, cache: FeedCache, refreshing: Boolean, onSearch: () -> Unit, onRefresh: () -> Unit) {
    val now = System.currentTimeMillis()
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.common_today), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            val updated = when {
                cache.lastRefresh == 0L -> stringResource(R.string.feed_not_updated)
                now - cache.lastRefresh < 60_000 -> stringResource(R.string.feed_updated_now)
                else -> stringResource(R.string.feed_updated, DateUtils.getRelativeTimeSpanString(cache.lastRefresh, now, DateUtils.MINUTE_IN_MILLIS))
            }
            val locale = LocalConfiguration.current.locales[0]
            Text(
                SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale).format(Date(now)) + " · " +
                    if (refreshing) stringResource(R.string.feed_updating) else updated,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSearch) { Icon(Icons.Default.Search, stringResource(R.string.feed_search)) }
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, stringResource(R.string.feed_refresh)) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.common_more)) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.feed_mark_all_read)) }, onClick = { menu = false; c.graph.feed.markAllRead() })
                DropdownMenuItem(text = { Text(stringResource(R.string.feed_add_widget_here)) }, onClick = { menu = false; c.widgetPicker = WidgetPlacement.Feed })
                DropdownMenuItem(text = { Text(stringResource(R.string.feed_settings)) }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = {
                    menu = false
                    c.start(Intent(c.activity, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, "feed").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            }
        }
    }
}

@Composable
private fun TodayCard(c: LauncherController) {
    val graph = LocalGraph.current
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val weather by graph.glance.weather.collectAsStateWithLifecycle()
    val events by graph.glance.events.collectAsStateWithLifecycle()
    val context = c.activity
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) graph.glance.refresh() }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) graph.glance.refresh(forceWeather = true) }
    val needCalendar = settings.glanceCalendar && !graph.glance.hasCalendar()
    val needLocation = settings.glanceWeather && weather == null && settings.weatherLat == null &&
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
    val w = weather
    if (w == null && events.isEmpty() && !needCalendar && !needLocation) return

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(18.dp),
    ) {
        if (w != null && settings.glanceWeather) {
            val (emoji, desc) = WeatherCodes.describe(w.code, w.isDay)
            val descText = desc?.let { stringResource(it) }.orEmpty()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 34.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${formatTemp(w.tempC, settings.tempUnit)} $descText", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        stringResource(R.string.feed_weather_high_low, formatTemp(w.highC, settings.tempUnit), formatTemp(w.lowC, settings.tempUnit)) +
                            (w.place?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
        if (events.isNotEmpty() && settings.glanceCalendar) {
            if (w != null) Spacer(Modifier.height(14.dp))
            val is24 = android.text.format.DateFormat.is24HourFormat(context)
            val fmt = SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", LocalConfiguration.current.locales[0])
            val allDay = stringResource(R.string.feed_all_day)
            events.take(3).forEach { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            val uri = android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, e.id)
                            c.start(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(4.dp).height(32.dp).clip(RoundedCornerShape(2.dp)).background(Color(e.color or 0xFF000000.toInt())))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            if (e.allDay) allDay else fmt.format(Date(e.begin)) + " – " + fmt.format(Date(e.end)) + (e.location?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
        if (needCalendar || needLocation) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (needLocation) AssistChip(onClick = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }, label = { Text(stringResource(R.string.feed_show_weather)) })
                if (needCalendar) AssistChip(onClick = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }, label = { Text(stringResource(R.string.feed_show_calendar)) })
            }
        }
    }
}

@Composable
private fun FilterRow(
    sources: List<FeedSource>,
    filter: FeedFilter,
    order: FeedOrder,
    onFilter: (FeedFilter) -> Unit,
    onOrder: (FeedOrder) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
        item {
            FilterChip(filter == FeedFilter.All && order == FeedOrder.ForYou, onClick = { onFilter(FeedFilter.All); onOrder(FeedOrder.ForYou) }, label = { Text(stringResource(R.string.feed_for_you)) })
        }
        item {
            FilterChip(filter == FeedFilter.All && order == FeedOrder.Latest, onClick = { onFilter(FeedFilter.All); onOrder(FeedOrder.Latest) }, label = { Text(stringResource(R.string.feed_latest)) })
        }
        item { FilterChip(filter == FeedFilter.Unread, onClick = { onFilter(FeedFilter.Unread) }, label = { Text(stringResource(R.string.feed_unread)) }) }
        item { FilterChip(filter == FeedFilter.Saved, onClick = { onFilter(FeedFilter.Saved) }, label = { Text(stringResource(R.string.feed_saved)) }) }
        items(sources.filter { it.enabled }, key = { it.id }) { s ->
            val selected = filter is FeedFilter.Source && filter.id == s.id
            FilterChip(selected, onClick = { onFilter(if (selected) FeedFilter.All else FeedFilter.Source(s.id)) }, label = { Text(s.title, maxLines = 1) })
        }
    }
}

private fun host(url: String?): String? = url?.let { runCatching { URI(it).host?.removePrefix("www.") }.getOrNull() }

@Composable
private fun FeedCard(
    item: FeedItem,
    source: FeedSource?,
    read: Boolean,
    saved: Boolean,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onHide: () -> Unit,
    onMute: () -> Unit,
    onOnlySource: () -> Unit,
    onShare: () -> Unit,
    onBrowser: () -> Unit,
    onMuteKeyword: () -> Unit,
) {
    val titleColor = if (read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onOpen),
    ) {
        if (item.imageUrl != null) {
            AsyncImage(
                model = item.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 23.sp),
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.imageUrl == null && item.summary != null) {
                Spacer(Modifier.height(6.dp))
                Text(item.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val h = host(source?.siteUrl) ?: host(item.link)
            Box(Modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                if (h != null) AsyncImage("https://icons.duckduckgo.com/ip3/$h.ico", null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                (source?.title ?: h ?: "") + " · " + DateUtils.getRelativeTimeSpanString(item.published, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSave) {
                Icon(
                    if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    stringResource(if (saved) R.string.feed_unsave else R.string.common_save),
                    tint = if (saved) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.common_more), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_share)) }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.feed_open_in_browser)) }, onClick = { menu = false; onBrowser() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.feed_hide_story)) }, onClick = { menu = false; onHide() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.feed_mute_keyword_menu)) }, onClick = { menu = false; onMuteKeyword() })
                    source?.let { s ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.feed_more_from, s.title)) }, onClick = { menu = false; onOnlySource() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.feed_fewer_from, s.title)) }, onClick = { menu = false; onMute() })
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        Box(Modifier.padding(16.dp).fillMaxWidth(0.8f).height(18.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        Box(Modifier.padding(start = 16.dp, bottom = 16.dp).fillMaxWidth(0.5f).height(14.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
    }
}

@Composable
private fun EmptyState(text: String, action: String?, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** Offers the story's longer words as one-tap suggestions, plus free text. */
@Composable
private fun MuteKeywordDialog(item: FeedItem, onDismiss: () -> Unit, onMute: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val suggestions = remember(item.id) {
        val words = item.title.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.distinct()
        // Capitalised words are the likely names in Latin scripts; scripts without case (Arabic) offer the longest words.
        words.filter { it.length >= 4 && it.first().isUpperCase() }.ifEmpty { words.sortedByDescending { it.length } }.take(6)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feed_mute_keyword_title)) },
        text = {
            Column {
                Text(stringResource(R.string.feed_mute_keyword_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { w -> AssistChip(onClick = { text = w }, label = { Text(w) }) }
                }
                androidx.compose.material3.OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(stringResource(R.string.feed_keyword)) })
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onMute(text.trim()); onDismiss() }) { Text(stringResource(R.string.feed_mute)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun SetupCard(c: LauncherController) {
    val context = c.activity
    var tick by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val missing = remember(tick) { com.mali.nbeta.ui.settings.Permissions.missing(context) }
    if (missing == 0) return
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
    ) {
        Text(androidx.compose.ui.res.stringResource(com.mali.nbeta.R.string.perm_setup_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(
            androidx.compose.ui.res.pluralStringResource(com.mali.nbeta.R.plurals.perm_setup_banner, missing, missing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { c.graph.settings.update { it.copy(setupCardDismissed = true) } }) {
                Text(androidx.compose.ui.res.stringResource(com.mali.nbeta.R.string.common_not_now))
            }
            TextButton(onClick = {
                c.start(Intent(context, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, "permissions").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text(androidx.compose.ui.res.stringResource(com.mali.nbeta.R.string.perm_setup_review)) }
        }
    }
}

@Composable
private fun FeedSearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(stringResource(R.string.feed_search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Clear, stringResource(R.string.common_clear)) }
    }
}
