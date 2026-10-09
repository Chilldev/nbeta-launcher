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
import com.mali.nbeta.R
import com.mali.nbeta.data.Container
import com.mali.nbeta.data.HomeItem
import com.mali.nbeta.data.Layout
import com.mali.nbeta.data.homePages
import com.mali.nbeta.data.stableKey
import com.mali.nbeta.ui.dnd.DragDrop
import com.mali.nbeta.data.WidgetPlacement
import com.mali.nbeta.data.apps.AppEntry
import com.mali.nbeta.data.apps.AppShortcut
import com.mali.nbeta.ui.common.BoundsHolder
import com.mali.nbeta.ui.common.SheetController
import com.mali.nbeta.ui.common.launchOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow

enum class Origin { Home, Dock, Drawer, Search, Folder }

sealed interface MenuTarget {
    /** [container] is where the tile lives on the home screen (null in drawer/search). */
    data class App(val app: AppEntry, val origin: Origin, val container: Container? = null) : MenuTarget
    data class PinnedShortcut(val item: HomeItem.Shortcut, val origin: Origin) : MenuTarget
    data class Folder(val folder: HomeItem.Folder) : MenuTarget
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
    var iconPickerFor by mutableStateOf<AppEntry?>(null)
    var editingHome by mutableStateOf(false)
    var openFolder by mutableStateOf<String?>(null)

    /** Index into the home pages (feed excluded) currently shown; -1 while on the feed. */
    var currentHomePage by mutableIntStateOf(0)
    var dockBounds: androidx.compose.ui.geometry.Rect? = null
    var dockHeightPx by mutableIntStateOf(0)
    fun isOverDock(p: androidx.compose.ui.geometry.Offset) = dockBounds?.contains(p) == true

    val dnd = DragDrop(this)
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
            Toast.makeText(activity, R.string.home_no_app, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(activity, activity.getString(R.string.home_not_allowed, e.message), Toast.LENGTH_SHORT).show()
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
        openFolder = null
        dnd.cancel()
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
        openFolder = null
    }

    // --- Home layout edits (rules live in Layout) ---

    fun isOnHome(key: String): Boolean {
        val s = graph.settings.value
        return Layout.appKeys(s.copy(dockItems = emptyList())).contains(key)
    }

    fun isInDock(key: String) = graph.settings.value.dockItems.any { it is HomeItem.App && it.key == key }

    fun toggleHome(key: String) = graph.settings.update { s ->
        val item = HomeItem.App(key)
        val where = Layout.containerOf(s, item)
        if (where != null && where != Container.Dock) Layout.normalize(Layout.remove(s, item))
        else {
            val page = currentHomePage.coerceIn(0, s.homePages.lastIndex)
            Layout.move(s, item, Container.Page(page), Layout.items(s, Container.Page(page)).size)
        }
    }

    fun toggleDock(key: String) = graph.settings.update { s ->
        val item = HomeItem.App(key)
        when {
            s.dockItems.any { it.stableKey == item.stableKey } -> Layout.normalize(Layout.remove(s, item))
            s.dockItems.size >= MAX_DOCK -> {
                Toast.makeText(activity, activity.resources.getQuantityString(R.plurals.home_dock_full, MAX_DOCK, MAX_DOCK), Toast.LENGTH_SHORT).show()
                s
            }
            else -> Layout.move(s, item, Container.Dock, s.dockItems.size)
        }
    }

    fun removeItem(item: HomeItem) {
        if (item is HomeItem.Shortcut) graph.shortcuts.unpin(item.packageName, item.id, item.userSerial)
        graph.settings.update { Layout.normalize(Layout.remove(it, item)) }
    }

    fun addPage() {
        graph.settings.update { Layout.addPage(it).first }
    }

    fun hide(app: AppEntry) {
        graph.settings.update { s -> s.copy(hiddenApps = s.hiddenApps + app.key) }
        Toast.makeText(activity, activity.getString(R.string.home_app_hidden, app.label), Toast.LENGTH_SHORT).show()
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
                Toast.makeText(c.activity, R.string.widget_cant_set_up, Toast.LENGTH_SHORT).show()
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
            Toast.makeText(c.activity, R.string.widget_cant_reconfigure, Toast.LENGTH_SHORT).show()
        }
    }

    fun onConfigureResult(ok: Boolean, id: Int) {
        if (pendingId < 0 || (id >= 0 && id != pendingId)) return
        if (ok) place() else cancel()
    }

    private fun place() {
        repo.add(pendingId, pendingPlacement, c.currentHomePage.coerceAtLeast(0))
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
