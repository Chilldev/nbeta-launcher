package com.mali.nbeta.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaWidgetMigrationTest {
    @Test
    fun oldLayoutGetsMediaWidgetFirstOnPageZero() {
        val old = LauncherSettings(widgets = listOf(WidgetSlot(12), WidgetSlot(13, page = 1)))
        val s = old.migrated()
        val first = s.widgets.first()
        assertEquals(BuiltinWidget.Media, first.builtin)
        assertEquals(WidgetPlacement.Home, first.placement)
        assertEquals(0, first.page)
        assertTrue(first.id < 0)
        assertEquals(listOf(12, 13), s.widgets.drop(1).map { it.id })
        assertTrue(s.mediaWidgetMigrated)
    }

    @Test
    fun migrationRunsOnce() {
        val s = LauncherSettings().migrated()
        assertEquals(s, s.migrated())
        // Removing it stays removed.
        val removed = s.copy(widgets = emptyList())
        assertEquals(removed, removed.migrated())
    }

    @Test
    fun builtinIdsNeverCollide() {
        val s = LauncherSettings(widgets = listOf(WidgetSlot(-1, builtin = BuiltinWidget.Media)), mediaWidgetMigrated = false).migrated()
        assertEquals(s.widgets.size, s.widgets.map { it.id }.toSet().size)
    }
}
