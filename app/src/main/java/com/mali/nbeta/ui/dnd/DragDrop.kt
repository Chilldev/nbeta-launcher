package com.mali.nbeta.ui.dnd

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mali.nbeta.data.Container
import com.mali.nbeta.data.HomeItem
import com.mali.nbeta.data.Layout
import com.mali.nbeta.data.apps.AppEntry
import com.mali.nbeta.data.stableKey
import com.mali.nbeta.R
import com.mali.nbeta.ui.LauncherController
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/** What is being dragged: the item, where it came from (null = drawer/search), and how to draw it. */
data class DragItem(
    val item: HomeItem,
    val origin: Container?,
    val app: AppEntry?,
    val bitmap: ImageBitmap?,
)

sealed interface DropTarget {
    data class Insert(val container: Container, val index: Int) : DropTarget
    data class Merge(val container: Container, val target: HomeItem) : DropTarget
    data object Remove : DropTarget
    data object AppInfo : DropTarget
    data object Uninstall : DropTarget
}

/** A grid that accepts drops. Registered by the grid itself; geometry is read lazily at drop time. */
class DropZone(val container: Container, var columns: Int) {
    var coords: LayoutCoordinates? = null
    var cellW = 1f
    var cellH = 1f
    var iconCenterY = 0f

    /** What the grid shows, *without* the item being dragged. Indexes of drops refer to this list. */
    var items: List<HomeItem> = emptyList()
    var capacity = Int.MAX_VALUE
}

val LocalDragDrop = staticCompositionLocalOf<DragDrop?> { null }

/**
 * Launcher drag and drop. A tile starts a drag after a long-press; the gesture stays with that tile's pointer input
 * (so its composable must stay composed: sources are hidden, never removed), and reports window positions here.
 */
@Stable
class DragDrop(private val c: LauncherController) {
    var item by mutableStateOf<DragItem?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var target by mutableStateOf<DropTarget?>(null)
        private set

    /** Bumped when a drag ends, so screens can reset state they kept stable during the drag. */
    var finished by mutableIntStateOf(0)
        private set

    val active get() = item != null
    private var mergeKey: String? = null
    private var mergeSince = 0L
    private val zones = HashMap<Container, DropZone>()
    val actionRects = HashMap<DropTarget, Rect>()

    fun zone(container: Container, columns: Int): DropZone = zones.getOrPut(container) { DropZone(container, columns) }.also { it.columns = columns }

    fun start(d: DragItem, at: Offset) {
        item = d
        pointer = at
        c.menu = null
        if (!c.drawer.isClosed) c.drawer.close()
        target = compute(at)
    }

    fun move(at: Offset) {
        pointer = at
        target = compute(at)
    }

    /** Re-evaluates a stationary finger (hover-to-make-folder needs time to pass, not movement). */
    fun tick() {
        if (active) target = compute(pointer)
    }

    companion object {
        private const val MERGE_DWELL_MS = 350L
    }

    fun end() {
        val d = item ?: return
        val t = target
        item = null
        target = null
        actionRects.clear()
        commit(d, t)
        finished++
    }

    fun cancel() {
        if (item == null) return
        item = null
        target = null
        actionRects.clear()
        finished++
    }

    private fun compute(p: Offset): DropTarget? {
        val d = item ?: return null
        actionRects.forEach { (t, r) -> if (r.contains(p)) return t }
        for (z in zones.values) {
            val co = z.coords?.takeIf { it.isAttached } ?: continue
            val r = co.boundsInWindow()
            if (r.width <= 0f || !r.contains(p)) continue
            if (z.container is Container.Folder && d.item is HomeItem.Folder) return null
            val local = p - r.topLeft
            val col = (local.x / z.cellW).toInt().coerceIn(0, z.columns - 1)
            val row = (local.y / z.cellH).toInt().coerceAtLeast(0)
            val idx = row * z.columns + col
            if (idx < z.items.size && z.container !is Container.Folder && d.item !is HomeItem.Folder) {
                // Hovering over the middle of an icon for a moment makes (or joins) a folder. Until then nothing
                // reflows, so the icon stays under the finger; elsewhere in the cell is an insert.
                val cx = (col + 0.5f) * z.cellW
                val cy = row * z.cellH + z.iconCenterY
                if (abs(local.x - cx) < z.cellW * 0.22f && abs(local.y - cy) < z.cellH * 0.22f) {
                    val key = z.items[idx].stableKey
                    val now = android.os.SystemClock.uptimeMillis()
                    if (key != mergeKey) {
                        mergeKey = key
                        mergeSince = now
                    }
                    return if (now - mergeSince >= MERGE_DWELL_MS) DropTarget.Merge(z.container, z.items[idx]) else target
                }
            }
            mergeKey = null
            val fromHere = d.origin == z.container
            if (!fromHere && z.items.size >= z.capacity) return null
            return DropTarget.Insert(z.container, idx.coerceIn(0, z.items.size))
        }
        // Anywhere else on a home page appends to that page.
        val page = c.currentHomePage
        if (page >= 0 && !c.isOverDock(p)) {
            val z = zones[Container.Page(page)]
            return DropTarget.Insert(Container.Page(page), z?.items?.size ?: Layout.items(c.graph.settings.value, Container.Page(page)).size)
        }
        return null
    }

