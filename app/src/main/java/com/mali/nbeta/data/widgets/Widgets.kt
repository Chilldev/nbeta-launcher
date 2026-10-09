package com.mali.nbeta.data.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.ViewGroup
import androidx.compose.runtime.Immutable
import com.mali.nbeta.data.SettingsRepository
import com.mali.nbeta.data.WidgetPlacement
import com.mali.nbeta.data.WidgetSlot

/** Long-press on widgets is detected in Compose (WidgetFrame), so the host uses plain AppWidgetHostViews. */
class LauncherWidgetHost(context: Context) : AppWidgetHost(context, HOST_ID) {
    var onProvidersChangedListener: (() -> Unit)? = null

    override fun onProvidersChanged() {
        onProvidersChangedListener?.invoke()
    }

    companion object {
        const val HOST_ID = 0x4e42
    }
}

@Immutable
data class WidgetProviderGroup(val appLabel: String, val packageName: String, val providers: List<Pair<AppWidgetProviderInfo, String>>)

class WidgetRepository(private val context: Context, private val settings: SettingsRepository) {
    val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
    val host = LauncherWidgetHost(context).apply { onProvidersChangedListener = { synchronized(infoCache) { infoCache.clear() } } }
    private val views = HashMap<Int, AppWidgetHostView>()
    private val infoCache = HashMap<Int, AppWidgetProviderInfo?>()

    /** Memoised: getAppWidgetInfo is a binder call and composition asks for it often. */
    fun info(id: Int): AppWidgetProviderInfo? = synchronized(infoCache) {
        infoCache.getOrPut(id) { runCatching { manager.getAppWidgetInfo(id) }.getOrNull() }
    }

    /** Host views are kept alive across recompositions; recreating them would reload the remote content. */
    fun view(id: Int): AppWidgetHostView? {
        views[id]?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            return v
        }
        val info = info(id) ?: return null
        return host.createView(context, id, info).also { views[id] = it }
    }

    fun providers(): List<WidgetProviderGroup> {
        val la = context.getSystemService(LauncherApps::class.java)
        val pm = context.packageManager
        return la.profiles.flatMap { user -> runCatching { manager.getInstalledProvidersForProfile(user) }.getOrDefault(emptyList()) }
            .groupBy { it.provider.packageName }
            .map { (pkg, list) ->
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                // Labels come from the provider's resources; load them here, off the main thread, not in composition.
                WidgetProviderGroup(label, pkg, list.map { it to it.loadLabel(pm) }.sortedBy { it.second.lowercase() })
            }
            .sortedBy { it.appLabel.lowercase() }
    }

    fun allocate(): Int = host.allocateAppWidgetId()

    fun bindIfAllowed(id: Int, info: AppWidgetProviderInfo): Boolean = try {
        manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
    } catch (e: Exception) {
        Log.w(TAG, "bind failed", e)
        false
    }

    fun needsConfigure(info: AppWidgetProviderInfo): Boolean {
        if (info.configure == null) return false
        if (Build.VERSION.SDK_INT >= 31) {
            val optional = info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
            if (optional) return false
        }
        return true
    }

    fun isReconfigurable(id: Int): Boolean {
        val info = info(id) ?: return false
        return info.configure != null && info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
    }

    fun add(id: Int, placement: WidgetPlacement, page: Int = 0) {
        synchronized(infoCache) { infoCache.remove(id) }
        settings.update { s -> if (s.widgets.any { it.id == id }) s else s.copy(widgets = s.widgets + WidgetSlot(id, placement, page = page)) }
    }

    fun discard(id: Int) {
        runCatching { host.deleteAppWidgetId(id) }
        views.remove(id)
        synchronized(infoCache) { infoCache.remove(id) }
    }

    fun remove(id: Int) {
        discard(id)
        settings.update { s -> s.copy(widgets = s.widgets.filterNot { it.id == id }) }
    }

    fun update(id: Int, transform: (WidgetSlot) -> WidgetSlot) {
        settings.update { s -> s.copy(widgets = s.widgets.map { if (it.id == id) transform(it) else it }) }
    }

    fun move(id: Int, delta: Int) {
        settings.update { s ->
            val l = s.widgets.toMutableList()
            val i = l.indexOfFirst { it.id == id }
            val me = l.getOrNull(i) ?: return@update s
            // Swap with the nearest widget on the same page.
            var j = i + delta
            while (j in l.indices && (l[j].placement != me.placement || l[j].page != me.page)) j += delta
            if (j !in l.indices) return@update s
            l[i] = l[j].also { l[j] = l[i] }
            s.copy(widgets = l)
        }
    }

    /** Default height in dp: the provider's own target or minimum, whichever is larger. */
    fun defaultHeightDp(id: Int): Int {
        val info = info(id) ?: return 120
        val d = context.resources.displayMetrics.density
        val minDp = (info.minHeight / d).toInt()
        val target = if (Build.VERSION.SDK_INT >= 31 && info.targetCellHeight > 0) info.targetCellHeight * 90 else 0
        return maxOf(minDp, target, 72)
    }

    fun reportSize(id: Int, widthDp: Float, heightDp: Float) {
        val v = views[id] ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                v.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
            } else {
                @Suppress("DEPRECATION")
                v.updateAppWidgetSize(null, widthDp.toInt(), heightDp.toInt(), widthDp.toInt(), heightDp.toInt())
            }
        } catch (e: Exception) {
            Log.w(TAG, "size update failed", e)
        }
    }

    fun startListening() = runCatching { host.startListening() }
    fun stopListening() = runCatching { host.stopListening() }

    /** Drop ids the host still holds but the layout no longer references (e.g. after a cancelled add). */
    fun cleanupOrphans() {
        val used = settings.value.widgets.map { it.id }.toSet()
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching { host.appWidgetIds }.getOrNull()?.forEach { if (it !in used) host.deleteAppWidgetId(it) }
        }
    }

    companion object {
        private const val TAG = "Widgets"
    }
}
