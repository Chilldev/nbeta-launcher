package com.mali.nbeta.ui.menu

import android.content.Intent
import android.content.pm.LauncherApps
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.mali.nbeta.data.HomeItem
import com.mali.nbeta.data.WidgetPlacement
import com.mali.nbeta.data.apps.AppEntry
import com.mali.nbeta.data.apps.AppShortcut
import com.mali.nbeta.ui.LauncherController
import com.mali.nbeta.ui.MenuRequest
import com.mali.nbeta.ui.MenuTarget
import com.mali.nbeta.ui.Origin
import com.mali.nbeta.ui.common.BoundsHolder
import com.mali.nbeta.ui.common.IconImage
import com.mali.nbeta.ui.common.rememberShortcutIcon
import com.mali.nbeta.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class AnchorAbove(private val anchor: android.graphics.Rect, private val margin: Int) : PopupPositionProvider {
    var placedAbove = true
        private set

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchor.centerX() - popupContentSize.width / 2).coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val above = anchor.top - popupContentSize.height - margin / 2
        placedAbove = above > margin * 3
        val y = if (placedAbove) above else (anchor.bottom + margin / 2).coerceAtMost(windowSize.height - popupContentSize.height - margin)
        return IntOffset(x, y)
    }
}

@Composable
fun AppMenuPopup(c: LauncherController, req: MenuRequest) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val provider = remember(req) { AnchorAbove(req.anchor, with(density) { 16.dp.roundToPx() }) }
    val visible = remember(req) { MutableTransitionState(false).apply { targetState = true } }
    val dismiss = { c.menu = null }
    Popup(popupPositionProvider = provider, onDismissRequest = dismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(
            visible,
            enter = fadeIn(spring(stiffness = 1500f)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 1200f), 0.85f, TransformOrigin(0.5f, if (provider.placedAbove) 1f else 0f)),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 4.dp,
                shadowElevation = 12.dp,
                modifier = Modifier.width(272.dp),
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    when (val t = req.target) {
                        is MenuTarget.App -> AppMenuContent(c, t.app, t.origin, dismiss)
                        is MenuTarget.PinnedShortcut -> {
                            MenuRow(Icons.Default.Close, "Remove from home") {
                                c.removeItem(t.item)
                                c.graph.shortcuts.unpin(t.item.packageName, t.item.id, t.item.userSerial)
                                dismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppMenuContent(c: LauncherController, app: AppEntry, origin: Origin, dismiss: () -> Unit) {
    val graph = c.graph
    // Binder calls (shortcuts, ApplicationInfo) happen off the main thread, once per menu.
    val loaded by produceState<Pair<List<AppShortcut>, Boolean>?>(null, app.key) {
        value = withContext(Dispatchers.IO) { graph.shortcuts.forApp(app) to graph.apps.isSystemApp(app) }
    }
    val shortcuts = loaded?.first.orEmpty()
    shortcuts.forEach { s -> ShortcutMenuRow(c, app, s, dismiss) }
    if (shortcuts.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 6.dp, horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

    // Quick actions as an icon row, the rest as a list.
    val onHome = c.isOnHome(app.key)
    val inDock = c.isInDock(app.key)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        QuickAction(Icons.Default.Info, "App info") { graph.apps.openAppInfo(app); dismiss() }
        QuickAction(if (onHome) Icons.Default.Close else Icons.Default.Home, if (onHome) "Off home" else "To home") { c.toggleHome(app.key); dismiss() }
        QuickAction(if (inDock) Icons.Default.KeyboardArrowDown else Icons.Default.Star, if (inDock) "Off dock" else "To dock") { c.toggleDock(app.key); dismiss() }
    }
    HorizontalDivider(Modifier.padding(vertical = 6.dp, horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    MenuRow(Icons.Default.Edit, "Rename") { c.renameTarget = app; dismiss() }
    if (origin == Origin.Drawer || origin == Origin.Search) {
        MenuRow(Icons.Default.Lock, "Hide from drawer") { c.hide(app); dismiss() }
    }
    if (loaded?.second == false) {
        MenuRow(Icons.Default.Delete, "Uninstall") { graph.apps.uninstall(app); dismiss() }
    }
}

@Composable
private fun ShortcutMenuRow(c: LauncherController, app: AppEntry, s: AppShortcut, dismiss: () -> Unit) {
    val bmp = rememberShortcutIcon(s)
    val bounds = remember { BoundsHolder() }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                c.launchShortcut(s, bounds)
                dismiss()
            }
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconImage(bmp, 30.dp, null, Modifier.onPlaced { bounds.coords = it })
        Spacer(Modifier.width(14.dp))
        Text(s.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = {
            pinToHome(c, app, s)
            dismiss()
        }) { Icon(Icons.Default.Add, "Add to home", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun pinToHome(c: LauncherController, app: AppEntry, s: AppShortcut) {
    val la = c.activity.getSystemService(LauncherApps::class.java)
    c.graph.scope.launch(Dispatchers.IO) {
        try {
            val q = LauncherApps.ShortcutQuery().setPackage(app.packageName).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val pinned = la.getShortcuts(q, app.user).orEmpty().map { it.id }
            la.pinShortcuts(app.packageName, (pinned + s.info.id).distinct(), app.user)
            val item = HomeItem.Shortcut(app.packageName, s.info.id, app.userSerial, s.label)
            c.graph.settings.update { st -> if (item in st.homeItems) st else st.copy(homeItems = st.homeItems + item) }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(c.activity, "Set Nbeta as your home app to pin shortcuts", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun HomeMenuSheet(c: LauncherController) {
    ModalBottomSheet(onDismissRequest = { c.homeMenu = false }) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            MenuRow(Icons.Default.Create, "Wallpaper") {
                c.homeMenu = false
                c.start(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Set wallpaper").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            MenuRow(Icons.Default.Add, "Widgets") {
                c.homeMenu = false
                c.widgetPicker = WidgetPlacement.Home
            }
            MenuRow(Icons.Default.Edit, "Edit home screen") {
                c.homeMenu = false
                c.editingHome = true
            }
            MenuRow(Icons.Default.Settings, "Launcher settings") {
                c.homeMenu = false
                c.start(Intent(c.activity, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

@Composable
fun RenameDialog(c: LauncherController, app: AppEntry) {
    var text by remember(app.key) { mutableStateOf(app.label) }
    AlertDialog(
        onDismissRequest = { c.renameTarget = null },
        title = { Text("Rename") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Label") })
                if (app.label != app.originalLabel) {
                    TextButton(onClick = { text = app.originalLabel }) { Text("Reset to “${app.originalLabel}”") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                c.rename(app, text)
                c.renameTarget = null
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { c.renameTarget = null }) { Text("Cancel") } },
    )
}
