package com.mali.nbeta.ui.wallpaper

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.mali.nbeta.R
import com.mali.nbeta.ui.LauncherController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class SourceItem(val source: WallpaperSource, val icon: ImageBitmap?)

/** The phone's wallpaper screen, each gallery app, live wallpapers and wallpaper apps, in one list. */
@Composable
fun WallpaperSheet(c: LauncherController) {
    val context = LocalContext.current
    val rows by produceState<List<SourceItem>?>(null) {
        value = withContext(Dispatchers.IO) {
            Wallpapers.sources(context).map { s -> SourceItem(s, s.icon?.let { runCatching { it.toBitmap(96, 96).asImageBitmap() }.getOrNull() }) }
        }
    }
    ModalBottomSheet(onDismissRequest = { c.wallpaperSheet = false }) {
        Text(stringResource(R.string.common_wallpaper), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            val list = rows.orEmpty()
            var lastKind: WallpaperSource.Kind? = null
            for (r in list) {
                val kind = r.source.kind
                val header = when (kind) {
                    WallpaperSource.Kind.Photos -> R.string.wallpaper_from_photo
                    WallpaperSource.Kind.Live -> R.string.wallpaper_live
                    WallpaperSource.Kind.App -> R.string.wallpaper_apps
                    else -> null
                }
                if (header != null && kind != lastKind) {
                    Text(
                        stringResource(header),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                lastKind = kind
                val (title, subtitle) = when (kind) {
                    WallpaperSource.Kind.System -> stringResource(R.string.wallpaper_system) to null
                    WallpaperSource.Kind.OtherPhotos -> stringResource(R.string.wallpaper_other_apps) to stringResource(R.string.wallpaper_other_apps_summary)
                    else -> r.source.label to null
                }
                SourceRow(r.icon, title, subtitle) {
                    c.wallpaperSheet = false
                    c.start(r.source.intent)
                }
            }
        }
    }
}

@Composable
private fun SourceRow(icon: ImageBitmap?, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(icon, null, Modifier.size(36.dp))
        } else {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MoreVert, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
