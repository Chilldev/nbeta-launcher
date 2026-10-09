package com.mali.nbeta.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.widget.Toast
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mali.nbeta.AppGraph
import com.mali.nbeta.data.HomeItem
import com.mali.nbeta.data.WidgetPlacement
import com.mali.nbeta.data.apps.AppEntry
import com.mali.nbeta.data.apps.AppShortcut
import com.mali.nbeta.ui.common.BoundsHolder
import com.mali.nbeta.ui.common.SheetController
import com.mali.nbeta.ui.common.launchOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow

enum class Origin { Home, Dock, Drawer, Search }

sealed interface MenuTarget {
    data class App(val app: AppEntry, val origin: Origin) : MenuTarget
    data class PinnedShortcut(val item: HomeItem.Shortcut, val origin: Origin) : MenuTarget
}

data class MenuRequest(val target: MenuTarget, val anchor: Rect)

/** UI state and actions shared by the launcher's screens. Lives as long as the activity. */
@Stable
class LauncherController(
    val activity: LauncherActivity,
    val graph: AppGraph,
    val scope: CoroutineScope,
    launchBind: (Intent) -> Unit,
) {
    val drawer = SheetController(scope)

    /** Hoisted so the feed keeps its scroll position while the page is off screen. */
    val feedListState = androidx.compose.foundation.lazy.LazyListState()
    var query by mutableStateOf("")
    var menu by mutableStateOf<MenuRequest?>(null)
    var homeMenu by mutableStateOf(false)
    var widgetPicker by mutableStateOf<WidgetPlacement?>(null)
    var widgetMenu by mutableStateOf<Int?>(null)
    var renameTarget by mutableStateOf<AppEntry?>(null)
    var editingHome by mutableStateOf(false)
    var focusSearch by mutableIntStateOf(0)
        private set

    /** true = animate (launcher was already visible). */
    val homeEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val widgets = WidgetFlow(this, launchBind)

    private val view get() = activity.window.decorView

    fun launch(app: AppEntry, bounds: BoundsHolder?) {
        val r = bounds?.rect()
        graph.apps.launch(app, r, launchOptions(view, r))
    }

    fun launchShortcut(shortcut: AppShortcut, bounds: BoundsHolder?) {
        val r = bounds?.rect()
        graph.shortcuts.launch(shortcut, r, launchOptions(view, r))
    }

    fun start(intent: Intent, bounds: BoundsHolder? = null) {
        try {
            val r = bounds?.rect()
            activity.startActivity(intent, launchOptions(view, r))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No app can handle this", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(activity, "Not allowed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun openSearch() {
        drawer.open()
        focusSearch++
    }

    fun onHomePressed(animate: Boolean) {
        menu = null
        homeMenu = false
        widgetMenu = null
        widgetPicker = null
        editingHome = false
        homeEvents.tryEmit(animate)
    }

    fun onStopped() {
        // Returning home after launching something from the drawer lands on a clean home screen.
        if (!drawer.isClosed) {
            drawer.snapClosed()
            query = ""
        }
        menu = null
        editingHome = false
    }

    // --- Home layout edits ---

    private fun HomeItem.matches(key: String) = this is HomeItem.App && this.key == key

    fun isOnHome(key: String) = graph.settings.value.homeItems.any { it.matches(key) }
    fun isInDock(key: String) = graph.settings.value.dockItems.any { it.matches(key) }

    fun toggleHome(key: String) = graph.settings.update { s ->
        if (s.homeItems.any { it.matches(key) }) s.copy(homeItems = s.homeItems.filterNot { it.matches(key) })
        else s.copy(homeItems = s.homeItems + HomeItem.App(key))
    }

    fun toggleDock(key: String) = graph.settings.update { s ->
        when {
            s.dockItems.any { it.matches(key) } -> s.copy(dockItems = s.dockItems.filterNot { it.matches(key) })
            s.dockItems.size >= MAX_DOCK -> {
                Toast.makeText(activity, "The dock holds $MAX_DOCK apps", Toast.LENGTH_SHORT).show()
                s
            }
            else -> s.copy(dockItems = s.dockItems + HomeItem.App(key))
        }
    }

    fun removeItem(item: HomeItem) = graph.settings.update { s ->
        s.copy(homeItems = s.homeItems - item, dockItems = s.dockItems - item)
    }

    /** Moves an item within the home grid or dock, used by drag-to-reorder. */
    fun moveItem(item: HomeItem, inDock: Boolean, toIndex: Int) = graph.settings.update { s ->
        val list = (if (inDock) s.dockItems else s.homeItems).toMutableList()
        val from = list.indexOf(item)
        if (from < 0) return@update s
        list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), item)
        if (inDock) s.copy(dockItems = list) else s.copy(homeItems = list)
    }

    fun hide(app: AppEntry) {
        graph.settings.update { s -> s.copy(hiddenApps = s.hiddenApps + app.key) }
        Toast.makeText(activity, "${app.label} hidden. Unhide it in Settings › App drawer.", Toast.LENGTH_SHORT).show()
    }

    fun rename(app: AppEntry, label: String?) = graph.settings.update { s ->
        val clean = label?.trim().orEmpty()
        s.copy(renamedApps = if (clean.isEmpty() || clean == app.originalLabel) s.renamedApps - app.key else s.renamedApps + (app.key to clean))
    }

    companion object {
        const val MAX_DOCK = 5
    }
}

