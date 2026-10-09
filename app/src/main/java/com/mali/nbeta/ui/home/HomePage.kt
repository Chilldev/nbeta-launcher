package com.mali.nbeta.ui.home

import android.content.Intent
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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HomePage(c: LauncherController, settings: LauncherSettings) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val editing = c.editingHome
    var pullDown by remember { mutableStateOf(0f) }
    val pullThreshold = with(density) { 56.dp.toPx() }
    val scope = rememberCoroutineScope()

    val dragState = rememberDraggableState { dy ->
        if (c.drawer.isClosed && dy > 0f) pullDown += dy else c.drawer.dragBy(dy)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { c.drawer.height = it.height.toFloat().coerceAtLeast(1f) }
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
                enabled = !editing,
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
            },
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 12.dp)) {
            Glance(c, settings)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                WidgetColumn(c, settings.widgets.filter { it.placement == WidgetPlacement.Home }, onWallpaper = true)
            }
            AnimatedVisibility(editing, enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { c.widgetPicker = WidgetPlacement.Home }) { Text("Add widget", color = LocalOnWallpaper.current.text) }
                    TextButton(onClick = { c.editingHome = false }) { Text("Done", color = LocalOnWallpaper.current.text) }
                }
            }
            ItemGrid(c, settings.homeItems, settings.homeColumns, settings.iconSizeDp.dp, settings.homeLabels, inDock = false, editing = editing) { c.editingHome = true }
            if (settings.showHomeSearch) {
                SearchPill(onClick = { c.openSearch() })
            }
            if (settings.showDock && settings.dockItems.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                ItemGrid(c, settings.dockItems, maxOf(settings.dockItems.size, 4), settings.iconSizeDp.dp, false, inDock = true, editing = editing) { c.editingHome = true }
            }
            Spacer(Modifier.height(8.dp))
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
        Text("Search apps, contacts, web…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Home grid / dock. Positions are absolute so that in edit mode an item can be dragged freely while its neighbours
 * animate into their new slots.
 */
@Composable
private fun ItemGrid(
    c: LauncherController,
    items: List<HomeItem>,
    columns: Int,
    iconSize: Dp,
    labels: Boolean,
    inDock: Boolean,
    editing: Boolean,
    onStartEditing: () -> Unit,
) {
    val graph = LocalGraph.current
    val byKey by graph.apps.byKey.collectAsStateWithLifecycle()
    val visible = remember(items, byKey) {
        items.filter { it !is HomeItem.App || byKey.containsKey(it.key) }
    }
    if (visible.isEmpty()) return
    var order by remember(visible) { mutableStateOf(visible) }
    var dragging by remember { mutableStateOf<HomeItem?>(null) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val labelStyle = wallpaperLabelStyle()
    val cellHeight = iconSize + if (labels) 34.dp else 14.dp

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = maxWidth / columns
        val cellW = with(density) { cellWidth.toPx() }
        val cellH = with(density) { cellHeight.toPx() }
        val rows = (order.size + columns - 1) / columns
        Box(Modifier.fillMaxWidth().height(cellHeight * rows)) {
            order.forEachIndexed { index, item ->
                key(item) {
                    val slot = IntOffset(((index % columns) * cellW).roundToInt(), ((index / columns) * cellH).roundToInt())
                    val animated by animateIntOffsetAsState(slot, spring(stiffness = 700f), label = "slot")
                    val isDragged = dragging == item
                    val pos = if (isDragged) IntOffset((dragStart.x + dragDelta.x).roundToInt(), (dragStart.y + dragDelta.y).roundToInt()) else animated
                    Box(
                        Modifier
                            .offset { pos }
                            .width(cellWidth)
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer {
                                val s = if (isDragged) 1.12f else 1f
                                scaleX = s
                                scaleY = s
                            }
                            .then(
                                if (editing) Modifier.pointerInput(item, columns, cellW, cellH, rows) {
                                    detectDragGestures(
                                        onDragStart = {
                                            val idx = order.indexOf(item)
                                            dragging = item
                                            dragStart = Offset((idx % columns) * cellW, (idx / columns) * cellH)
                                            dragDelta = Offset.Zero
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragDelta += amount
                                            val cx = dragStart.x + dragDelta.x + cellW / 2
                                            val cy = dragStart.y + dragDelta.y + cellH / 2
                                            val col = (cx / cellW).toInt().coerceIn(0, columns - 1)
                                            val row = (cy / cellH).toInt().coerceIn(0, rows - 1)
                                            val target = (row * columns + col).coerceIn(0, order.size - 1)
                                            val from = order.indexOf(item)
                                            if (target != from) order = order.toMutableList().apply { add(target, removeAt(from)) }
                                        },
                                        onDragEnd = {
                                            c.moveItem(item, inDock, order.indexOf(item))
                                            dragging = null
                                        },
                                        onDragCancel = { dragging = null },
                                    )
                                } else Modifier,
                            ),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        HomeItemTile(c, item, iconSize, labels, labelStyle, if (inDock) Origin.Dock else Origin.Home, editing, onStartEditing)
                        if (editing) {
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
                                Icon(Icons.Default.Close, "Remove", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeItemTile(
    c: LauncherController,
    item: HomeItem,
    iconSize: Dp,
    labels: Boolean,
    labelStyle: TextStyle,
    origin: Origin,
    editing: Boolean,
    onStartEditing: () -> Unit,
) {
    val graph = LocalGraph.current
    when (item) {
        is HomeItem.App -> {
            val app = graph.apps.byKey.collectAsStateWithLifecycle().value[item.key] ?: return
            AppTile(
                app, iconSize, labels, labelStyle,
                onClick = { b -> if (!editing) c.launch(app, b) },
                onLongClick = { b -> if (!editing) b.rect()?.let { c.menu = MenuRequest(MenuTarget.App(app, origin), it) } else onStartEditing() },
            )
        }
        is HomeItem.Shortcut -> {
            val shortcut by produceState<AppShortcut?>(null, item) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    graph.shortcuts.pinned(item.packageName, item.id, item.userSerial)
                }
            }
            val bmp = shortcut?.let { rememberShortcutIcon(it) }
            Tile(
                bmp, item.label, iconSize, labels, labelStyle, hasDot = false,
                onClick = { b -> if (!editing) shortcut?.let { c.launchShortcut(it, b) } },
                onLongClick = { b -> if (!editing) b.rect()?.let { c.menu = MenuRequest(MenuTarget.PinnedShortcut(item, origin), it) } },
            )
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
    val locale = Locale.getDefault()

    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 12.dp)) {
        if (s.glanceClock) {
            Text(
                SimpleDateFormat(if (is24) "HH:mm" else "h:mm", locale).format(Date(now)),
                style = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Light, color = w.text, shadow = w.shadow, letterSpacing = (-1).sp),
                modifier = Modifier.clickable(interactionSource = null, indication = null) {
                    c.start(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            )
        }
        Text(
            SimpleDateFormat("EEEE, MMMM d", locale).format(Date(now)),
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
                next.allDay -> "Today"
                next.begin <= now -> "Now"
                next.begin - now < 3600_000 -> "in ${((next.begin - now) / 60_000).coerceAtLeast(1)} min"
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
    TempUnit.Celsius -> "${c.roundToInt()}°"
    TempUnit.Fahrenheit -> "${(c * 9 / 5 + 32).roundToInt()}°"
}
