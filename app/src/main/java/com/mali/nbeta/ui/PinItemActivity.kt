package com.mali.nbeta.ui

import android.appwidget.AppWidgetManager
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.mali.nbeta.NbetaApp
import com.mali.nbeta.data.HomeItem
import com.mali.nbeta.data.WidgetPlacement
import com.mali.nbeta.ui.theme.NbetaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Confirms "Add to home screen" requests from other apps (browser shortcuts, app widgets). */
class PinItemActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as NbetaApp).graph
        val la = getSystemService(LauncherApps::class.java)
        val request = runCatching { la.getPinItemRequest(intent) }.getOrNull()
        if (request == null || !request.isValid) {
            finish()
            return
        }
        val isShortcut = request.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT
        val shortcut = request.shortcutInfo
        val widget = request.getAppWidgetProviderInfo(this)
        val label = when {
            isShortcut -> (shortcut?.shortLabel ?: shortcut?.longLabel ?: "Shortcut").toString()
            else -> widget?.loadLabel(packageManager) ?: "Widget"
        }

        setContent {
            NbetaTheme(graph.settings.value) {
                val icon by produceState<ImageBitmap?>(null) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            val d = if (isShortcut && shortcut != null) la.getShortcutIconDrawable(shortcut, resources.displayMetrics.densityDpi)
                            else widget?.loadPreviewImage(this@PinItemActivity, resources.displayMetrics.densityDpi) ?: widget?.loadIcon(this@PinItemActivity, resources.displayMetrics.densityDpi)
                            d?.toBitmap(256, (256f * d.intrinsicHeight / d.intrinsicWidth.coerceAtLeast(1)).toInt().coerceIn(1, 512))?.asImageBitmap()
                        }.getOrNull()
                    }
                }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(if (isShortcut) "Add to home screen?" else "Add widget?") },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            icon?.let { Image(it, null, Modifier.size(if (isShortcut) 64.dp else 160.dp)) }
                            Spacer(Modifier.height(12.dp))
                            Text(label)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (isShortcut && shortcut != null) {
                                if (request.accept()) {
                                    val serial = getSystemService(UserManager::class.java).getSerialNumberForUser(shortcut.userHandle)
                                    graph.settings.update { s ->
                                        s.copy(homeItems = s.homeItems + HomeItem.Shortcut(shortcut.`package`, shortcut.id, serial, label))
                                    }
                                }
                            } else if (widget != null) {
                                val id = graph.widgets.allocate()
                                val ok = request.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) })
                                if (ok) graph.widgets.add(id, WidgetPlacement.Home) else graph.widgets.discard(id)
                            }
                            finish()
                        }) { Text("Add") }
                    },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Cancel") } },
                )
            }
        }
    }
}
