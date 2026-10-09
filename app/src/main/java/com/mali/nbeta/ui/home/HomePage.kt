package com.mali.nbeta.ui.home

import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import com.mali.nbeta.data.Container
import com.mali.nbeta.R
import com.mali.nbeta.data.homePages
import com.mali.nbeta.data.stableKey
import com.mali.nbeta.ui.common.IconImage
import com.mali.nbeta.ui.common.rememberAppIcon
import com.mali.nbeta.ui.dnd.DragItem
import com.mali.nbeta.ui.dnd.DropTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.mali.nbeta.data.DoubleTapAction
import com.mali.nbeta.data.HomeItem
import com.mali.nbeta.data.LauncherSettings
import com.mali.nbeta.data.SwipeDownAction
import com.mali.nbeta.data.TempUnit
import com.mali.nbeta.data.WidgetPlacement
import com.mali.nbeta.data.apps.AppShortcut
import com.mali.nbeta.data.glance.WeatherCodes
import com.mali.nbeta.system.GlobalActions
import com.mali.nbeta.ui.LauncherController
import com.mali.nbeta.ui.MenuRequest
import com.mali.nbeta.ui.MenuTarget
import com.mali.nbeta.ui.Origin
import com.mali.nbeta.ui.common.AppTile
import com.mali.nbeta.ui.common.LocalGraph
import com.mali.nbeta.ui.common.LocalOnWallpaper
import com.mali.nbeta.ui.common.Tile
import com.mali.nbeta.ui.common.rememberShortcutIcon
import com.mali.nbeta.ui.common.wallpaperLabelStyle
import com.mali.nbeta.ui.widgets.WidgetColumn
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Swipe up/down, double-tap, long-press and the "recede as the drawer rises" effect, shared by pages and the dock. */
@Composable
fun Modifier.homeGestures(c: LauncherController, settings: LauncherSettings): Modifier {
    val context = LocalContext.current
    val density = LocalDensity.current
    val editing = c.editingHome
    var pullDown by remember { mutableStateOf(0f) }
    val pullThreshold = with(density) { 56.dp.toPx() }
    val scope = rememberCoroutineScope()
    val dragState = rememberDraggableState { dy ->
        if (c.drawer.isClosed && dy > 0f) pullDown += dy else c.drawer.dragBy(dy)
    }
    return this
        .pointerInput(settings.doubleTap, editing) {
            detectTapGestures(
                onDoubleTap = if (settings.doubleTap == DoubleTapAction.LockScreen && !editing) {
                    { GlobalActions.lockScreen(context) }
                } else null,
                onLongPress = {
                    // A long-press over a widget opens the widget's menu instead (both detectors fire together).
                    if (!editing) scope.launch {
                        delay(60)
                        if (c.widgetMenu == null) c.homeMenu = true
                    }
                },
                onTap = { if (editing) c.editingHome = false },
            )
        }
        .draggable(
            dragState,
            Orientation.Vertical,
            enabled = !editing && !c.dnd.active,
            onDragStarted = { pullDown = 0f },
            onDragStopped = { velocity ->
                if (pullDown > pullThreshold && c.drawer.isClosed) {
                    when (settings.swipeDown) {
                        SwipeDownAction.Notifications -> GlobalActions.expandNotifications(context)
                        SwipeDownAction.QuickSettings -> GlobalActions.expandQuickSettings(context)
                        SwipeDownAction.Search -> c.openSearch()
                        SwipeDownAction.None -> Unit
                    }
                } else {
                    c.drawer.settle(velocity)
                }
                pullDown = 0f
            },
        )
        .graphicsLayer {
            // Home recedes as the drawer rises.
            val p = c.drawer.progress
            alpha = 1f - p
            val s = 1f - 0.06f * p
            scaleX = s
            scaleY = s
            translationY = -p * 48.dp.toPx()
        }
}

