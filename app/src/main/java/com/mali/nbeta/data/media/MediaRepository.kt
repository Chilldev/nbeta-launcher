package com.mali.nbeta.data.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import com.mali.nbeta.system.NotificationDotsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String?,
    val art: ImageBitmap?,
    val playing: Boolean,
)

/**
 * What's playing, from active media sessions. Reading sessions requires notification access (granted to
 * [NotificationDotsService]); without it this stays empty and costs nothing.
 */
class MediaRepository(private val context: Context) {
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, NotificationDotsService::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private var started = false
    private var controllers: List<MediaController> = emptyList()
    private var active: MediaController? = null
    private var artKey: Any? = null
    private var art: ImageBitmap? = null

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
            Log.w("Media", "No notification access yet", e)
        }
    }

    private fun track(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = list
        list.forEach { it.registerCallback(callback, main) }
        pick()
    }

    private fun pick() {
        // Prefer whatever is playing; otherwise the most recent session that still has something loaded.
        val c = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }
        active = c
        val meta = c?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (c == null || meta == null || title.isNullOrBlank()) {
            _nowPlaying.value = null
            return
        }
        val bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp !== artKey) {
            artKey = bmp
            art = bmp?.let { small(it).asImageBitmap() }
        }
        _nowPlaying.value = NowPlaying(
            packageName = c.packageName,
            title = title,
            artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            art = art,
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    private fun small(b: Bitmap): Bitmap {
        val max = 192
        if (b.width <= max && b.height <= max) return b
        val f = max.toFloat() / maxOf(b.width, b.height)
        return b.scale((b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1))
    }

    fun playPause() {
        val c = active ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = active?.transportControls?.skipToNext()
    fun previous() = active?.transportControls?.skipToPrevious()

    /** Opens the player: its own session activity if it offers one, else the app. */
    fun open() {
        val c = active ?: return
        try {
            c.sessionActivity?.send() ?: context.packageManager.getLaunchIntentForPackage(c.packageName)
                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
        } catch (e: Exception) {
            Log.w("Media", "Can't open player", e)
        }
    }
}
