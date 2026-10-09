package com.mali.nbeta.ui.drawer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mali.nbeta.R
import com.mali.nbeta.data.LauncherSettings
import com.mali.nbeta.data.apps.AppShortcut
import com.mali.nbeta.data.search.ContactHit
import com.mali.nbeta.data.search.SearchResults
import com.mali.nbeta.data.search.WebHit
import com.mali.nbeta.ui.LauncherController
import com.mali.nbeta.ui.MenuRequest
import com.mali.nbeta.ui.MenuTarget
import com.mali.nbeta.ui.Origin
import com.mali.nbeta.ui.common.AppTile
import com.mali.nbeta.ui.common.BoundsHolder
import com.mali.nbeta.ui.common.IconImage
import com.mali.nbeta.ui.common.rememberShortcutIcon
import com.mali.nbeta.ui.common.surfaceLabelStyle

@Composable
fun SearchResultsList(c: LauncherController, r: SearchResults, settings: LauncherSettings) {
    val cols = settings.drawerColumns
    val labelStyle = surfaceLabelStyle()
    val iconSize = (settings.iconSizeDp - 4).coerceAtLeast(40).dp
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        r.calc?.let { value ->
            item(key = "calc") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable { copyToClipboard(c, value) }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.query.trim(), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f), maxLines = 1)
                        Text("= $value", fontSize = 28.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(stringResource(R.string.common_copy), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (r.apps.isNotEmpty()) {
            val rows = r.apps.chunked(cols)
            items(rows.size, key = { "apps$it" }) { i ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (i == 0) Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
                            else Modifier,
                        ),
                ) {
                    rows[i].forEach { app ->
                        AppTile(
                            app, iconSize, true, labelStyle,
                            onClick = { b -> c.launch(app, b) },
                            onLongClick = { b -> b.rect()?.let { c.menu = MenuRequest(MenuTarget.App(app, Origin.Search), it) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(cols - rows[i].size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (r.shortcuts.isNotEmpty()) {
            item(key = "h-shortcuts") { Header(stringResource(R.string.search_shortcuts)) }
            items(r.shortcuts, key = { "s" + it.key }) { s -> ShortcutRow(c, s) }
        }
        if (r.contacts.isNotEmpty()) {
            item(key = "h-contacts") { Header(stringResource(R.string.common_contacts)) }
            items(r.contacts, key = { "c${it.id}" }) { ContactRow(c, it) }
        }
        if (r.settings.isNotEmpty()) {
            item(key = "h-settings") { Header(stringResource(R.string.common_settings)) }
            items(r.settings, key = { "set" + it.action }) { s ->
                ResultRow(Icons.Default.Settings, stringResource(s.label), null) { c.start(Intent(s.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        item(key = "h-web") { Header(stringResource(R.string.search_web)) }
        items(r.web, key = { it.key }) { w ->
            val (icon, title) = when (w) {
                is WebHit.Url -> Icons.Default.Share to w.url
                is WebHit.Search -> Icons.Default.Search to stringResource(R.string.search_web_hit, w.engine, w.query)
                is WebHit.Store -> Icons.Default.PlayArrow to stringResource(R.string.search_store_hit, w.query)
            }
            ResultRow(icon, title, null) { c.start(c.graph.search.webIntent(w)) }
        }
        item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

private val WebHit.key
    get() = when (this) {
        is WebHit.Url -> "wu$url"
        is WebHit.Search -> "ws$url"
        is WebHit.Store -> "wp$query"
    }

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun ResultRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ShortcutRow(c: LauncherController, s: AppShortcut) {
    val bmp = rememberShortcutIcon(s)
    val bounds = remember { BoundsHolder() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { c.launchShortcut(s, bounds) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconImage(bmp, 40.dp, null, Modifier.onPlaced { bounds.coords = it })
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(s.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(s.appLabel, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ContactRow(c: LauncherController, h: ContactHit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                val uri = ContactsContract.Contacts.getLookupUri(h.id, h.lookupKey)
                c.start(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
            Text(h.name.take(1).uppercase(), color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.Bold)
            if (h.photo != null) AsyncImage(h.photo, null, Modifier.size(40.dp).clip(CircleShape))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(h.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            h.phone?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        h.phone?.let { phone ->
            IconButton(onClick = { c.start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                Icon(Icons.Default.Email, stringResource(R.string.search_message))
            }
            IconButton(onClick = { c.start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                Icon(Icons.Default.Call, stringResource(R.string.search_call))
            }
        }
    }
}

fun copyToClipboard(c: LauncherController, text: String) {
    val cm = c.activity.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("Result", text))
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(c.activity, c.activity.getString(R.string.search_copied, text), Toast.LENGTH_SHORT).show()
}