/** One home page: the glance (first page only), that page's widgets, and its app grid. The dock is drawn separately. */
@Composable
fun HomePageContent(c: LauncherController, settings: LauncherSettings, page: Int) {
    val density = LocalDensity.current
    val editing = c.editingHome
    val items = settings.homePages.getOrNull(page).orEmpty()
    val scrim = LocalOnWallpaper.current.scrim
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { c.drawer.height = it.height.toFloat().coerceAtLeast(1f) }
            .drawBehind {
                // Behind the status bar and the glance: solid for the first fifth, gone by ~40% of the height.
                drawRect(
                    Brush.verticalGradient(0f to scrim, 0.48f to scrim, 1f to Color.Transparent, endY = size.height * 0.42f),
                    size = size.copy(height = size.height * 0.42f),
                )
            }
            .homeGestures(c, settings),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(bottom = with(density) { c.dockHeightPx.toDp() }),
        ) {
            if (page == 0) {
                Glance(c, settings)
                MediaCard(Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            } else {
                Spacer(Modifier.height(24.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                WidgetColumn(c, settings.widgets.filter { it.placement == WidgetPlacement.Home && it.page == page }, editing)
            }
            AnimatedVisibility(editing, enter = fadeIn(), exit = fadeOut()) {
                // On a pill: these sit mid-wallpaper, where neither scrim reaches.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f))
                            .padding(horizontal = 4.dp),
                    ) {
                        val color = MaterialTheme.colorScheme.primary
                        TextButton(onClick = { c.widgetPicker = WidgetPlacement.Home }) { Text(stringResource(R.string.home_add_widget), color = color) }
                        TextButton(onClick = { c.addPage() }) { Text(stringResource(R.string.home_add_page), color = color) }
                        TextButton(onClick = { c.editingHome = false }) { Text(stringResource(R.string.common_done), color = color) }
                    }
                }
            }
            ItemGrid(c, Container.Page(page), items, settings.homeColumns, settings.iconSizeDp.dp, settings.homeLabels, wallpaperLabelStyle())
        }
    }
}

