package com.mali.nbeta.data

import java.util.UUID

/** Where a home item lives. */
sealed interface Container {
    data class Page(val index: Int) : Container
    data object Dock : Container
    data class Folder(val id: String) : Container
}

/** The home screen's pages, never empty. */
val LauncherSettings.homePages: List<List<HomeItem>> get() = pages.ifEmpty { listOf(emptyList()) }

/**
 * Pure edits of the home layout (pages, dock, folders). Every drag-and-drop and menu action goes through here, so the
 * rules live in one tested place: items are identified by [stableKey], an item appears at most once, folders never
 * nest, and [normalize] tidies up after each edit.
 */
object Layout {
    fun items(s: LauncherSettings, c: Container): List<HomeItem> = when (c) {
        is Container.Page -> s.homePages.getOrNull(c.index).orEmpty()
        Container.Dock -> s.dockItems
        is Container.Folder -> folder(s, c.id)?.items.orEmpty()
    }

    private fun topLevel(s: LauncherSettings): Sequence<HomeItem> = s.homePages.asSequence().flatten() + s.dockItems.asSequence()

    fun folder(s: LauncherSettings, id: String): HomeItem.Folder? =
        topLevel(s).filterIsInstance<HomeItem.Folder>().firstOrNull { it.id == id }

    /** Every app key placed anywhere (pages, dock, inside folders). */
    fun appKeys(s: LauncherSettings): Set<String> = topLevel(s).flatMap { if (it is HomeItem.Folder) it.items.asSequence() else sequenceOf(it) }
        .filterIsInstance<HomeItem.App>().map { it.key }.toSet()

    fun containerOf(s: LauncherSettings, item: HomeItem): Container? {
        val k = item.stableKey
        s.homePages.forEachIndexed { i, page -> if (page.any { it.stableKey == k }) return Container.Page(i) }
        if (s.dockItems.any { it.stableKey == k }) return Container.Dock
        topLevel(s).filterIsInstance<HomeItem.Folder>().forEach { f -> if (f.items.any { it.stableKey == k }) return Container.Folder(f.id) }
        return null
    }

    fun withItems(s: LauncherSettings, c: Container, list: List<HomeItem>): LauncherSettings = when (c) {
        is Container.Page -> {
            val pages = s.homePages.toMutableList()
            while (pages.size <= c.index) pages += emptyList<HomeItem>()
            pages[c.index] = list
            s.copy(pages = pages)
        }
        Container.Dock -> s.copy(dockItems = list)
        is Container.Folder -> mapTopLevel(s) { if (it is HomeItem.Folder && it.id == c.id) it.copy(items = list) else it }
    }

    private fun mapTopLevel(s: LauncherSettings, f: (HomeItem) -> HomeItem) =
        s.copy(pages = s.homePages.map { p -> p.map(f) }, dockItems = s.dockItems.map(f))

    /** Removes the item wherever it is, including from inside folders. */
    fun remove(s: LauncherSettings, item: HomeItem): LauncherSettings {
        val k = item.stableKey
        fun clean(list: List<HomeItem>) = list.filter { it.stableKey != k }.map { if (it is HomeItem.Folder) it.copy(items = it.items.filter { i -> i.stableKey != k }) else it }
        return s.copy(pages = s.homePages.map(::clean), dockItems = clean(s.dockItems))
    }

    fun insert(s: LauncherSettings, c: Container, index: Int, item: HomeItem): LauncherSettings {
        if (c is Container.Folder && item is HomeItem.Folder) return s
        val list = items(s, c).toMutableList()
        list.add(index.coerceIn(0, list.size), item)
        return withItems(s, c, list)
    }

    /** [index] is a position in the target list *without* the moved item (what the user sees while dragging). */
    fun move(s: LauncherSettings, item: HomeItem, to: Container, index: Int): LauncherSettings =
        normalize(insert(remove(s, item), to, index, item))

    /** Drops [item] onto [target]: joins the target folder, or makes a new folder of the two. */
    fun merge(s: LauncherSettings, item: HomeItem, target: HomeItem, newFolderName: String = "Folder"): LauncherSettings {
        if (item.stableKey == target.stableKey || item is HomeItem.Folder) return s
        val removed = remove(s, item)
        val c = containerOf(removed, target) ?: return s
        if (c is Container.Folder) return s
        val list = items(removed, c).toMutableList()
        val i = list.indexOfFirst { it.stableKey == target.stableKey }
        if (i < 0) return s
        val current = list[i]
        list[i] = if (current is HomeItem.Folder) current.copy(items = current.items + item)
        else HomeItem.Folder(UUID.randomUUID().toString().take(8), newFolderName, listOf(current, item))
        return normalize(withItems(removed, c, list))
    }

    fun renameFolder(s: LauncherSettings, id: String, name: String) =
        mapTopLevel(s) { if (it is HomeItem.Folder && it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it }

    /** Replaces the folder with its contents, in place. */
    fun ungroup(s: LauncherSettings, id: String): LauncherSettings {
        fun expand(list: List<HomeItem>) = list.flatMap { if (it is HomeItem.Folder && it.id == id) it.items else listOf(it) }
        return normalize(s.copy(pages = s.homePages.map(::expand), dockItems = expand(s.dockItems)))
    }

    /**
     * Folders with one item become that item, empty folders vanish, and empty pages after the first are dropped
     * (unless a widget lives there), renumbering widget pages to match.
     */
    fun normalize(s: LauncherSettings): LauncherSettings {
        fun fix(list: List<HomeItem>) = list.mapNotNull {
            if (it is HomeItem.Folder) when (it.items.size) {
                0 -> null
                1 -> it.items[0]
                else -> it
            } else it
        }
        val pages = s.homePages.map(::fix)
        val widgetPages = s.widgets.filter { it.placement == WidgetPlacement.Home }.map { it.page }.toSet()
        val keep = pages.indices.filter { i -> i == 0 || pages[i].isNotEmpty() || i in widgetPages }
        val remap = keep.withIndex().associate { (newIdx, old) -> old to newIdx }
        return s.copy(
            pages = keep.map { pages[it] },
            dockItems = fix(s.dockItems),
            widgets = s.widgets.map { w -> if (w.placement == WidgetPlacement.Home) w.copy(page = remap[w.page] ?: 0) else w },
        )
    }

    fun addPage(s: LauncherSettings): Pair<LauncherSettings, Int> = s.copy(pages = s.homePages + listOf(emptyList())) to s.homePages.size
}
