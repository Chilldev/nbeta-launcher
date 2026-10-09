package com.mali.nbeta.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.mali.nbeta.NbetaApp
import com.mali.nbeta.R
import com.mali.nbeta.data.Container
import com.mali.nbeta.data.HomeItem
import com.mali.nbeta.data.Layout
import com.mali.nbeta.data.WidgetPlacement
import com.mali.nbeta.ui.theme.NbetaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Confirms "Add to home screen" requests from other apps. Widgets follow the same path as the picker: bind (asking
 * once if needed), accept the request with the bound id, run the provider's configuration, then place.
 */
class PinItemActivity : ComponentActivity() {
    private val graph get() = (application as NbetaApp).graph
    private lateinit var request: LauncherApps.PinItemRequest
    private var pendingId = -1

    private val bind = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) acceptWidget() else abandon()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val la = getSystemService(LauncherApps::class.java)
        val req = runCatching { la.getPinItemRequest(intent) }.getOrNull()
        if (req == null || !req.isValid) {
            finish()
            return
        }
        request = req
        val isShortcut = req.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT
        val shortcut = req.shortcutInfo
        val widget = req.getAppWidgetProviderInfo(this)
        val label = when {
            isShortcut -> (shortcut?.shortLabel ?: shortcut?.longLabel ?: getString(R.string.pin_shortcut)).toString()
            else -> widget?.loadLabel(packageManager) ?: getString(R.string.common_widget)
        }
        val density = resources.displayMetrics.densityDpi

        setContent {
            NbetaTheme(graph.settings.value) {
                val icon by produceState<ImageBitmap?>(null) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            val d = if (isShortcut && shortcut != null) la.getShortcutIconDrawable(shortcut, density)
                            else widget?.loadPreviewImage(this@PinItemActivity, density) ?: widget?.loadIcon(this@PinItemActivity, density)
                            d?.toBitmap(256, (256f * d.intrinsicHeight / d.intrinsicWidth.coerceAtLeast(1)).toInt().coerceIn(1, 512))?.asImageBitmap()
                        }.getOrNull()
                    }
                }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(stringResource(if (isShortcut) R.string.pin_add_to_home else R.string.pin_add_widget)) },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            icon?.let { Image(it, null, Modifier.size(if (isShortcut) 64.dp else 160.dp)) }
                            Spacer(Modifier.height(12.dp))
                            Text(label)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            when {
                                isShortcut && shortcut != null -> addShortcut(shortcut, label)
                                widget != null -> startWidget(widget)
                                else -> finish()
                            }
                        }) { Text(stringResource(R.string.common_add)) }
                    },
                    dismissButton = { TextButton(onClick = { finish() }) { Text(stringResource(R.string.common_cancel)) } },
                )
            }
        }
    }

    private fun addShortcut(shortcut: ShortcutInfo, label: String) {
        if (request.accept()) {
            val serial = getSystemService(UserManager::class.java).getSerialNumberForUser(shortcut.userHandle)
            val item = HomeItem.Shortcut(shortcut.`package`, shortcut.id, serial, label)
            graph.settings.update { s ->
                if (Layout.containerOf(s, item) != null) s else Layout.insert(s, Container.Page(0), Int.MAX_VALUE, item)
            }
        }
        finish()
    }

    private fun startWidget(info: AppWidgetProviderInfo) {
        pendingId = graph.widgets.allocate()
        if (graph.widgets.bindIfAllowed(pendingId, info)) {
            acceptWidget()
        } else {
            bind.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile),
            )
        }
    }

    private fun acceptWidget() {
        val info = request.getAppWidgetProviderInfo(this)
        if (info == null || !request.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId) })) return abandon()
        if (graph.widgets.needsConfigure(info)) {
            try {
                graph.widgets.host.startAppWidgetConfigureActivityForResult(this, pendingId, 0, REQUEST_CONFIGURE, null)
            } catch (_: Exception) {
                abandon()
            }
        } else {
            place()
        }
    }

    @Deprecated("AppWidgetHost reports configuration results only through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONFIGURE) {
            if (resultCode == RESULT_OK) place() else abandon()
        }
    }

    private fun place() {
        graph.widgets.add(pendingId, WidgetPlacement.Home)
        pendingId = -1
        finish()
    }

    private fun abandon() {
        if (pendingId >= 0) graph.widgets.discard(pendingId)
        pendingId = -1
        finish()
    }

    companion object {
        private const val REQUEST_CONFIGURE = 4201
    }
}
