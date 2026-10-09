package com.mali.nbeta.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mali.nbeta.data.apps.AppEntry
import com.mali.nbeta.data.apps.AppShortcut

@Composable
fun rememberAppIcon(app: AppEntry): ImageBitmap? {
    val icons = LocalGraph.current.icons
    val style = LocalIconStyle.current
    // Synchronous memory hit on the first frame (icons are prewarmed), async only on a miss.
    var bmp by remember(app.key, app.version, style) { mutableStateOf(icons.peek(app, style)) }
    if (bmp == null) {
        LaunchedEffect(app.key, app.version, style) { bmp = icons.load(app, style) }
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
 * One app tile: icon, optional label and notification dot, press-to-shrink feedback. Bounds are captured cheaply for
 * the launch animation.
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
) {
    val bitmap = rememberAppIcon(app)
    val hasDot = app.packageKey in LocalDots.current
    Tile(bitmap, app.label, iconSize, showLabel, labelStyle, hasDot, onClick, onLongClick, modifier)
}

@Composable
fun Tile(
    bitmap: ImageBitmap?,
    label: String,
    iconSize: Dp,
    showLabel: Boolean,
    labelStyle: TextStyle,
    hasDot: Boolean,
    onClick: (BoundsHolder) -> Unit,
    onLongClick: (BoundsHolder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bounds = remember { BoundsHolder() }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.6f, stiffness = 900f), label = "press")
    val haptics = LocalHapticFeedback.current
    Column(
        modifier
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onClick(bounds) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick(bounds)
                },
                onLongClickLabel = "Options",
            )
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
            IconImage(bitmap, iconSize, if (showLabel) null else label)
            if (hasDot) {
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
