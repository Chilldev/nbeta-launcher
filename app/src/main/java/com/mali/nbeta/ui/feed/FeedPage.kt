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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
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
import com.mali.nbeta.ui.theme.isDark
import com.mali.nbeta.ui.widgets.WidgetFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val items by feed.arranged.collectAsStateWithLifecycle()

    LaunchedEffect(active) {
        if (active) {
            feed.refreshIfStale()
            graph.glance.refresh()
            if (settings.linkOpener == LinkOpener.CustomTab) c.activity.customTabs.warmup()
        }
    }
    LaunchedEffect(active, items.firstOrNull()?.link) {
        val first = items.firstOrNull()
        if (active && first != null && settings.linkOpener == LinkOpener.CustomTab) c.activity.customTabs.mayLaunch(first.link)
    }

    val open: (FeedItem) -> Unit = { item ->
        feed.markRead(item)
        c.activity.customTabs.open(item.link, dark, settings.linkOpener == LinkOpener.CustomTab)
    }

    val insets = WindowInsets.systemBars.asPaddingValues()
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
                item(key = "header", contentType = "header") { FeedHeader(c, cache, refreshing) { scope.launch { feed.refresh() } } }
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
                if (settings.feedSources.none { it.enabled }) {
                    item(key = "empty") {
                        EmptyState("Your feed has no sources yet.", "Add sources") {
                            c.start(Intent(c.activity, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, "feed").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                } else if (items.isEmpty() && cache.items.isEmpty()) {
                    if (refreshing) items(3, key = { "sk$it" }) { SkeletonCard() }
                    else item(key = "empty") { EmptyState("Couldn't load stories. Check your connection.", "Try again") { scope.launch { feed.refresh() } } }
                } else if (items.isEmpty()) {
                    item(key = "none") { EmptyState(if (filter == FeedFilter.Saved) "Nothing saved yet. Tap ♡ on a story to keep it." else "You're all caught up.", null) {} }
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

@Composable
private fun FeedHeader(c: LauncherController, cache: FeedCache, refreshing: Boolean, onRefresh: () -> Unit) {
    val now = System.currentTimeMillis()
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Today", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            val updated = when {
                cache.lastRefresh == 0L -> "Not updated yet"
                now - cache.lastRefresh < 60_000 -> "Updated just now"
                else -> "Updated " + DateUtils.getRelativeTimeSpanString(cache.lastRefresh, now, DateUtils.MINUTE_IN_MILLIS)
            }
            Text(
                SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(now)) + " · " + if (refreshing) "Updating…" else updated,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Mark all as read") }, onClick = { menu = false; c.graph.feed.markAllRead() })
                DropdownMenuItem(text = { Text("Add widget here") }, onClick = { menu = false; c.widgetPicker = WidgetPlacement.Feed })
                DropdownMenuItem(text = { Text("Feed settings") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 34.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${formatTemp(w.tempC, settings.tempUnit)} $desc", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "H ${formatTemp(w.highC, settings.tempUnit)} · L ${formatTemp(w.lowC, settings.tempUnit)}" + (w.place?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
        if (events.isNotEmpty() && settings.glanceCalendar) {
            if (w != null) Spacer(Modifier.height(14.dp))
            val is24 = android.text.format.DateFormat.is24HourFormat(context)
            val fmt = SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", Locale.getDefault())
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
                            if (e.allDay) "All day" else fmt.format(Date(e.begin)) + " – " + fmt.format(Date(e.end)) + (e.location?.let { " · $it" } ?: ""),
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
                if (needLocation) AssistChip(onClick = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }, label = { Text("Show weather") })
                if (needCalendar) AssistChip(onClick = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }, label = { Text("Show calendar") })
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
            FilterChip(filter == FeedFilter.All && order == FeedOrder.ForYou, onClick = { onFilter(FeedFilter.All); onOrder(FeedOrder.ForYou) }, label = { Text("For you") })
        }
        item {
            FilterChip(filter == FeedFilter.All && order == FeedOrder.Latest, onClick = { onFilter(FeedFilter.All); onOrder(FeedOrder.Latest) }, label = { Text("Latest") })
        }
        item { FilterChip(filter == FeedFilter.Unread, onClick = { onFilter(FeedFilter.Unread) }, label = { Text("Unread") }) }
        item { FilterChip(filter == FeedFilter.Saved, onClick = { onFilter(FeedFilter.Saved) }, label = { Text("Saved") }) }
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
                    if (saved) "Unsave" else "Save",
                    tint = if (saved) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("Open in browser") }, onClick = { menu = false; onBrowser() })
                    DropdownMenuItem(text = { Text("Hide this story") }, onClick = { menu = false; onHide() })
                    source?.let { s ->
                        DropdownMenuItem(text = { Text("More from ${s.title}") }, onClick = { menu = false; onOnlySource() })
                        DropdownMenuItem(text = { Text("Fewer from ${s.title}") }, onClick = { menu = false; onMute() })
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
