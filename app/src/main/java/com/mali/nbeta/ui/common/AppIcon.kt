package com.mali.nbeta.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import com.mali.nbeta.data.Container
import com.mali.nbeta.data.HomeItem
import com.mali.nbeta.ui.dnd.DragItem
import com.mali.nbeta.ui.dnd.LocalDragDrop
import com.mali.nbeta.ui.dnd.tileGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mali.nbeta.data.apps.AppEntry
import com.mali.nbeta.data.apps.AppShortcut
import com.mali.nbeta.R

@Composable
fun rememberAppIcon(app: AppEntry): ImageBitmap? {
    val icons = LocalGraph.current.icons
    val style = LocalIconStyle.current
    val override = LocalIconOverrides.current[app.key]
    // Synchronous memory hit on the first frame (icons are prewarmed), async only on a miss.
    var bmp by remember(app.key, app.version, style, override) { mutableStateOf(icons.peek(app, style)) }
    if (bmp == null) {
        LaunchedEffect(app.key, app.version, style, override) { bmp = icons.load(app, style) }
    }
    return bmp
}

@Composable
fun rememberShortcutIcon(shortcut: AppShortcut): ImageBitmap? {
    val icons = LocalGraph.current.icons
    val style = LocalIconStyle.current
    var bmp by remember(shortcut.key, style) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(shortcut.key, style) { bmp = icons.shortcutIcon(shortcut.info, style) }
    return bmp
}

@Composable
fun IconImage(bitmap: ImageBitmap?, size: Dp, contentDescription: String?, modifier: Modifier = Modifier) {
    if (bitmap != null) {
        Image(bitmap, contentDescription, modifier.size(size))
    } else {
        Box(modifier.size(size).clip(RoundedCornerShape(size / 3)).background(Color.White.copy(alpha = 0.12f)))
    }
}

/**
 * One app tile: icon, optional label and notification dot, press-to-shrink feedback. With [origin] set (or for drawer
 * tiles, null origin) and a [LocalDragDrop] present, long-press-then-drag picks the app up.
 */
@Composable
fun AppTile(
    app: AppEntry,
    iconSize: Dp,
    showLabel: Boolean,
    labelStyle: TextStyle,
    onClick: (BoundsHolder) -> Unit,
    onLongClick: (BoundsHolder) -> Unit,
    modifier: Modifier = Modifier,
    origin: Container? = null,
    draggable: Boolean = true,
    item: HomeItem = HomeItem.App(app.key),
    hidden: Boolean = false,
    highlight: Boolean = false,
) {
    val bitmap = rememberAppIcon(app)
    val dotCount = LocalDots.current[app.packageKey] ?: 0
    Tile(
        bitmap, app.label, iconSize, showLabel, labelStyle, dotCount, onClick, onLongClick, modifier,
        dragItem = if (draggable) ({ DragItem(item, origin, app, bitmap) }) else null,
        hidden = hidden,
        highlight = highlight,
    )
}

@Composable
fun Tile(
    bitmap: ImageBitmap?,
    label: String,
    iconSize: Dp,
    showLabel: Boolean,
    labelStyle: TextStyle,
    /** Notifications for this tile; 0 = no dot. */
    hasDot: Int,
    onClick: (BoundsHolder) -> Unit,
    onLongClick: (BoundsHolder) -> Unit,
    modifier: Modifier = Modifier,
    dragItem: (() -> DragItem?)? = null,
    hidden: Boolean = false,
    highlight: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) {
    val bounds = remember { BoundsHolder() }
    val root = remember { BoundsHolder() }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        when {
            highlight -> 1.18f
            pressed -> 0.88f
            else -> 1f
        },
        spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "press",
    )
    val haptics = LocalHapticFeedback.current
    val dnd = LocalDragDrop.current
    val currentDrag by rememberUpdatedState(dragItem)
    val currentClick by rememberUpdatedState(onClick)
    val currentLong by rememberUpdatedState(onLongClick)
    val optionsLabel = stringResource(R.string.common_options)
    Column(
        modifier
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .onPlaced { root.coords = it }
            .tileGestures(
                key = Unit,
                interaction = interaction,
                dnd = dnd,
                coords = { root.coords },
                onClick = { currentClick(bounds) },
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentLong(bounds)
                },
                dragItem = { currentDrag?.invoke() },
            )
            .semantics(mergeDescendants = true) {
                onClick(label = null) { currentClick(bounds); true }
                onLongClick(label = optionsLabel) { currentLong(bounds); true }
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .onPlaced { bounds.coords = it }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            if (icon != null) Box(Modifier.size(iconSize)) { icon() } else IconImage(bitmap, iconSize, if (showLabel) null else label)
            if (hasDot > 0) {
                if (LocalDotCounts.current) {
                    val text = if (hasDot > 99) "99+" else hasDot.toString()
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp)
                            .defaultMinSize(minWidth = iconSize * 0.34f, minHeight = iconSize * 0.34f)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text,
                            color = MaterialTheme.colorScheme.onTertiary,
                            fontSize = (iconSize.value * 0.2f).sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            style = TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 1.dp, y = (-1).dp)
                            .size(iconSize * 0.22f)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary),
                    )
                }
            }
        }
        if (showLabel) {
            Spacer(Modifier.height(5.dp))
            Text(
                label,
                style = labelStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
fun wallpaperLabelStyle(): TextStyle {
    val w = LocalOnWallpaper.current
    return TextStyle(color = w.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, shadow = w.shadow)
}

@Composable
fun surfaceLabelStyle(): TextStyle =
    TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
