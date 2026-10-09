package com.mali.nbeta.ui.theme

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mali.nbeta.data.LauncherSettings
import com.mali.nbeta.data.ThemeMode
import java.util.concurrent.Executors

private val Light = lightColorScheme(
    primary = Color(0xFF3F5AA9),
    secondary = Color(0xFF585E71),
    tertiary = Color(0xFFE5484D),
    surface = Color(0xFFFBF8FF),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    secondary = Color(0xFFC1C6DD),
    tertiary = Color(0xFFFF8A80),
    surface = Color(0xFF121318),
)

@Composable
fun isDark(settings: LauncherSettings): Boolean = when (settings.themeMode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun NbetaTheme(settings: LauncherSettings, content: @Composable () -> Unit) {
    val dark = isDark(settings)
    val context = LocalContext.current
    val scheme = if (settings.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, content = content)
}

private val wallpaperExecutor by lazy { Executors.newSingleThreadExecutor() }

/** Whether the wallpaper is light enough that home-screen text should be dark. Read off the main thread. */
@Composable
fun rememberWallpaperPrefersDarkText(): Boolean {
    val context = LocalContext.current
    var darkText by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val wm = WallpaperManager.getInstance(context)
        val main = Handler(Looper.getMainLooper())
        fun apply(colors: WallpaperColors?) {
            // The system hint describes the wallpaper as a whole; a mostly dark picture with a bright patch can still
            // claim "supports dark text". Only go dark when every dominant colour is light too, since text sits on
            // more than one region (glance at the top, grid and dock at the bottom).
            val allLight = colors != null && listOfNotNull(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor)
                .all { it.luminance() > 0.45f }
            val dark = when {
                colors == null -> false
                Build.VERSION.SDK_INT >= 31 -> colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0 && allLight
                else -> allLight
            }
            main.post { darkText = dark }
        }
        wallpaperExecutor.execute { apply(runCatching { wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()) }
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) apply(colors)
        }
        wm.addOnColorsChangedListener(listener, main)
        onDispose { wm.removeOnColorsChangedListener(listener) }
    }
    return darkText
}