    private fun commit(d: DragItem, t: DropTarget?) {
        val settings = c.graph.settings
        when (t) {
            is DropTarget.Insert -> settings.update { Layout.move(it, d.item, t.container, t.index) }
            is DropTarget.Merge -> settings.update { Layout.merge(it, d.item, t.target, c.activity.getString(R.string.home_folder_default_name)) }
            DropTarget.Remove -> if (d.origin != null) c.removeItem(d.item)
            DropTarget.AppInfo -> d.app?.let { c.graph.apps.openAppInfo(it) }
            DropTarget.Uninstall -> d.app?.let { c.graph.apps.uninstall(it) }
            null -> Unit
        }
    }

    fun isDragging(item: HomeItem) = this.item?.item?.stableKey == item.stableKey
}

/**
 * Tap, long-press (menu) and long-press-then-drag on one tile, as a single gesture:
 * - before the long-press timeout, movement past touch slop hands the gesture to the parent (scroll, pager, drawer);
 * - after it, the gesture is ours: the menu opens, and moving further turns it into a drag.
 * The down event is consumed so the home screen's own long-press/double-tap don't also fire.
 */
fun Modifier.tileGestures(
    key: Any?,
    interaction: MutableInteractionSource,
    dnd: DragDrop?,
    coords: () -> LayoutCoordinates?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    dragItem: (() -> DragItem?)?,
): Modifier = pointerInput(key, dnd) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        down.consume()
        val press = PressInteraction.Press(down.position)
        interaction.tryEmit(press)
        // 1 = released (tap), 2 = someone else took it (scroll/swipe), null = long-press timeout.
        val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var outcome = 2
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (ch.changedToUp()) {
                    if (!ch.isConsumed) {
                        ch.consume()
                        outcome = 1
                    }
                    break
                }
                if (ch.isConsumed || (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
            outcome
        }
        if (early != null) {
            interaction.tryEmit(if (early == 1) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            if (early == 1) onClick()
            return@awaitEachGesture
        }

        onLongPress()
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) break
            if (dnd != null && dragItem != null && !dnd.active &&
                (ch.position - down.position).getDistance() > viewConfiguration.touchSlop * 1.5f
            ) {
                val d = dragItem()
                val co = coords()
                if (d != null && co != null && co.isAttached) {
                    interaction.tryEmit(PressInteraction.Cancel(press))
                    // From here the launcher root follows the finger (see Modifier.dragHost), so this tile may be
                    // scrolled away or disposed (page change, drawer closing) without dropping the item.
                    dnd.start(d, co.localToWindow(ch.position))
                    return@awaitEachGesture
                }
            }
            ch.consume()
        }
        interaction.tryEmit(PressInteraction.Release(press))
    }
}

/**
 * Put on the root of the launcher window. Every pointer event passes through it first (Initial pass), so once a drag
 * has started it tracks the finger and drops on release, regardless of what happens to the tile that started it.
 */
fun Modifier.dragHost(dnd: DragDrop): Modifier = pointerInput(dnd) {
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            if (!dnd.active) continue
            val ch = ev.changes.firstOrNull() ?: continue
            ev.changes.forEach { it.consume() }
            if (ch.pressed) dnd.move(ch.position) else dnd.end()
        }
    }
}

/** The floating icon under the finger, plus Remove / App info / Uninstall targets along the top. */
@Composable
fun DragOverlay(dnd: DragDrop, iconSize: Dp) {
    val d = dnd.item ?: return
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            if (d.origin != null) ActionTarget(dnd, DropTarget.Remove, Icons.Default.Close, stringResource(R.string.common_remove))
            if (d.app != null) {
                ActionTarget(dnd, DropTarget.AppInfo, Icons.Default.Info, stringResource(R.string.common_app_info))
                ActionTarget(dnd, DropTarget.Uninstall, Icons.Default.Delete, stringResource(R.string.common_uninstall))
            }
        }
        val size = iconSize * 1.12f
        val half = with(density) { (size / 2).toPx() }
        val bmp = d.bitmap
        Box(
            Modifier
                .offset { IntOffset((dnd.pointer.x - half).roundToInt(), (dnd.pointer.y - half * 1.3f).roundToInt()) }
                .size(size)
                .shadow(10.dp, RoundedCornerShape(size / 3), clip = false),
        ) {
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(size / 3)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        }
    }
}

@Composable
private fun ActionTarget(dnd: DragDrop, t: DropTarget, icon: ImageVector, label: String) {
    val hovered = dnd.target == t
    Row(
        Modifier
            .onPlaced { dnd.actionRects[t] = it.boundsInWindow().inflate(12f) }
            .clip(RoundedCornerShape(20.dp))
            .background(if (hovered) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (hovered) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelLarge)
    }
}
