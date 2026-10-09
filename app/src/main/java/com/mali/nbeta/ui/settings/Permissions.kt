package com.mali.nbeta.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mali.nbeta.R
import com.mali.nbeta.data.apps.UsageHistory
import com.mali.nbeta.system.GestureAccessibilityService
import com.mali.nbeta.system.NotificationDotsService

/** Everything Nbeta can be allowed to do, in one place. Shared by the settings page and the setup prompts. */
object Permissions {
    class Item(@StringRes val title: Int, @StringRes val reason: Int, val granted: Boolean, val runtime: String?, val open: (Context) -> Unit)

    fun isDefaultHome(context: Context): Boolean {
        val home = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return home?.activityInfo?.packageName == context.packageName
    }

    private fun has(context: Context, p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun accessibilityOn(context: Context): Boolean {
        if (GestureAccessibilityService.instance != null) return true
        val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, GestureAccessibilityService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun start(context: Context, intent: Intent, fallback: Intent? = null) {
        val i = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure { fallback?.let { f -> runCatching { context.startActivity(f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
    }

    fun items(context: Context): List<Item> = buildList {
        val pkg = Uri.parse("package:${context.packageName}")
        add(Item(R.string.perm_home, R.string.perm_home_why, isDefaultHome(context), null) { c -> start(c, Intent(Settings.ACTION_HOME_SETTINGS)) })
        add(
            Item(R.string.perm_notification_access, R.string.perm_notification_access_why, NotificationDotsService.isEnabled(context), null) { c ->
                val detail = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(c, NotificationDotsService::class.java).flattenToString())
                else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                start(c, detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
        )
        if (Build.VERSION.SDK_INT >= 33) add(Item(R.string.perm_notifications, R.string.perm_notifications_why, has(context, Manifest.permission.POST_NOTIFICATIONS), Manifest.permission.POST_NOTIFICATIONS) { })
        add(Item(R.string.perm_contacts, R.string.perm_contacts_why, has(context, Manifest.permission.READ_CONTACTS), Manifest.permission.READ_CONTACTS) { })
        add(Item(R.string.perm_calendar, R.string.perm_calendar_why, has(context, Manifest.permission.READ_CALENDAR), Manifest.permission.READ_CALENDAR) { })
        add(Item(R.string.perm_location, R.string.perm_location_why, has(context, Manifest.permission.ACCESS_COARSE_LOCATION), Manifest.permission.ACCESS_COARSE_LOCATION) { })
        add(
            Item(R.string.perm_usage, R.string.perm_usage_why, UsageHistory(context).granted(), null) { c ->
                start(c, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkg), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            },
        )
        add(
            Item(R.string.perm_install, R.string.perm_install_why, context.packageManager.canRequestPackageInstalls(), null) { c ->
                start(c, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg))
            },
        )
        add(
            Item(R.string.perm_accessibility, R.string.perm_accessibility_why, accessibilityOn(context), null) { c ->
                val detail = if (Build.VERSION.SDK_INT >= 33) Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(c, GestureAccessibilityService::class.java).flattenToString())
                else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                start(c, detail, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
        )
    }

    fun missing(context: Context) = items(context).count { !it.granted }
}

@Composable
fun PermissionsContent() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // Statuses change in system screens; re-read whenever we come back.
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val items = remember(tick) { Permissions.items(context) }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val pendingRuntime = items.filter { !it.granted && it.runtime != null }.mapNotNull { it.runtime }

    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.perm_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (pendingRuntime.isNotEmpty()) {
            Button(onClick = { runtime.launch(pendingRuntime.toTypedArray()) }, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.perm_allow_all))
            }
        }
        items.forEach { item ->
            ClickPref(
                stringResource(item.title),
                stringResource(item.reason),
                trailing = {
                    if (item.granted) Icon(Icons.Default.CheckCircle, stringResource(R.string.perm_granted), tint = MaterialTheme.colorScheme.primary)
                    else FilledTonalButton(onClick = {
                        when {
                            item.runtime != null -> runtime.launch(arrayOf(item.runtime))
                            item.title == R.string.perm_home -> {
                                val rm = context.getSystemService(RoleManager::class.java)
                                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) role.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                                else item.open(context)
                            }
                            else -> item.open(context)
                        }
                    }) { Text(stringResource(R.string.perm_allow)) }
                },
            ) { if (!item.granted) item.open(context) }
        }
    }
}
