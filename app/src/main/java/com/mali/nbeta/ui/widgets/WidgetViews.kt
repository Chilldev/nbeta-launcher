package com.mali.nbeta.ui.widgets

import android.appwidget.AppWidgetProviderInfo
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mali.nbeta.data.WidgetPlacement
import com.mali.nbeta.data.WidgetSlot
import com.mali.nbeta.data.widgets.WidgetProviderGroup
import com.mali.nbeta.ui.LauncherController
import com.mali.nbeta.ui.common.LocalGraph
import com.mali.nbeta.ui.menu.MenuRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun WidgetColumn(c: LauncherController, slots: List<WidgetSlot>, onWallpaper: Boolean) {
    if (slots.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        slots.forEach { slot -> key(slot.id) { WidgetFrame(c, slot) } }
    }
}

@Composable
fun WidgetFrame(c: LauncherController, slot: WidgetSlot, modifier: Modifier = Modifier) {
    val repo = LocalGraph.current.widgets
    val height = if (slot.heightDp > 0) slot.heightDp else remember(slot.id) { repo.defaultHeightDp(slot.id) }
    BoxWithConstraints(modifier.fillMaxWidth().height(height.dp)) {
        val width = maxWidth
        AndroidView(
            factory = { ctx -> repo.view(slot.id) ?: FrameLayout(ctx) },
            modifier = Modifier.fillMaxSize(),
        )
        LaunchedEffect(slot.id, width, height) { repo.reportSize(slot.id, width.value, height.toFloat()) }
    }
}

@Composable
fun WidgetPickerSheet(c: LauncherController, placement: WidgetPlacement) {
    val repo = LocalGraph.current.widgets
    val groups by produceState(emptyList<WidgetProviderGroup>()) { value = withContext(Dispatchers.IO) { repo.providers() } }
    var expanded by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = { c.widgetPicker = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            "Add widget",
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
                        Text(g.appLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("${g.providers.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (expanded == g.packageName) {
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 12.dp),
                        ) {
                            items(g.providers, key = { it.provider.flattenToShortString() + it.profile.hashCode() }) { info ->
                                ProviderCard(info) {
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
private fun ProviderCard(info: AppWidgetProviderInfo, onClick: () -> Unit) {
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
        Text(info.loadLabel(context.packageManager), fontWeight = FontWeight.Medium, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
        val wCells = ((info.minWidth / dm.density) / 70).toInt().coerceAtLeast(1)
        val hCells = ((info.minHeight / dm.density) / 70).toInt().coerceAtLeast(1)
        Text("$wCells × $hCells", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun WidgetMenuSheet(c: LauncherController, id: Int) {
    val graph = LocalGraph.current
    val repo = graph.widgets
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val slot = settings.widgets.firstOrNull { it.id == id }
    ModalBottomSheet(onDismissRequest = { c.widgetMenu = null }) {
        val label = remember(id) { repo.info(id)?.loadLabel(c.activity.packageManager) ?: "Widget" }
        Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            if (slot != null) {
                val current = if (slot.heightDp > 0) slot.heightDp else repo.defaultHeightDp(id)
                MenuRow(Icons.Default.Add, "Taller") { repo.update(id) { it.copy(heightDp = (current + 40).coerceAtMost(720)) } }
                MenuRow(Icons.Default.Close, "Shorter") { repo.update(id) { it.copy(heightDp = (current - 40).coerceAtLeast(56)) } }
                MenuRow(Icons.Default.KeyboardArrowUp, "Move up") { repo.move(id, -1) }
                MenuRow(Icons.Default.KeyboardArrowDown, "Move down") { repo.move(id, +1) }
                val other = if (slot.placement == WidgetPlacement.Home) WidgetPlacement.Feed else WidgetPlacement.Home
                MenuRow(Icons.Default.Share, if (other == WidgetPlacement.Feed) "Move to feed page" else "Move to home screen") {
                    repo.update(id) { it.copy(placement = other) }
                    c.widgetMenu = null
                }
                if (repo.isReconfigurable(id)) {
                    MenuRow(Icons.Default.Settings, "Reconfigure") {
                        c.widgetMenu = null
                        c.widgets.reconfigure(id)
                    }
                }
                if (slot.heightDp > 0) MenuRow(Icons.Default.Refresh, "Reset size") { repo.update(id) { it.copy(heightDp = 0) } }
            }
            MenuRow(Icons.Default.Close, "Remove") {
                repo.remove(id)
                c.widgetMenu = null
            }
        }
    }
}
