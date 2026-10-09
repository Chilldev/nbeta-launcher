package com.mali.nbeta.ui.wallpaper

import com.mali.nbeta.system.DiagLog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import com.mali.nbeta.R

/**
 * Opens a gallery app to pick a photo, then hands the photo to the system's own "set as wallpaper" screen (Samsung's
 * crop-and-apply with home/lock choice, or the AOSP cropper), so cropping and the home/lock choice stay native.
 */
class WallpaperActivity : ComponentActivity() {
    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: r.data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (r.resultCode == RESULT_OK && uri != null) setAs(uri, r.data?.type ?: contentResolver.getType(uri))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // the picker is already open; its result comes back here
        val picker = IntentCompat.getParcelableExtra(intent, EXTRA_PICKER, ComponentName::class.java)
        val request = if (picker != null) {
            Intent(Intent.ACTION_PICK).setType("image/*").setComponent(picker)
        } else {
            Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), getString(R.string.wallpaper_pick_photo))
        }
        try {
            pick.launch(request)
        } catch (e: Exception) {
            DiagLog.w(TAG, "No picker", e)
            finish()
        }
    }

    private fun setAs(uri: Uri, type: String?) {
        val mime = type?.takeIf { it.startsWith("image/") } ?: "image/*"
        val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION
        // 1. The system's own handler for "set this image as wallpaper" (Samsung: Wallpaper and style).
        val setAs = Intent(Intent.ACTION_SET_WALLPAPER).setDataAndType(uri, mime).addFlags(grant)
        val system = packageManager.queryIntentActivities(setAs, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
        val attempts = buildList {
            if (system != null) add(Intent(setAs).setComponent(ComponentName(system.activityInfo.packageName, system.activityInfo.name)))
            // 2. The platform cropper.
            runCatching { WallpaperManager.getInstance(this@WallpaperActivity).getCropAndSetWallpaperIntent(uri) }.getOrNull()?.let { add(it.addFlags(grant)) }
            // 3. Any "Set as…" handler.
            add(Intent.createChooser(Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(uri, mime).putExtra("mimeType", mime).addFlags(grant), null).addFlags(grant))
        }
        for (i in attempts) {
            try {
                startActivity(i)
                return
            } catch (e: Exception) {
                DiagLog.w(TAG, "Can't open $i", e)
            }
        }
        Toast.makeText(this, R.string.wallpaper_cant_set, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "Wallpaper"
        private const val EXTRA_PICKER = "picker"

        /** [picker] null = the system chooser of every app that can provide an image (files, cloud drives…). */
        fun pickWith(context: Context, picker: ComponentName?): Intent =
            Intent(context, WallpaperActivity::class.java).putExtra(EXTRA_PICKER, picker).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
