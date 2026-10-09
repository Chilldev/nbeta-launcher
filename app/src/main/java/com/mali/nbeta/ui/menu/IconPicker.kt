package com.mali.nbeta.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mali.nbeta.data.apps.AppEntry
import com.mali.nbeta.data.apps.IconPack
import com.mali.nbeta.data.search.TextFold
import com.mali.nbeta.ui.LauncherController
import com.mali.nbeta.ui.common.IconImage
import com.mali.nbeta.ui.common.LocalGraph
import com.mali.nbeta.ui.common.rememberAppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pick any icon from any installed icon pack for one app, or go back to its default. */
@Composable
fun IconPickerSheet(c: LauncherController, app: AppEntry) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val dismiss = { c.iconPickerFor = null }
    val packs by produceState(emptyList<Pair<String, String>>()) { value = withContext(Dispatchers.IO) { IconPack.installed(context) } }
    var selected by remember { mutableStateOf<String?>(null) }
    val pkg = selected ?: packs.firstOrNull()?.first
    var query by remember { mutableStateOf("") }
    val icons by produceState<List<String>>(emptyList(), pkg, query) {
        value = withContext(Dispatchers.Default) {
            val pack = pkg?.let { graph.icons.pack(it) } ?: return@withContext emptyList()
            val all = pack.allIcons
            val suggestion = pack.suggestion(app.component)
            val q = TextFold.fold(query.trim())
            val filtered = if (q.isEmpty()) all else all.filter { TextFold.fold(it.replace('_', ' ')).contains(q) }
            listOfNotNull(suggestion?.takeIf { q.isEmpty() || it in filtered }) + filtered.filter { it != suggestion }
        }
    }
    val choose: (String?) -> Unit = { value ->
        graph.settings.update { s -> s.copy(iconOverrides = if (value == null) s.iconOverrides - app.key else s.iconOverrides + (app.key to value)) }
        dismiss()
    }

    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconImage(rememberAppIcon(app), 48.dp, null)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleLarge)
                    Text("Choose an icon", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (app.key in graph.settings.value.iconOverrides) {
                    androidx.compose.material3.TextButton(onClick = { choose(null) }) { Text("Reset") }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (packs.isEmpty()) {
                Text(
                    "No icon packs installed. Install one from the Play Store (search “icon pack”), then come back here to pick icons app by app.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                return@Column
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(packs, key = { it.first }) { (p, label) -> FilterChip(p == pkg, onClick = { selected = p }, label = { Text(label) }) }
            }
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search icons") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(64.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.heightIn(max = 460.dp),
            ) {
                items(icons, key = { it }) { name ->
                    val bmp by produceState<ImageBitmap?>(null, pkg, name) { value = pkg?.let { graph.icons.packIcon(it, name) } }
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { choose("$pkg/$name") }
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) { IconImage(bmp, 44.dp, name.replace('_', ' '), Modifier.size(44.dp)) }
                }
            }
        }
    }
}
