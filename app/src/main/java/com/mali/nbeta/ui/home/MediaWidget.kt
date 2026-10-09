package com.mali.nbeta.ui.home

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mali.nbeta.R
import com.mali.nbeta.data.MediaStyle
import com.mali.nbeta.data.apps.ProfileKind
import com.mali.nbeta.data.media.MediaAction
import com.mali.nbeta.data.media.NowPlaying
import com.mali.nbeta.ui.common.LocalGraph
import com.mali.nbeta.ui.common.MediaIcons
import com.mali.nbeta.ui.common.rememberAppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** What the media layouts can do; the picker's previews pass no-ops. */
@Stable
class MediaControls(
    val open: () -> Unit = {},
    val playPause: () -> Unit = {},
    val next: () -> Unit = {},
    val previous: () -> Unit = {},
    val seekTo: (Long) -> Unit = {},
    val custom: (MediaAction) -> Unit = {},
    val switchPlayer: () -> Unit = {},
)

/** The feed's fixed now-playing card. */
@Composable
fun MediaCard(modifier: Modifier = Modifier) = MediaWidget(MediaStyle.Compact, modifier)

/**
 * Now playing in one of four sizes. Shows which app is playing and opens it on tap. With nothing playing it takes no
 * space, except while editing the home screen, where it shows a placeholder so it can still be found and moved.
 */
@Composable
fun MediaWidget(style: MediaStyle, modifier: Modifier = Modifier, editing: Boolean = false) {
    val media = LocalGraph.current.media
    val np by media.nowPlaying.collectAsStateWithLifecycle()
    val controls = remember(media) {
        MediaControls(media::open, media::playPause, { media.next() }, { media.previous() }, { media.seekTo(it) }, { media.custom(it) }, media::switchPlayer)
    }
    val now = np
    if (now == null) {
        if (editing) MediaPlaceholder(modifier)
        return
    }
    val (label, icon) = rememberPlayerApp(now.packageName)
    MediaLayout(style, now, label, icon, controls, modifier)
}

/** A sample of each style for the widget picker. */
@Composable
fun MediaStylePreview(style: MediaStyle, modifier: Modifier = Modifier) {
    val sample = remember {
        NowPlaying(
            packageName = "", sessionId = "", title = "Midnight City", artist = "M83", album = "Hurry Up, We're Dreaming",
            genre = "Electronic", art = null, artColor = 0xFF5B4BA8.toInt(), playing = true, position = 83_000, positionAt = 0,
            speed = 1f, duration = 243_000, canSeek = true, canSkipNext = true, canSkipPrevious = true, actions = emptyList(),
            others = emptyList(),
        )
    }
    MediaLayout(style, sample, stringResource(R.string.media_sample_app), null, remember { MediaControls() }, modifier)
}

@Composable
private fun MediaLayout(style: MediaStyle, np: NowPlaying, appLabel: String?, appIcon: ImageBitmap?, controls: MediaControls, modifier: Modifier) {
    when (style) {
        MediaStyle.Pill -> PillLayout(np, appIcon, controls, modifier)
        MediaStyle.Compact -> CompactLayout(np, appLabel, appIcon, controls, modifier)
        MediaStyle.Large -> LargeLayout(np, appLabel, appIcon, controls, modifier)
        MediaStyle.Artwork -> ArtworkLayout(np, appLabel, appIcon, controls, modifier)
    }
}

// --- Styles ---

