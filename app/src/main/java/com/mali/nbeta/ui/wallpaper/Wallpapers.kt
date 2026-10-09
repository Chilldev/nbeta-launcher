package com.mali.nbeta.ui.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable

/** One place a wallpaper can come from. [intent] is ready to start from the launcher. */
class WallpaperSource(val kind: Kind, val label: String, val icon: Drawable?, val intent: Intent) {
    enum class Kind { System, Photos, OtherPhotos, Live, App }
}

/**
 * Where wallpapers can come from on this phone. The plain SET_WALLPAPER chooser only lists apps that registered for it
 * (on Samsung: Google Photos and the live picker), missing the system's own wallpaper screen and gallery apps such as
 * Samsung Gallery, which only answer "pick an image". So this asks for each separately.
 */
object Wallpapers {
    private const val WALLPAPER_SETTINGS = "android.settings.WALLPAPER_SETTINGS"
    private val livePickerPackages = setOf("com.android.wallpaper.livepicker")

    fun sources(context: Context): List<WallpaperSource> {
        val pm = context.packageManager
        val out = ArrayList<WallpaperSource>()
        val seen = HashSet<String>()
        fun ResolveInfo.label() = loadLabel(pm).toString()
        fun ResolveInfo.icon(): Drawable? = runCatching { loadIcon(pm) }.getOrNull()

        // The phone's own wallpaper & style screen (Samsung's Wallpaper and style, Pixel's Wallpaper & style…).
        Intent(WALLPAPER_SETTINGS).let { i ->
            pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.let { r ->
                out += WallpaperSource(WallpaperSource.Kind.System, r.label(), r.icon(), i.component(r))
            }
        }

        // Gallery apps: each one that can hand back a picture, opened directly.
        val pick = Intent(Intent.ACTION_PICK).setType("image/*")
        pm.queryIntentActivities(pick, PackageManager.MATCH_DEFAULT_ONLY)
            .sortedWith(compareByDescending<ResolveInfo> { it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }.thenBy { it.label() })
            .forEach { r ->
                if (seen.add(r.activityInfo.packageName)) {
                    out += WallpaperSource(WallpaperSource.Kind.Photos, r.label(), r.icon(), WallpaperActivity.pickWith(context, r.component()))
                }
            }
        out += WallpaperSource(WallpaperSource.Kind.OtherPhotos, "", null, WallpaperActivity.pickWith(context, null))

        Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).let { i ->
            pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.let { r ->
                seen += r.activityInfo.packageName
                out += WallpaperSource(WallpaperSource.Kind.Live, r.label(), r.icon(), i.component(r))
            }
        }

        // Wallpaper apps (Backdrops, Walli…) that registered for SET_WALLPAPER. The system's own pickers are already
        // behind the first row when it exists.
        val hasSystemRow = out.any { it.kind == WallpaperSource.Kind.System }
        val labels = HashSet<String>()
        pm.queryIntentActivities(Intent(Intent.ACTION_SET_WALLPAPER), PackageManager.MATCH_DEFAULT_ONLY).forEach { r ->
            val pkg = r.activityInfo.packageName
            val system = r.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
            if (pkg !in livePickerPackages && !(hasSystemRow && system) && labels.add(r.label()) && seen.add(pkg)) {
                out += WallpaperSource(WallpaperSource.Kind.App, r.label(), r.icon(), Intent(Intent.ACTION_SET_WALLPAPER).component(r))
            }
        }
        return out
    }

    private fun ResolveInfo.component() = ComponentName(activityInfo.packageName, activityInfo.name)
    private fun Intent.component(r: ResolveInfo) = setComponent(r.component()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
