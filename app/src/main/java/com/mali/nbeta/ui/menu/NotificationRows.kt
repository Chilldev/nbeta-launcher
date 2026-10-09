package com.mali.nbeta.ui.menu

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.mali.nbeta.R
import com.mali.nbeta.system.NotifItem
import com.mali.nbeta.system.NotificationDotsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A notification with open / reply / mark-as-read / dismiss. Used in app menus and search. */
@Composable
fun NotificationRow(item: NotifItem, fallbackIcon: ImageBitmap?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var replying by remember(item.key) { mutableStateOf(false) }
    val avatar by produceState(fallbackIcon, item.key) {
        item.largeIcon?.let { icon ->
            value = withContext(Dispatchers.IO) { runCatching { icon.loadDrawable(context)?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() } ?: fallbackIcon
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .clickable(enabled = item.canOpen) {
                if (NotificationDotsService.open(context, item)) onDone()
            }
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)) {
                avatar?.let { Image(it, null, Modifier.size(36.dp), contentScale = ContentScale.Crop) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        " · " + DateUtils.getRelativeTimeSpanString(item.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (item.text.isNotBlank()) {
                    Text(item.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (item.dismissable) {
                IconButton(onClick = { NotificationDotsService.dismiss(item) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.notif_dismiss), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (replying) {
            ReplyField(onSend = { text ->
                val ok = NotificationDotsService.reply(context, item, text)
                Toast.makeText(context, if (ok) R.string.notif_reply_sent else R.string.notif_reply_failed, Toast.LENGTH_SHORT).show()
                replying = false
                if (ok) onDone()
            })
        } else if (item.reply != null || item.markRead != null) {
            Row(Modifier.padding(start = 40.dp)) {
                if (item.reply != null) TextButton(onClick = { replying = true }) { Text(stringResource(R.string.notif_reply)) }
                if (item.markRead != null) TextButton(onClick = { NotificationDotsService.markRead(context, item) }) { Text(stringResource(R.string.notif_mark_read)) }
            }
        }
    }
}

@Composable
private fun ReplyField(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier
            .padding(start = 44.dp, top = 6.dp, end = 4.dp, bottom = 4.dp)
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(stringResource(R.string.notif_reply_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend(text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        IconButton(onClick = { if (text.isNotBlank()) onSend(text.trim()) }) {
            Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.notif_send), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
