package com.mali.nbeta.data.media

import com.mali.nbeta.system.DiagLog
import android.app.WallpaperColors
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import com.mali.nbeta.system.NotificationDotsService
import com.mali.nbeta.system.sendFromLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/** A player's own button (like, shuffle, repeat, ±15 s…), as published in its playback state. */
@Immutable
class MediaAction(val id: String, val name: String, val icon: Icon?)

@Immutable
data class NowPlaying(
    val packageName: String,
    val sessionId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val art: ImageBitmap?,
    /** ARGB from the artwork, for tinting; null without art. */
    val artColor: Int?,
    val playing: Boolean,
    /** Position at [positionAt] (elapsedRealtime), advancing at [speed] while playing. */
    val position: Long,
    val positionAt: Long,
    val speed: Float,
    /** 0 when the player doesn't say (live streams, some radio apps). */
    val duration: Long,
    val canSeek: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrevious: Boolean,
    val actions: List<MediaAction>,
    /** Other sessions that could be shown instead, by package. */
    val others: List<String>,
) {
    fun positionNow(now: Long = SystemClock.elapsedRealtime()): Long {
        val p = if (playing && positionAt > 0) position + ((now - positionAt) * speed).toLong() else position
        return if (duration > 0) p.coerceIn(0, duration) else p.coerceAtLeast(0)
    }
}

/**
 * What's playing, from active media sessions. Reading sessions requires notification access (granted to
 * [NotificationDotsService]); without it this stays empty and costs nothing.
 */
class MediaRepository(private val context: Context) {
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, NotificationDotsService::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val colorWorker by lazy { Executors.newSingleThreadExecutor() }
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private var started = false
    private var controllers: List<MediaController> = emptyList()
    private var active: MediaController? = null
    /** Session the user picked with the switcher; wins while it still exists. */
    private var pinned: String? = null
    private var artKey: Any? = null
    private var art: ImageBitmap? = null
    private var artColor: Int? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> track(list.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = pick()
        override fun onMetadataChanged(metadata: MediaMetadata?) = pick()
        override fun onSessionDestroyed() = pick()
    }

    /** Idempotent; call on resume so it starts as soon as the user grants notification access. */
    fun start() {
        if (started || !NotificationDotsService.isEnabled(context)) return
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
            started = true
            track(msm.getActiveSessions(listener))
        } catch (e: SecurityException) {
            DiagLog.w(TAG, "No notification access yet", e)
        }
    }

    private fun track(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = list
        list.forEach { it.registerCallback(callback, main) }
        pick()
    }

    private val MediaController.id get() = sessionToken.hashCode().toString()
    private val MediaController.hasMedia get() = metadata?.let { titleOf(it) } != null

    private fun titleOf(m: MediaMetadata) =
        (m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))?.takeIf { it.isNotBlank() }

    private fun pick() {
        // The user's pick, then whatever is playing, then the most recent session that still has something loaded
        // (a paused player stays on screen so it can be resumed).
        val usable = controllers.filter { it.hasMedia }
        val c = usable.firstOrNull { it.id == pinned }
            ?: usable.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: usable.firstOrNull()
        active = c
        val meta = c?.metadata
        val title = meta?.let(::titleOf)
        if (c == null || meta == null || title == null) {
            _nowPlaying.value = null
            return
        }
        val bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp !== artKey) {
            artKey = bmp
            val small = bmp?.let(::small)
            art = small?.asImageBitmap()
            artColor = null
            if (small != null) {
                // Off the main thread; the card picks the colour up when it lands.
                colorWorker.execute {
                    val color = runCatching { WallpaperColors.fromBitmap(small).primaryColor.toArgb() }.getOrNull()
                    main.post { if (artKey === bmp) { artColor = color; pick() } }
                }
            }
        }
        val state = c.playbackState
        val actions = state?.actions ?: 0L
        _nowPlaying.value = NowPlaying(
            packageName = c.packageName,
            sessionId = c.id,
            title = title,
            artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() },
            genre = meta.getString(MediaMetadata.METADATA_KEY_GENRE)?.takeIf { it.isNotBlank() },
            art = art,
            artColor = artColor,
            playing = state?.state == PlaybackState.STATE_PLAYING,
            position = state?.position ?: 0L,
            positionAt = state?.lastPositionUpdateTime ?: 0L,
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
            // Players that don't declare their actions usually still handle skips.
            canSkipNext = state == null || actions == 0L || actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canSkipPrevious = state == null || actions == 0L || actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            actions = state?.customActions.orEmpty().take(3).map { a ->
                MediaAction(a.action, a.name.toString(), if (a.icon != 0) Icon.createWithResource(c.packageName, a.icon) else null)
            },
            others = usable.filter { it !== c }.map { it.packageName }.distinct(),
        )
    }

    private fun small(b: Bitmap): Bitmap {
        val max = 256
        if (b.width <= max && b.height <= max) return b
        val f = max.toFloat() / maxOf(b.width, b.height)
        return b.scale((b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1))
    }

    fun playPause() {
        val c = active ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else if (c.playbackState == null) {
            // A session that hasn't reported a state yet (just restored) may only listen for media buttons.
            c.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            c.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        } else c.transportControls.play()
    }

    fun next() = active?.transportControls?.skipToNext()
    fun previous() = active?.transportControls?.skipToPrevious()
    fun seekTo(ms: Long) = active?.transportControls?.seekTo(ms.coerceAtLeast(0))
    fun custom(action: MediaAction) = active?.transportControls?.sendCustomAction(action.id, null)

    /** Shows the next session (e.g. YouTube instead of Spotify) and keeps it until it ends. */
    fun switchPlayer() {
        val usable = controllers.filter { it.hasMedia }
        if (usable.size < 2) return
        val i = usable.indexOfFirst { it === active }
        pinned = usable[(i + 1) % usable.size].id
        pick()
    }

    /** Opens the player: its own "now playing" screen if it offers one, else the app's main screen. */
    fun open() {
        val c = active ?: return
        if (c.sessionActivity?.sendFromLauncher(context) == true) return
        try {
            val la = context.getSystemService(LauncherApps::class.java)
            val activity = la.getActivityList(c.packageName, Process.myUserHandle()).firstOrNull()
            if (activity != null) {
                la.startMainActivity(activity.componentName, activity.user, null, null)
            } else {
                context.packageManager.getLaunchIntentForPackage(c.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
        } catch (e: Exception) {
            DiagLog.w(TAG, "Can't open player", e)
        }
    }

    private companion object {
        const val TAG = "Media"
    }
}
