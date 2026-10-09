package com.mali.nbeta.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private fun app(k: String) = HomeItem.App(k)
    private val a = app("a")
    private val b = app("b")
    private val c = app("c")
    private val d = app("d")
    private val base = LauncherSettings(pages = listOf(listOf(a, b, c)), dockItems = listOf(d))

    @Test fun movesWithinAPage() {
        val s = Layout.move(base, a, Container.Page(0), 2) // index in [b, c] -> after c
        assertEquals(listOf(b, c, a), s.homePages[0])
    }

    @Test fun movesBetweenPageAndDock() {
        val s = Layout.move(base, d, Container.Page(0), 1)
        assertEquals(listOf(a, d, b, c), s.homePages[0])
        assertTrue(s.dockItems.isEmpty())
    }

    @Test fun createsAndExtendsFolders() {
        var s = Layout.merge(base, b, a)
        val f = s.homePages[0][0] as HomeItem.Folder
        assertEquals(listOf(a, b), f.items)
        assertEquals(listOf<HomeItem>(f, c), s.homePages[0])
        s = Layout.merge(s, d, f)
        assertEquals(listOf(a, b, d), (s.homePages[0][0] as HomeItem.Folder).items)
        assertTrue(s.dockItems.isEmpty())
    }

    @Test fun folderWithOneItemDissolves() {
        var s = Layout.merge(base, b, a)
        val f = s.homePages[0][0] as HomeItem.Folder
        s = Layout.move(s, b, Container.Page(0), 2) // drag b out of the folder
        assertEquals(listOf(a, c, b), s.homePages[0])
        assertNull(Layout.folder(s, f.id))
    }

    @Test fun foldersDoNotNest() {
        val s1 = Layout.merge(base, b, a)
        val f = s1.homePages[0][0] as HomeItem.Folder
        val s2 = Layout.merge(Layout.merge(s1, d, c), f, c)
        assertEquals(s2.homePages[0].count { it is HomeItem.Folder }, 2)
    }

    @Test fun emptyPagesAreDroppedAndWidgetsRenumbered() {
        val s = base.copy(
            pages = listOf(listOf(a), emptyList(), listOf(b)),
            widgets = listOf(WidgetSlot(1, WidgetPlacement.Home, page = 2)),
        )
        val n = Layout.normalize(s)
        assertEquals(2, n.homePages.size)
        assertEquals(1, n.widgets[0].page)
    }

    @Test fun ungroupRestoresItemsInPlace() {
        val s = Layout.merge(base, c, a)
        val f = s.homePages[0][0] as HomeItem.Folder
        assertEquals(listOf(a, c, b), Layout.ungroup(s, f.id).homePages[0])
    }

    @Test fun appKeysIncludeFolderContents() {
        val s = Layout.merge(base, b, a)
        assertEquals(setOf("a", "b", "c", "d"), Layout.appKeys(s))
    }
}
