package com.mali.nbeta.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mali.nbeta.R
import com.mali.nbeta.ui.common.LocalGraph
import com.mali.nbeta.ui.common.MediaIcons

/** Now playing, with transport controls. Renders nothing when no media session is active. */
@Composable
fun MediaCard(modifier: Modifier = Modifier) {
    val media = LocalGraph.current.media
    val np by media.nowPlaying.collectAsStateWithLifecycle()
    val now = np ?: return
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
            .clickable { media.open() }
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer)) {
            now.art?.let { Image(it, null, Modifier.size(44.dp), contentScale = ContentScale.Crop) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(now.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            now.artist?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val tint = MaterialTheme.colorScheme.onSurface
        IconButton(onClick = { media.previous() }) { Icon(MediaIcons.Previous, stringResource(R.string.media_previous), tint = tint) }
        IconButton(onClick = { media.playPause() }) {
            Icon(if (now.playing) MediaIcons.Pause else MediaIcons.Play, stringResource(if (now.playing) R.string.media_pause else R.string.media_play), tint = tint)
        }
        IconButton(onClick = { media.next() }) { Icon(MediaIcons.Next, stringResource(R.string.media_next), tint = tint) }
    }
}
