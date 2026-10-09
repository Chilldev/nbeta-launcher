package com.mali.nbeta.baselineprofile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real media session for checking the media widget by hand (skipped unless asked for):
 * ./gradlew :baselineprofile:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.holdMediaSeconds=120 \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.mali.nbeta.baselineprofile.MediaSessionFixture
 * It plays a fake 4:03 track, handles play/pause/seek/skip and two custom actions, and logs each under "MediaFixture".
 */
@RunWith(AndroidJUnit4::class)
class MediaSessionFixture {
    @Test
    fun holdMediaSession() {
        val seconds = InstrumentationRegistry.getArguments().getString("holdMediaSeconds")?.toLongOrNull()
        assumeTrue(seconds != null)
        val context = InstrumentationRegistry.getInstrumentation().context
        val main = Handler(Looper.getMainLooper())
        val session = MediaSession(context, "fixture")
        var playing = true
        var position = 83_000L
        var at = SystemClock.elapsedRealtime()
        var liked = false
        fun publish() {
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position, 1f, at)
                    .addCustomAction(
                        PlaybackState.CustomAction.Builder("like", if (liked) "Unlike" else "Like", if (liked) android.R.drawable.star_on else android.R.drawable.star_off).build(),
                    )
                    .addCustomAction(PlaybackState.CustomAction.Builder("ff15", "Forward 15 seconds", android.R.drawable.ic_media_ff).build())
                    .build(),
            )
        }
        fun now() = if (playing) position + (SystemClock.elapsedRealtime() - at) else position
        main.post {
            session.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { position = now(); at = SystemClock.elapsedRealtime(); playing = true; publish(); Log.i(TAG, "play") }
                override fun onPause() { position = now(); at = SystemClock.elapsedRealtime(); playing = false; publish(); Log.i(TAG, "pause") }
                override fun onSeekTo(pos: Long) { position = pos; at = SystemClock.elapsedRealtime(); publish(); Log.i(TAG, "seek $pos") }
                override fun onSkipToNext() { position = 0; at = SystemClock.elapsedRealtime(); publish(); Log.i(TAG, "next") }
                override fun onSkipToPrevious() { position = 0; at = SystemClock.elapsedRealtime(); publish(); Log.i(TAG, "previous") }
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) {
                        "like" -> liked = !liked
                        "ff15" -> { position = now() + 15_000; at = SystemClock.elapsedRealtime() }
                    }
                    publish()
                    Log.i(TAG, "custom $action")
                }
            }, main)
            session.setSessionActivity(
                PendingIntent.getActivity(context, 0, Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE),
            )
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Midnight City")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "M83")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "Hurry Up, We're Dreaming")
                    .putString(MediaMetadata.METADATA_KEY_GENRE, "Electronic")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, 243_000)
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover())
                    .build(),
            )
            publish()
            session.isActive = true
            Log.i(TAG, "session active")
        }
        SystemClock.sleep(seconds!! * 1000)
        main.post { session.release() }
        SystemClock.sleep(300)
    }

    private fun cover(): Bitmap {
        val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        Canvas(b).drawRect(0f, 0f, 512f, 512f, Paint().apply {
            shader = LinearGradient(0f, 0f, 512f, 512f, 0xFF3B2A8F.toInt(), 0xFFE0559B.toInt(), Shader.TileMode.CLAMP)
        })
        return b
    }

    private companion object {
        const val TAG = "MediaFixture"
    }
}