@Composable
private fun PillLayout(np: NowPlaying, appIcon: ImageBitmap?, controls: MediaControls, modifier: Modifier) {
    val container = tinted(np)
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(container)
            .clickable(onClick = controls.open)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppBadge(appIcon, 32.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            listOfNotNull(np.title, np.artist).joinToString(" · "),
            Modifier.weight(1f).scrolling(np.playing),
            maxLines = 1,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Progress runs around the play button.
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (np.duration > 0) {
                val pos by rememberPosition(np)
                CircularProgressIndicator(
                    progress = { (pos.toFloat() / np.duration).coerceIn(0f, 1f) },
                    modifier = Modifier.size(40.dp),
                    color = accent(np),
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    strokeWidth = 3.dp,
                    gapSize = 0.dp,
                )
            }
            PlayPauseButton(np, controls, MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun CompactLayout(np: NowPlaying, appLabel: String?, appIcon: ImageBitmap?, controls: MediaControls, modifier: Modifier) {
    val tint = MaterialTheme.colorScheme.onSurface
    val pos by rememberPosition(np)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(tinted(np))
            .clickable(onClick = controls.open),
    ) {
        val seekable = np.duration > 0
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = if (seekable) 0.dp else 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Artwork(np, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                // Which app is playing, on the corner of the artwork.
                AppBadge(appIcon, 20.dp, Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(np.title, Modifier.scrolling(np.playing), maxLines = 1, fontWeight = FontWeight.Medium, color = tint)
                val sub = listOfNotNull(np.artist, appLabel).joinToString(" · ")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sub, Modifier.weight(1f, fill = false).scrolling(np.playing), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (np.duration > 0) {
                        Text(
                            "  " + timeLeft(np.duration - pos),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (np.canSkipPrevious) IconButton(onClick = controls.previous) { Icon(MediaIcons.Previous, stringResource(R.string.media_previous), tint = tint) }
            PlayPauseButton(np, controls, tint)
            if (np.canSkipNext) IconButton(onClick = controls.next) { Icon(MediaIcons.Next, stringResource(R.string.media_next), tint = tint) }
        }
        if (seekable) {
            // Inset from the rounded corners so the whole bar shows; time left is already next to the artist.
            Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp)) {
                SeekBar(np, controls, accent(np), accent(np), MaterialTheme.colorScheme.onSurfaceVariant, compact = true)
            }
        }
    }
}

@Composable
private fun LargeLayout(np: NowPlaying, appLabel: String?, appIcon: ImageBitmap?, controls: MediaControls, modifier: Modifier) {
    val tint = MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(tinted(np))
            .clickable(onClick = controls.open)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
    ) {
        AppHeader(np, appLabel, appIcon, controls, MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(np, Modifier.size(88.dp).clip(RoundedCornerShape(14.dp)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(np.title, Modifier.scrolling(np.playing), maxLines = 1, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = tint)
                np.artist?.let { Text(it, Modifier.scrolling(np.playing), maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                details(np)?.let { Text(it, Modifier.scrolling(np.playing), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Spacer(Modifier.height(6.dp))
        SeekBar(np, controls, accent(np), tint, MaterialTheme.colorScheme.onSurfaceVariant)
        TransportRow(np, controls, tint, accent(np))
    }
}

@Composable
private fun ArtworkLayout(np: NowPlaying, appLabel: String?, appIcon: ImageBitmap?, controls: MediaControls, modifier: Modifier) {
    val base = np.artColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier
            .fillMaxWidth()
            .height(232.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(base)
            .clickable(onClick = controls.open),
    ) {
        np.art?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        // Dark enough behind the text for white to pass contrast on any cover.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.3f to Color.Black.copy(alpha = 0.2f), 0.55f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.82f))))
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
                AppHeader(np, appLabel, appIcon, controls, Color.White.copy(alpha = 0.9f))
                Spacer(Modifier.weight(1f))
                Text(np.title, Modifier.scrolling(np.playing), maxLines = 1, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
                val sub = listOfNotNull(np.artist, details(np)).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, Modifier.scrolling(np.playing), maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.height(4.dp))
                SeekBar(np, controls, Color.White, Color.White, Color.White.copy(alpha = 0.85f))
                TransportRow(np, controls, Color.White, Color.White)
            }
        }
    }
}

// --- Parts ---

/** "Spotify" with its icon, and a switcher when another player also has something loaded. */
@Composable
private fun AppHeader(np: NowPlaying, appLabel: String?, appIcon: ImageBitmap?, controls: MediaControls, color: Color) {
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        AppBadge(appIcon, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(appLabel.orEmpty(), Modifier.weight(1f).scrolling(np.playing), maxLines = 1, style = MaterialTheme.typography.labelLarge, color = color)
        if (np.others.isNotEmpty()) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = controls.switchPlayer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MediaIcons.Swap, null, Modifier.size(16.dp), tint = color)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.media_switch_player), style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
    }
}

/** Previous / play / next, flanked by up to two of the player's own buttons (like, shuffle, ±15 s…). */
@Composable
private fun TransportRow(np: NowPlaying, controls: MediaControls, tint: Color, accent: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        CustomActionButton(np.actions.getOrNull(0), controls, tint)
        IconButton(onClick = controls.previous, enabled = np.canSkipPrevious) {
            Icon(MediaIcons.Previous, stringResource(R.string.media_previous), tint = tint.copy(alpha = if (np.canSkipPrevious) 1f else 0.38f))
        }
        val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(accent).clickable(onClick = controls.playPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (np.playing) MediaIcons.Pause else MediaIcons.Play,
                stringResource(if (np.playing) R.string.media_pause else R.string.media_play),
                Modifier.size(28.dp),
                tint = onAccent,
            )
        }
        IconButton(onClick = controls.next, enabled = np.canSkipNext) {
            Icon(MediaIcons.Next, stringResource(R.string.media_next), tint = tint.copy(alpha = if (np.canSkipNext) 1f else 0.38f))
        }
        CustomActionButton(np.actions.getOrNull(1), controls, tint)
    }
}

@Composable
private fun CustomActionButton(action: MediaAction?, controls: MediaControls, tint: Color) {
    if (action == null) {
        Spacer(Modifier.size(48.dp))
        return
    }
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, action.id, action.icon) {
        value = withContext(Dispatchers.IO) {
            runCatching { action.icon?.loadDrawable(context)?.toBitmap(72, 72)?.asImageBitmap() }.getOrNull()
        }
    }
    IconButton(onClick = { controls.custom(action) }) {
        val bmp = icon
        if (bmp != null) Image(bmp, action.name, Modifier.size(22.dp), colorFilter = ColorFilter.tint(tint))
        else Text(action.name.take(2), color = tint, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PlayPauseButton(np: NowPlaying, controls: MediaControls, tint: Color) {
    IconButton(onClick = controls.playPause) {
        Icon(if (np.playing) MediaIcons.Pause else MediaIcons.Play, stringResource(if (np.playing) R.string.media_pause else R.string.media_play), tint = tint)
    }
}

/**
 * Elapsed time, the bar, and time left (tap it to show the total length instead). Tap or drag the bar to seek;
 * while dragging, the time under the finger is shown above it. Players that can't seek get a plain bar.
 */
@Composable
private fun SeekBar(np: NowPlaying, controls: MediaControls, active: Color, thumb: Color, text: Color, compact: Boolean = false) {
    if (np.duration <= 0) {
        if (compact) return
        // Live streams and players that don't report a length: just how long it has been playing.
        val pos by rememberPosition(np)
        Text(formatTime(pos), style = MaterialTheme.typography.labelSmall, color = text, modifier = Modifier.padding(vertical = 6.dp))
        return
    }
    val pos by rememberPosition(np)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var showRemaining by rememberSaveable { mutableStateOf(true) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val fraction = dragFraction ?: (pos.toFloat() / np.duration).coerceIn(0f, 1f)
    val shown = (fraction * np.duration).toLong()
    val track = active.copy(alpha = 0.25f)
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            fun fractionAt(x: Float) = (if (rtl) 1f - x / widthPx else x / widthPx).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (compact) 20.dp else 24.dp)
                    .then(
                        if (np.canSeek) Modifier
                            .pointerInput(np.duration, widthPx, rtl) {
                                detectTapGestures { controls.seekTo((fractionAt(it.x) * np.duration).toLong()) }
                            }
                            .pointerInput(np.duration, widthPx, rtl) {
                                val end = {
                                    dragFraction?.let { controls.seekTo((it * np.duration).toLong()) }
                                    dragFraction = null
                                }
                                detectHorizontalDragGestures(
                                    onDragStart = { dragFraction = fractionAt(it.x) },
                                    onDragEnd = end,
                                    onDragCancel = end,
                                ) { change, _ ->
                                    change.consume()
                                    dragFraction = fractionAt(change.position.x)
                                }
                            }
                        else Modifier,
                    )
                    .drawBehind {
                        val y = size.height / 2
                        val stroke = (if (compact) 3.dp else 4.dp).toPx()
                        val x = size.width * fraction
                        val start = if (rtl) size.width else 0f
                        val end = if (rtl) size.width - x else x
                        drawLine(track, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
                        drawLine(active, Offset(start, y), Offset(end, y), stroke, StrokeCap.Round)
                        if (np.canSeek && (!compact || dragFraction != null)) {
                            drawCircle(thumb, radius = (if (dragFraction != null) 8.dp else 6.dp).toPx(), center = Offset(end, y))
                        }
                    },
            )
            // The time under the finger, floating above the thumb while dragging.
            dragFraction?.let { f ->
                val bubbleW = 56.dp
                val density = LocalDensity.current
                val x = with(density) { (if (rtl) (1f - f) else f) * widthPx - (bubbleW / 2).toPx() }
                    .coerceIn(0f, with(density) { widthPx - bubbleW.toPx() })
                Box(
                    Modifier
                        .offset { IntOffset(x.roundToInt(), (-30).dp.roundToPx()) }
                        .width(bubbleW)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .padding(vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
        }
        if (!compact) Row(Modifier.fillMaxWidth()) {
            Text(formatTime(shown), style = MaterialTheme.typography.labelSmall, color = text)
            Spacer(Modifier.weight(1f))
            Text(
                if (showRemaining) timeLeft(np.duration - shown) else formatTime(np.duration),
                style = MaterialTheme.typography.labelSmall,
                color = text,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { showRemaining = !showRemaining }.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun Artwork(np: NowPlaying, modifier: Modifier) {
    Box(modifier.background(np.artColor?.let { Color(it) } ?: MaterialTheme.colorScheme.secondaryContainer)) {
        np.art?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun AppBadge(icon: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    if (icon != null) {
        Image(icon, null, modifier.size(size))
    } else {
        // Before the icon loads, or for players without one: a neutral note-ish badge rather than a gap.
        Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(MediaIcons.Play, null, Modifier.size(size * 0.6f), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun MediaPlaceholder(modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.media_idle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Card colour: the usual surface, lightly tinted with the cover's colour. */
@Composable
private fun tinted(np: NowPlaying): Color {
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val tint = np.artColor?.let { lerp(surface, Color(it), 0.18f) } ?: surface
    return tint.copy(alpha = 0.95f)
}

/** The cover's colour for the play button and progress, unless it would vanish against the card. */
@Composable
private fun accent(np: NowPlaying): Color {
    val c = np.artColor?.let { Color(it) } ?: return MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    return if (kotlin.math.abs(c.luminance() - surface.luminance()) < 0.25f) MaterialTheme.colorScheme.primary else c
}

/**
 * Text too long for its line scrolls so all of it can be read: continuously while playing, once when paused. Text that
 * fits stays still (the marquee only moves when the content overflows).
 */
private fun Modifier.scrolling(playing: Boolean) = basicMarquee(
    iterations = if (playing) Int.MAX_VALUE else 1,
    initialDelayMillis = 1500,
    repeatDelayMillis = 2500,
    spacing = MarqueeSpacing(40.dp),
    velocity = 36.dp,
)

private fun details(np: NowPlaying): String? = listOfNotNull(np.album, np.genre).joinToString(" · ").ifEmpty { null }

/** Playback position, ticking while playing. */
@Composable
private fun rememberPosition(np: NowPlaying) = produceState(np.positionNow(), np) {
    while (np.playing) {
        value = np.positionNow(SystemClock.elapsedRealtime())
        delay(500)
    }
    value = np.positionNow()
}

/** The player's label and launcher icon (icon pack and themed icons included), from the main profile when possible. */
@Composable
private fun rememberPlayerApp(pkg: String): Pair<String?, ImageBitmap?> {
    val graph = LocalGraph.current
    val apps by graph.apps.apps.collectAsStateWithLifecycle()
    val entry = remember(apps, pkg) {
        apps.firstOrNull { it.packageName == pkg && it.profile == ProfileKind.Main } ?: apps.firstOrNull { it.packageName == pkg }
    }
    if (entry != null) return entry.label to rememberAppIcon(entry)
    val context = LocalContext.current
    val fallback by produceState<Pair<String?, ImageBitmap?>>(null to null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai).toString() to pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap()
            }.getOrDefault(null to null)
        }
    }
    return fallback
}

/** "−2:31", kept left-to-right so the minus stays in front in Arabic too. */
internal fun timeLeft(ms: Long) = "\u2066−" + formatTime(ms) + "\u2069"

internal fun formatTime(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 1000
    val h = t / 3600
    val m = t % 3600 / 60
    val s = t % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}