/** Page dots, search pill and dock: fixed while home pages change, sliding away with them towards the feed. */
@Composable
fun DockArea(
    c: LauncherController,
    settings: LauncherSettings,
    pageCount: Int,
    position: () -> Float,
    firstHomePage: Int,
    modifier: Modifier = Modifier,
) {
    val onWallpaperScrim = LocalOnWallpaper.current.scrim
    val awayFromFeed = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    Column(
        modifier
            .fillMaxWidth()
            .onPlaced {
                c.dockBounds = it.boundsInWindow()
                c.dockHeightPx = it.size.height
            }
            .graphicsLayer {
                // Off to the side as the feed slides in (hit-testing follows the translation).
                translationX = (firstHomePage - position()).coerceAtLeast(0f) * size.width * awayFromFeed
            }
            .drawBehind {
                val scrim = onWallpaperScrim
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, scrim), startY = 0f, endY = size.height * 0.6f))
                drawRect(scrim, topLeft = Offset(0f, size.height * 0.6f), size = size.copy(height = size.height * 0.4f))
            }
            .homeGestures(c, settings)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp),
    ) {
        if (pageCount > 1 && (settings.showPageDots || c.dnd.active)) PageDots(pageCount, { position() - firstHomePage })
        if (settings.showHomeSearch) SearchPill(onClick = { c.openSearch() })
        if (settings.showDock) {
            val dock = settings.dockItems
            val columns = (dock.size + if (c.dnd.active && c.dnd.item?.origin != Container.Dock) 1 else 0)
                .coerceIn(4, LauncherController.MAX_DOCK)
            ItemGrid(c, Container.Dock, dock, columns, settings.iconSizeDp.dp, false, wallpaperLabelStyle(), capacity = LauncherController.MAX_DOCK)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PageDots(count: Int, position: () -> Float) {
    val color = LocalOnWallpaper.current.text
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(7.dp)
                    .graphicsLayer {
                        val d = abs(position() - i).coerceAtMost(1f)
                        alpha = 1f - 0.5f * d
                        val sc = 1.15f - 0.3f * d
                        scaleX = sc
                        scaleY = sc
                    }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.home_search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun originOf(container: Container) = when (container) {
    is Container.Page -> Origin.Home
    Container.Dock -> Origin.Dock
    is Container.Folder -> Origin.Folder
}

/**
 * A grid of home items (a page, the dock, or a folder). Positions are absolute and animated, so while something is
 * dragged over it the others slide apart to open a gap. The dragged item's own tile stays composed but invisible:
 * it owns the gesture.
 */
@Composable
fun ItemGrid(
    c: LauncherController,
    container: Container,
    items: List<HomeItem>,
    columns: Int,
    iconSize: Dp,
    labels: Boolean,
    labelStyle: TextStyle,
    capacity: Int = Int.MAX_VALUE,
    acceptsDrops: Boolean = true,
) {
    val graph = LocalGraph.current
    val dnd = c.dnd
    val byKey by graph.apps.byKey.collectAsStateWithLifecycle()
    val visible = remember(items, byKey) {
        items.filter {
            when (it) {
                is HomeItem.App -> byKey.containsKey(it.key)
                is HomeItem.Folder -> it.items.any { i -> i !is HomeItem.App || byKey.containsKey(i.key) }
                is HomeItem.Shortcut -> true
            }
        }
    }
    val dragged = dnd.item
    val target = dnd.target
    val base = if (dragged != null) visible.filter { it.stableKey != dragged.item.stableKey } else visible
    val insertAt = (target as? DropTarget.Insert)?.takeIf { it.container == container }?.index
    val mergeKey = (target as? DropTarget.Merge)?.takeIf { it.container == container }?.target?.stableKey
    // While dragging, reserve room for one more item so the drop area doesn't jump under the finger.
    val slotsShown = if (dragged != null && acceptsDrops && base.size < capacity) base.size + 1 else visible.size
    if (slotsShown == 0) return
    val rows = (slotsShown + columns - 1) / columns
    val density = LocalDensity.current
    val cellHeight = iconSize + if (labels) 34.dp else 14.dp
    val zone = if (acceptsDrops) remember(container) { dnd.zone(container, columns) } else null
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    SideEffect {
        zone?.let {
            it.columns = columns
            it.items = base
            it.capacity = capacity
            it.rtl = rtl
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = maxWidth / columns
        val cellW = with(density) { cellWidth.toPx() }
        val cellH = with(density) { cellHeight.toPx() }
        Box(
            Modifier
                .fillMaxWidth()
                .height(cellHeight * rows)
                .onPlaced { co ->
                    zone?.let {
                        it.coords = co
                        it.cellW = cellW
                        it.cellH = cellH
                        it.iconCenterY = with(density) { (6.dp + iconSize / 2).toPx() }
                    }
                },
        ) {
            visible.forEach { item ->
                key(item.stableKey) {
                    val isDragged = dragged?.item?.stableKey == item.stableKey
                    val index = if (isDragged) visible.indexOf(item) else {
                        val i = base.indexOf(item)
                        if (insertAt != null && i >= insertAt) i + 1 else i
                    }
                    val slot = IntOffset(((index % columns) * cellW).roundToInt(), ((index / columns) * cellH).roundToInt())
                    val animated by animateIntOffsetAsState(slot, spring(stiffness = 700f), label = "slot")
                    Box(
                        Modifier
                            .offset { if (isDragged) slot else animated }
                            .width(cellWidth),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        HomeItemTile(c, item, container, iconSize, labels, labelStyle, hidden = isDragged, highlight = mergeKey == item.stableKey)
                        if (c.editingHome && !isDragged && container !is Container.Folder) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(end = 6.dp)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .clickable { c.removeItem(item) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Close, stringResource(R.string.common_remove), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeItemTile(
    c: LauncherController,
    item: HomeItem,
    container: Container,
    iconSize: Dp,
    labels: Boolean,
    labelStyle: TextStyle,
    hidden: Boolean = false,
    highlight: Boolean = false,
) {
    val graph = LocalGraph.current
    val origin = originOf(container)
    when (item) {
        is HomeItem.App -> {
            val app = graph.apps.byKey.collectAsStateWithLifecycle().value[item.key] ?: return
            AppTile(
                app, iconSize, labels, labelStyle,
                onClick = { b -> c.launch(app, b) },
                onLongClick = { b -> b.rect()?.let { c.menu = MenuRequest(MenuTarget.App(app, origin, container), it) } },
                origin = container,
                item = item,
                hidden = hidden,
                highlight = highlight,
            )
        }
        is HomeItem.Shortcut -> {
            val shortcut by produceState<AppShortcut?>(null, item) {
                value = withContext(Dispatchers.IO) { graph.shortcuts.pinned(item.packageName, item.id, item.userSerial) }
            }
            val bmp = shortcut?.let { rememberShortcutIcon(it) }
            Tile(
                bmp, item.label, iconSize, labels, labelStyle, hasDot = false,
                onClick = { b -> shortcut?.let { c.launchShortcut(it, b) } },
                onLongClick = { b -> b.rect()?.let { c.menu = MenuRequest(MenuTarget.PinnedShortcut(item, origin), it) } },
                dragItem = { DragItem(item, container, null, bmp) },
                hidden = hidden,
                highlight = highlight,
            )
        }
        is HomeItem.Folder -> {
            val byKey by graph.apps.byKey.collectAsStateWithLifecycle()
            val firstApp = item.items.firstNotNullOfOrNull { (it as? HomeItem.App)?.let { a -> byKey[a.key] } }
            val firstIcon = firstApp?.let { rememberAppIcon(it) }
            Tile(
                null, item.name, iconSize, labels, labelStyle, hasDot = false,
                onClick = { c.openFolder = item.id },
                onLongClick = { b -> b.rect()?.let { c.menu = MenuRequest(MenuTarget.Folder(item), it) } },
                dragItem = { DragItem(item, container, null, firstIcon) },
                hidden = hidden,
                highlight = highlight,
                icon = { FolderIcon(item, iconSize) },
            )
        }
    }
}

/** Up to four mini icons on a translucent plate. */
@Composable
fun FolderIcon(folder: HomeItem.Folder, size: Dp) {
    val graph = LocalGraph.current
    val byKey by graph.apps.byKey.collectAsStateWithLifecycle()
    val apps = folder.items.mapNotNull { (it as? HomeItem.App)?.let { a -> byKey[a.key] } }.take(4)
    val mini = size * 0.36f
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            // A tinted container plus a hairline keeps the plate visible on both dark and light wallpapers.
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f))
            .border(1.dp, MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.16f), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(size * 0.06f)) {
            apps.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(size * 0.06f)) {
                    row.forEach { app -> IconImage(rememberAppIcon(app), mini, null) }
                    if (row.size == 1) Spacer(Modifier.size(mini))
                }
            }
        }
    }
}

@Composable
private fun Glance(c: LauncherController, s: LauncherSettings) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val w = LocalOnWallpaper.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Ticks on the minute, only while the home screen is visible.
    val now by produceState(System.currentTimeMillis()) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(60_000 - value % 60_000 + 50)
            }
        }
    }
    val weather by graph.glance.weather.collectAsStateWithLifecycle()
    val events by graph.glance.events.collectAsStateWithLifecycle()
    val alarm by graph.glance.nextAlarm.collectAsStateWithLifecycle()
    val is24 = DateFormat.is24HourFormat(context)
    val locale = LocalConfiguration.current.locales[0]

    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 12.dp)) {
        if (s.glanceClock) {
            Text(
                SimpleDateFormat(if (is24) "HH:mm" else "h:mm", locale).format(Date(now)),
                style = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Normal, color = w.text, shadow = w.shadow, letterSpacing = (-1).sp),
                modifier = Modifier.clickable(interactionSource = null, indication = null) {
                    c.start(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            )
        }
        Text(
            SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale).format(Date(now)),
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, color = w.text, shadow = w.shadow),
            modifier = Modifier.clickable(interactionSource = null, indication = null) {
                val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(now.toString()).build()
                c.start(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
        Spacer(Modifier.height(6.dp))
        val chip = TextStyle(fontSize = 14.sp, color = w.secondary, shadow = w.shadow)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val wx = weather
            if (s.glanceWeather && wx != null) {
                val (emoji, _) = WeatherCodes.describe(wx.code, wx.isDay)
                Text("$emoji ${formatTemp(wx.tempC, s.tempUnit)}${wx.place?.let { " $it" } ?: ""}", style = chip)
            }
            val a = alarm
            if (s.glanceAlarm && a != null && a - now < 24 * 3600_000L) {
                Text("⏰ " + SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", locale).format(Date(a)), style = chip,
                    modifier = Modifier.clickable(interactionSource = null, indication = null) {
                        c.start(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
            }
        }
        val next = events.firstOrNull { !it.allDay } ?: events.firstOrNull()
        if (s.glanceCalendar && next != null) {
            Spacer(Modifier.height(4.dp))
            val whenText = when {
                next.allDay -> stringResource(R.string.common_today)
                next.begin <= now -> stringResource(R.string.glance_now)
                next.begin - now < 3600_000 -> ((next.begin - now) / 60_000).coerceAtLeast(1).toInt().let { pluralStringResource(R.plurals.glance_in_minutes, it, it) }
                else -> SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", locale).format(Date(next.begin))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(interactionSource = null, indication = null) {
                    val uri = android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, next.id)
                    c.start(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(next.color or 0xFF000000.toInt())))
                Spacer(Modifier.width(8.dp))
                Text("${next.title} · $whenText", style = chip, maxLines = 1)
            }
        }
    }
}

fun formatTemp(c: Double, unit: TempUnit): String = when (unit) {
    TempUnit.Celsius -> "%d°".format(c.roundToInt())
    TempUnit.Fahrenheit -> "%d°".format((c * 9 / 5 + 32).roundToInt())
}
