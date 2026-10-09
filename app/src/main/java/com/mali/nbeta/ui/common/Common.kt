package com.mali.nbeta.ui.common

import android.app.ActivityOptions
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.AndroidUiDispatcher
import com.mali.nbeta.AppGraph
import com.mali.nbeta.data.apps.IconStyle
import com.mali.nbeta.data.IconShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("No AppGraph") }
val LocalIconStyle = compositionLocalOf { IconStyle(IconShape.System, null, false, false) }
/** "package#userSerial" -> notification count. */
val LocalDots = compositionLocalOf { emptyMap<String, Int>() }
/** Show the count inside the dot instead of a plain dot. */
val LocalDotCounts = compositionLocalOf { false }
val LocalIconOverrides = compositionLocalOf { emptyMap<String, String>() }

/** Colours for text drawn straight on the wallpaper. */
/** [scrim] is laid behind the status bar/glance and the dock so text stays legible on any wallpaper. */
data class OnWallpaper(val text: Color, val secondary: Color, val shadow: Shadow?, val scrim: Color = Color.Transparent)

val LocalOnWallpaper = compositionLocalOf {
    OnWallpaper(Color.White, Color.White.copy(alpha = 0.9f), Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1.5f), 10f), Color.Black.copy(alpha = 0.45f))
}

/**
 * Holds the last placed coordinates of an item. Updated in onPlaced (a field write, no recomposition), read only on
 * click, which is far cheaper than tracking bounds with onGloballyPositioned on every scroll frame.
 */
class BoundsHolder {
    var coords: LayoutCoordinates? = null

    fun rect(): Rect? = coords?.takeIf { it.isAttached }?.boundsInWindow()?.let {
        Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
    }
}

fun launchOptions(view: View, r: Rect?): Bundle? =
    r?.let { ActivityOptions.makeClipRevealAnimation(view, it.left, it.top, it.width(), it.height()).toBundle() }

/**
 * Drives a full-screen sheet (the app drawer) from 0 (closed) to 1 (open). The value is plain float state read inside
 * graphicsLayer blocks, so dragging re-draws without recomposing anything.
 */
@Stable
class SheetController(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f)
        private set
    var height = 1f
    private var job: Job? = null

    val isOpen get() = progress > 0.5f
    val isClosed get() = progress == 0f

    /** dy in pixels; negative (finger moving up) opens. */
    private var lastDy = 0f

    fun dragBy(dy: Float) {
        job?.cancel()
        if (dy != 0f) lastDy = dy
        progress = (progress - dy / height).coerceIn(0f, 1f)
    }

    fun settle(velocityY: Float) {
        android.util.Log.d("Sheet", "settle v=$velocityY p=$progress")
        val target = when {
            velocityY < -800f -> 1f
            velocityY > 800f -> 0f
            // Slow release: follow the direction the finger was last moving, once past a small threshold.
            progress > 0.85f -> 1f
            progress < 0.08f -> 0f
            else -> if (lastDy < 0f) 1f else 0f
        }
        animateTo(target, velocityY)
    }

    fun open() = animateTo(1f)
    fun close() = animateTo(0f)

    fun snapClosed() {
        job?.cancel()
        progress = 0f
    }

    private fun animateTo(target: Float, velocityY: Float = 0f) {
        job?.cancel()
        if (abs(progress - target) < 0.0005f) {
            progress = target
            return
        }
        // AndroidUiDispatcher.Main carries the Choreographer frame clock that animate() needs.
        job = scope.launch(AndroidUiDispatcher.Main) {
            try {
            animate(progress, target, initialVelocity = -velocityY / height, animationSpec = spring(dampingRatio = 0.92f, stiffness = 520f)) { v, _ ->
                progress = v
            }
            android.util.Log.d("Sheet", "done p=$progress")
            } catch (e: Throwable) { android.util.Log.d("Sheet", "anim ended $e"); throw e }
        }
    }
}
