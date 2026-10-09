package com.mali.nbeta.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mali.nbeta.data.TextOnWallpaper
import com.mali.nbeta.data.apps.IconStyle
import com.mali.nbeta.system.NotificationDotsService
import com.mali.nbeta.ui.common.LocalDots
import com.mali.nbeta.ui.common.LocalGraph
import com.mali.nbeta.ui.common.LocalIconStyle
import com.mali.nbeta.ui.common.LocalOnWallpaper
import com.mali.nbeta.ui.common.OnWallpaper
import com.mali.nbeta.ui.drawer.AppDrawer
import com.mali.nbeta.ui.feed.FeedPage
import com.mali.nbeta.ui.home.DockArea
import com.mali.nbeta.ui.home.FolderOverlay
import com.mali.nbeta.ui.home.HomePageContent
import com.mali.nbeta.ui.dnd.DragOverlay
import com.mali.nbeta.ui.dnd.dragHost
import com.mali.nbeta.ui.dnd.LocalDragDrop
import com.mali.nbeta.data.homePages
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.mali.nbeta.ui.menu.AppMenuPopup
import com.mali.nbeta.ui.menu.HomeMenuSheet
import com.mali.nbeta.ui.menu.RenameDialog
import com.mali.nbeta.ui.theme.NbetaTheme
import com.mali.nbeta.ui.theme.isDark
import com.mali.nbeta.ui.theme.rememberWallpaperPrefersDarkText
import com.mali.nbeta.ui.widgets.WidgetMenuSheet
import com.mali.nbeta.ui.widgets.WidgetPickerSheet
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun LauncherRoot(c: LauncherController) {
    val graph = c.graph
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val dots by NotificationDotsService.packagesWithDots.collectAsStateWithLifecycle()

    NbetaTheme(settings) {
        val dark = isDark(settings)
        val iconStyle = remember(settings.iconShape, settings.iconPack, settings.themedIcons, dark) {
            IconStyle(settings.iconShape, settings.iconPack, settings.themedIcons, dark)
        }
        val prefersDark = rememberWallpaperPrefersDarkText()
        val darkText = when (settings.textOnWallpaper) {
            TextOnWallpaper.Auto -> prefersDark && settings.wallpaperDim < 0.2f
            TextOnWallpaper.Light -> false
            TextOnWallpaper.Dark -> true
        }
        val onWallpaper = remember(darkText) {
            // White text gets a shadow plus a soft dark scrim (the wallpaper's light parts would wash it out); dark text
            // a faint light scrim for the same reason on busy light wallpapers.
            if (darkText) OnWallpaper(Color(0xFF1B1B1F), Color(0xFF1B1B1F).copy(alpha = 0.8f), null, Color.White.copy(alpha = 0.28f))
            else OnWallpaper(Color.White, Color.White.copy(alpha = 0.9f), Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1.5f), 10f), Color.Black.copy(alpha = 0.45f))
        }

        // Warm icons for the whole drawer as soon as the list or style is known; home and dock first.
        val apps by graph.apps.visibleApps.collectAsStateWithLifecycle()
        LaunchedEffect(apps, iconStyle) {
            val s = graph.settings.value
            val firstKeys = com.mali.nbeta.data.Layout.appKeys(s)
            val (first, rest) = apps.partition { it.key in firstKeys }
            graph.icons.prewarm(first, iconStyle)
            graph.icons.prewarm(rest, iconStyle)
        }

        CompositionLocalProvider(
            LocalGraph provides graph,
            LocalDragDrop provides c.dnd,
            com.mali.nbeta.ui.common.LocalIconOverrides provides settings.iconOverrides,
            LocalIconStyle provides iconStyle,
            LocalDots provides if (settings.notificationDots) dots else emptyMap(),
            com.mali.nbeta.ui.common.LocalDotCounts provides settings.notificationCounts,
            LocalOnWallpaper provides onWallpaper,
        ) {
            val feedOn = settings.feedEnabled
            val feedOffset = if (feedOn) 1 else 0
            val homePage = feedOffset
            val homeCount = settings.homePages.size
            // Re-created when the feed is toggled so the page index never points past the end.
            // While dragging, one extra empty page waits at the end so items can be dropped onto a new page.
            val pager = key(feedOn) { rememberPagerState(initialPage = homePage) { feedOffset + settings.homePages.size + if (c.dnd.active) 1 else 0 } }
            val scope = rememberCoroutineScope()
            val drawerClosed by remember { derivedStateOf { c.drawer.isClosed } }
            val onFeed by remember(feedOn) { derivedStateOf { feedOn && pager.currentPage == 0 } }

            LaunchedEffect(pager, feedOffset) {
                snapshotFlow { pager.currentPage }.collect { c.currentHomePage = it - feedOffset }
            }

            // Hold an item at the screen edge to move it to the neighbouring page.
            val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
            LaunchedEffect(c.dnd.active, pager, feedOffset, rtl) {
                if (!c.dnd.active) return@LaunchedEffect
                val edge = c.activity.resources.displayMetrics.widthPixels * 0.07f
                val width = c.activity.resources.displayMetrics.widthPixels
                var dwell = 0
                var dir = 0
                while (true) {
                    delay(100)
                    c.dnd.tick()
                    val x = c.dnd.pointer.x
                    // The pager mirrors in right-to-left layouts: the left edge leads to the next page there.
                    val towardsStart = if (rtl) x > width - edge else x < edge
                    val towardsEnd = if (rtl) x < edge else x > width - edge
                    val next = when {
                        towardsStart && pager.currentPage > feedOffset -> -1
                        towardsEnd && pager.currentPage < pager.pageCount - 1 -> 1
                        else -> 0
                    }
                    if (next != 0 && next == dir) dwell++ else dwell = 0
                    dir = next
                    if (dwell >= 6) {
                        dwell = 0
                        pager.animateScrollToPage(pager.currentPage + dir)
                    }
                }
            }

            // Status bar icons follow whatever is under them: wallpaper text colour on home, theme on drawer/feed.
            val view = LocalView.current
            val window = c.activity.window
            LaunchedEffect(darkText, dark, feedOn, settings.hideStatusBar) {
                snapshotFlow { c.drawer.progress > 0.5f || (feedOn && pager.currentPage == 0) }
                    .distinctUntilChanged()
                    .collect { onSurface ->
                        val light = if (onSurface) !dark else darkText
                        WindowCompat.getInsetsController(window, view).apply {
                            isAppearanceLightStatusBars = light
                            isAppearanceLightNavigationBars = light
                            // Hidden only on the home pages; a swipe from the top still reveals it briefly.
                            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            if (settings.hideStatusBar && !onSurface) hide(WindowInsetsCompat.Type.statusBars())
                            else show(WindowInsetsCompat.Type.statusBars())
                        }
                    }
            }

            LaunchedEffect(pager, homePage) {
                c.homeEvents.collect { animate ->
                    if (animate) {
                        if (!c.drawer.isClosed) c.drawer.close()
                        else if (pager.currentPage != homePage) pager.animateScrollToPage(homePage)
                    } else {
                        c.drawer.snapClosed()
                        c.query = ""
                        pager.scrollToPage(homePage)
                    }
                }
            }

            BackHandler(enabled = !drawerClosed) { c.drawer.close() }
            BackHandler(enabled = drawerClosed && onFeed) { scope.launch { pager.animateScrollToPage(homePage) } }

            Box(
                Modifier
                    .fillMaxSize()
                    .dragHost(c.dnd)
                    .drawBehind {
                        if (settings.wallpaperDim > 0f) drawRect(Color.Black.copy(alpha = settings.wallpaperDim))
                    },
            ) {
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = drawerClosed && !c.dnd.active,
                    beyondViewportPageCount = 0,
                    key = { it },
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    if (feedOn && page == 0) {
                        FeedPage(c, active = onFeed)
                    } else {
                        HomePageContent(c, settings, page - feedOffset)
                    }
                }
                DockArea(
                    c, settings,
                    pageCount = homeCount + if (c.dnd.active) 1 else 0,
                    position = { pager.currentPage + pager.currentPageOffsetFraction },
                    firstHomePage = homePage,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                AppDrawer(c, settings)
                FolderOverlay(c, settings)
                DragOverlay(c.dnd, settings.iconSizeDp.dp)
            }

            c.menu?.let { AppMenuPopup(c, it) }
            if (c.homeMenu) HomeMenuSheet(c)
            c.widgetPicker?.let { WidgetPickerSheet(c, it) }
            c.widgetMenu?.let { WidgetMenuSheet(c, it) }
            c.renameTarget?.let { RenameDialog(c, it) }
            c.iconPickerFor?.let { com.mali.nbeta.ui.menu.IconPickerSheet(c, it) }
        }
    }
}