/**
 * Adding a widget: allocate an id, bind it (asking the user once if needed), run the provider's configuration
 * activity when it is required, then place it. Every exit path releases the id if the widget is not placed.
 */
class WidgetFlow(private val c: LauncherController, private val launchBind: (Intent) -> Unit) {
    private var pendingId = -1
    private var pendingInfo: AppWidgetProviderInfo? = null
    private var pendingPlacement = WidgetPlacement.Home
    private val repo get() = c.graph.widgets

    fun add(info: AppWidgetProviderInfo, placement: WidgetPlacement) {
        val id = repo.allocate()
        pendingId = id
        pendingInfo = info
        pendingPlacement = placement
        if (repo.bindIfAllowed(id, info)) {
            configureOrPlace()
        } else {
            launchBind(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile),
            )
        }
    }

    fun onBindResult(ok: Boolean) {
        if (ok) configureOrPlace() else cancel()
    }

    private fun configureOrPlace() {
        val info = pendingInfo ?: return cancel()
        if (repo.needsConfigure(info)) {
            try {
                repo.host.startAppWidgetConfigureActivityForResult(c.activity, pendingId, 0, REQUEST_CONFIGURE, null)
            } catch (e: Exception) {
                Toast.makeText(c.activity, "This widget can't be set up", Toast.LENGTH_SHORT).show()
                cancel()
            }
        } else {
            place()
        }
    }

    fun reconfigure(id: Int) {
        try {
            repo.host.startAppWidgetConfigureActivityForResult(c.activity, id, 0, REQUEST_RECONFIGURE, null)
        } catch (_: Exception) {
            Toast.makeText(c.activity, "Can't reconfigure this widget", Toast.LENGTH_SHORT).show()
        }
    }

    fun onConfigureResult(ok: Boolean, id: Int) {
        if (pendingId < 0 || (id >= 0 && id != pendingId)) return
        if (ok) place() else cancel()
    }

    private fun place() {
        repo.add(pendingId, pendingPlacement)
        pendingId = -1
        pendingInfo = null
    }

    private fun cancel() {
        if (pendingId >= 0) repo.discard(pendingId)
        pendingId = -1
        pendingInfo = null
    }

    companion object {
        const val REQUEST_CONFIGURE = 4101
        const val REQUEST_RECONFIGURE = 4102
    }
}
