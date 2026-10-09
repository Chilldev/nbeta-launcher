package com.mali.nbeta.ui.widgets

import android.appwidget.AppWidgetProviderInfo
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mali.nbeta.data.WidgetPlacement
import com.mali.nbeta.R
import com.mali.nbeta.data.WidgetSlot
import com.mali.nbeta.data.widgets.WidgetProviderGroup
import com.mali.nbeta.ui.LauncherController
import com.mali.nbeta.ui.common.LocalGraph
import com.mali.nbeta.ui.menu.MenuRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun WidgetColumn(c: LauncherController, slots: List<WidgetSlot>, editing: Boolean) {
    if (slots.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        slots.forEach { slot -> key(slot.id) { WidgetFrame(c, slot, editing = editing) } }
    }
}

@Composable
fun WidgetFrame(c: LauncherController, slot: WidgetSlot, modifier: Modifier = Modifier, editing: Boolean = false) {
    val repo = LocalGraph.current.widgets
    val info = remember(slot.id) { repo.info(slot.id) }
    val openMenu = { c.widgetMenu = slot.id }
    if (info == null) {
        // The provider is gone (uninstalled, restored from another device...). Keep it removable.
        Box(
            modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
                .combinedClickable(onClick = openMenu, onLongClick = openMenu),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.widget_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val saved = if (slot.heightDp > 0) slot.heightDp else remember(slot.id) { repo.defaultHeightDp(slot.id) }
    // Live height while the resize handle is dragged; written to settings once, on release.
    var live by remember(slot.id, saved) { mutableStateOf(saved.toFloat()) }
    val height = live.roundToInt()
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .then(if (editing) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)) else Modifier)
            .widgetLongPress(openMenu),
    ) {
        val width = maxWidth
        AndroidView(
            factory = { ctx -> repo.view(slot.id) ?: FrameLayout(ctx) },
            modifier = Modifier.fillMaxSize(),
        )
        LaunchedEffect(slot.id, width, height) { repo.reportSize(slot.id, width.value, height.toFloat()) }
        if (editing) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .size(width = 64.dp, height = 28.dp)
                    .pointerInput(slot.id) {
                        detectVerticalDragGestures(
                            onDragEnd = { repo.update(slot.id) { it.copy(heightDp = live.roundToInt()) } },
                        ) { change, dy ->
                            change.consume()
                            live = (live + with(density) { dy.toDp().value }).coerceIn(56f, 720f)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 48.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

/**
 * Detects a long-press over a widget in the Initial pass, i.e. before the widget's own views see the events, so it
 * works on empty areas too and needs no timers in the hosted View. Once it fires, the rest of the gesture is consumed
 * (the widget receives a cancel). Movement beyond touch slop hands the gesture to the widget or the pager.
 */
private fun Modifier.widgetLongPress(onLongPress: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || change.isConsumed) break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
        }
        if (released == null) {
            onLongPress()
            do {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                ev.changes.forEach { it.consume() }
            } while (ev.changes.any { it.pressed })
        }
    }
}

@Composable
fun WidgetPickerSheet(c: LauncherController, placement: WidgetPlacement) {
    val repo = LocalGraph.current.widgets
    val groups by produceState(emptyList<WidgetProviderGroup>()) { value = withContext(Dispatchers.IO) { repo.providers() } }
    var expanded by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = { c.widgetPicker = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            stringResource(R.string.home_add_widget),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(groups, key = { it.packageName }) { g ->
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expanded = if (expanded == g.packageName) null else g.packageName }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIconSmall(g.packageName)
                        Spacer(Modifier.width(16.dp))
                        Text(g.appLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.settings_number, g.providers.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (expanded == g.packageName) {
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 12.dp),
                        ) {
                            items(g.providers, key = { it.first.provider.flattenToShortString() + it.first.profile.hashCode() }) { (info, label) ->
                                ProviderCard(info, label) {
                                    c.widgetPicker = null
                                    c.widgets.add(info, placement)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIconSmall(pkg: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(36.dp)) { icon?.let { Image(it, null, Modifier.size(36.dp)) } }
}

@Composable
private fun ProviderCard(info: AppWidgetProviderInfo, label: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.densityDpi
    val preview by produceState<ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                (info.loadPreviewImage(context, density) ?: info.loadIcon(context, density))?.let { d ->
                    val w = d.intrinsicWidth.takeIf { it > 0 } ?: 300
                    val h = d.intrinsicHeight.takeIf { it > 0 } ?: 200
                    val scale = minOf(1f, 600f / maxOf(w, h))
                    d.toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1)).asImageBitmap()
                }
            }.getOrNull()
        }
    }
    val dm = context.resources.displayMetrics
    Column(
        Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 140.dp), contentAlignment = Alignment.Center) {
            preview?.let { Image(it, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontWeight = FontWeight.Medium, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
        val wCells = ((info.minWidth / dm.density) / 70).toInt().coerceAtLeast(1)
        val hCells = ((info.minHeight / dm.density) / 70).toInt().coerceAtLeast(1)
        Text(stringResource(R.string.widget_size, wCells, hCells), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun WidgetMenuSheet(c: LauncherController, id: Int) {
    val graph = LocalGraph.current
    val repo = graph.widgets
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val slot = settings.widgets.firstOrNull { it.id == id }
    ModalBottomSheet(onDismissRequest = { c.widgetMenu = null }) {
        val fallback = stringResource(R.string.common_widget)
        val label by produceState(fallback, id) {
            value = withContext(Dispatchers.IO) { repo.info(id)?.loadLabel(c.activity.packageManager) ?: fallback }
        }
        Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            val meta by produceState<Pair<Int, Boolean>?>(null, id) {
                value = withContext(Dispatchers.IO) { repo.defaultHeightDp(id) to repo.isReconfigurable(id) }
            }
            if (slot != null) {
                val current = if (slot.heightDp > 0) slot.heightDp else meta?.first ?: 120
                MenuRow(Icons.Default.Add, stringResource(R.string.widget_taller)) { repo.update(id) { it.copy(heightDp = (current + 40).coerceAtMost(720)) } }
                MenuRow(Icons.Default.Close, stringResource(R.string.widget_shorter)) { repo.update(id) { it.copy(heightDp = (current - 40).coerceAtLeast(56)) } }
                MenuRow(Icons.Default.KeyboardArrowUp, stringResource(R.string.widget_move_up)) { repo.move(id, -1) }
                MenuRow(Icons.Default.KeyboardArrowDown, stringResource(R.string.widget_move_down)) { repo.move(id, +1) }
                val other = if (slot.placement == WidgetPlacement.Home) WidgetPlacement.Feed else WidgetPlacement.Home
                MenuRow(Icons.Default.Share, stringResource(if (other == WidgetPlacement.Feed) R.string.widget_move_to_feed else R.string.widget_move_to_home)) {
                    repo.update(id) { it.copy(placement = other) }
                    c.widgetMenu = null
                }
                if (meta?.second == true) {
                    MenuRow(Icons.Default.Settings, stringResource(R.string.widget_reconfigure)) {
                        c.widgetMenu = null
                        c.widgets.reconfigure(id)
                    }
                }
                if (slot.heightDp > 0) MenuRow(Icons.Default.Refresh, stringResource(R.string.widget_reset_size)) { repo.update(id) { it.copy(heightDp = 0) } }
            }
            MenuRow(Icons.Default.Close, stringResource(R.string.common_remove)) {
                repo.remove(id)
                c.widgetMenu = null
            }
        }
    }
}
