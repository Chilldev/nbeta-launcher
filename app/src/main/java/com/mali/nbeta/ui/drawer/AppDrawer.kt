package com.mali.nbeta.ui.drawer

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mali.nbeta.data.DrawerSort
import com.mali.nbeta.data.LauncherSettings
import com.mali.nbeta.data.apps.AppEntry
import com.mali.nbeta.data.apps.ProfileKind
import com.mali.nbeta.data.search.SearchResults
import com.mali.nbeta.data.search.TextFold
import com.mali.nbeta.ui.LauncherController
import com.mali.nbeta.ui.MenuRequest
import com.mali.nbeta.ui.MenuTarget
import com.mali.nbeta.ui.Origin
import com.mali.nbeta.ui.common.AppTile
import com.mali.nbeta.ui.common.LocalGraph
import com.mali.nbeta.ui.common.surfaceLabelStyle
import com.mali.nbeta.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SheetPhase { Closed, Moving, Open }

@Composable
fun AppDrawer(c: LauncherController, settings: LauncherSettings) {
    val surface = MaterialTheme.colorScheme.surface
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val gridState = rememberLazyGridState()
    var wantFocus by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(settings)

    LaunchedEffect(c.focusSearch) { if (c.focusSearch > 0) wantFocus = true }
    LaunchedEffect(Unit) {
        snapshotFlow { c.drawer.progress }
            .map { p -> if (p >= 1f) SheetPhase.Open else if (p <= 0f) SheetPhase.Closed else SheetPhase.Moving }
            .distinctUntilChanged()
            .collect { phase ->
                when (phase) {
                    SheetPhase.Open -> if (current.autoKeyboard || wantFocus) {
                        runCatching { focus.requestFocus() }
                        keyboard?.show()
                        wantFocus = false
                    }
                    SheetPhase.Moving -> {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    }
                    // Not while an app is being dragged out: its tile owns the gesture and must stay composed.
                    SheetPhase.Closed -> if (!c.dnd.active) {
                        c.query = ""
                        gridState.scrollToItem(0)
                    }
                }
            }
    }

    LaunchedEffect(c.dnd.finished) {
        if (c.dnd.finished > 0 && c.drawer.isClosed) {
            c.query = ""
            gridState.scrollToItem(0)
        }
    }

    // Pull down at the top of the list closes the drawer; pushing up while half open opens it.
    val nested = remember(c) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y < 0 && c.drawer.progress < 1f) {
                    c.drawer.dragBy(available.y)
                    return Offset(0f, available.y)
                }
                if (source == NestedScrollSource.UserInput && available.y != 0f) keyboard?.hide()
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0) {
                    c.drawer.dragBy(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val p = c.drawer.progress
                if (p > 0f && p < 1f) {
                    c.drawer.settle(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = c.drawer.progress
                // Fully off-screen when closed so it never intercepts touches meant for home.
                translationY = if (p == 0f) size.height else (1f - p) * size.height * 0.3f
                alpha = p.coerceIn(0f, 1f)
            }
            .drawBehind { drawRect(surface.copy(alpha = settings.drawerOpacity)) }
            .nestedScroll(nested)
            .draggable(
                rememberDraggableState { c.drawer.dragBy(it) },
                Orientation.Vertical,
                onDragStopped = { c.drawer.settle(it) },
            ),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SearchField(c, focus)
            val results by produceState<SearchResults?>(null, c.query) {
                val q = c.query
                value = if (q.isBlank()) null else withContext(Dispatchers.Default) { c.graph.search.search(q) }
            }
            val r = results
            if (c.query.isBlank() || r == null) {
                DrawerApps(c, settings, gridState)
            } else {
                SearchResultsList(c, r, settings)
            }
        }
    }
}

@Composable
private fun SearchField(c: LauncherController, focus: FocusRequester) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (c.query.isEmpty()) {
                Text("Search", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
            BasicTextField(
                value = c.query,
                onValueChange = { c.query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = {
                    scope.launch {
                        val r = withContext(Dispatchers.Default) { c.graph.search.search(c.query) }
                        launchTopResult(c, r)
                    }
                }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (c.query.isNotEmpty()) {
            IconButton(onClick = { c.query = "" }) { Icon(Icons.Default.Clear, "Clear") }
        } else {
            IconButton(onClick = {
                c.start(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Icon(Icons.Default.Settings, "Launcher settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

fun launchTopResult(c: LauncherController, r: SearchResults) {
    val app = r.apps.firstOrNull()
    when {
        app != null -> c.launch(app, null)
        r.calc != null -> copyToClipboard(c, r.calc)
        r.web.isNotEmpty() -> c.start(c.graph.search.webIntent(r.web.first { it !is com.mali.nbeta.data.search.WebHit.Store }))
    }
}

@Composable
private fun DrawerApps(c: LauncherController, settings: LauncherSettings, gridState: androidx.compose.foundation.lazy.grid.LazyGridState) {
    val graph = LocalGraph.current
    val apps by graph.apps.visibleApps.collectAsStateWithLifecycle()
    val profiles by graph.apps.profiles.collectAsStateWithLifecycle()
    val stats by graph.apps.stats.flow.collectAsStateWithLifecycle()
    val workProfile = profiles.firstOrNull { it.kind == ProfileKind.Work }
    val privateProfile = profiles.firstOrNull { it.kind == ProfileKind.Private }
    var tab by remember { mutableIntStateOf(0) }
    val cols = settings.drawerColumns
    val labelStyle = surfaceLabelStyle()
    val iconSize = (settings.iconSizeDp - 4).coerceAtLeast(40).dp

    val sorted = remember(apps, settings.drawerSort, if (settings.drawerSort == DrawerSort.MostUsed) stats else null) {
        if (settings.drawerSort == DrawerSort.MostUsed) apps.sortedByDescending { stats[it.key]?.count ?: 0 } else apps
    }
    val personal = remember(sorted) { sorted.filter { it.profile == ProfileKind.Main || it.profile == ProfileKind.Clone } }
    val work = remember(sorted) { sorted.filter { it.profile == ProfileKind.Work } }
    val private = remember(sorted) { sorted.filter { it.profile == ProfileKind.Private } }
    val suggestions = remember(apps, stats) {
        if (stats.isEmpty()) emptyList()
        else personal.sortedByDescending { graph.apps.frecency(it.key) }.filter { (stats[it.key]?.count ?: 0) > 0 }.take(cols)
    }
    val showSuggestions = settings.showSuggestions && tab == 0 && suggestions.size >= cols.coerceAtMost(3)
    val list = if (tab == 0) personal else work

    Column(Modifier.fillMaxSize()) {
        if (workProfile != null) {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Personal") })
                Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Work") })
            }
        }
        val onClick: (AppEntry, com.mali.nbeta.ui.common.BoundsHolder) -> Unit = { app, b -> c.launch(app, b) }
        val onLong: (AppEntry, com.mali.nbeta.ui.common.BoundsHolder) -> Unit = { app, b ->
            b.rect()?.let { c.menu = MenuRequest(MenuTarget.App(app, Origin.Drawer), it) }
        }
        Box(Modifier.weight(1f)) {
            val headerCount = (if (showSuggestions) 1 else 0) + (if (tab == 1 && workProfile?.quiet == true) 1 else 0)
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                state = gridState,
                contentPadding = PaddingValues(start = 8.dp, end = 22.dp, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showSuggestions) {
                    item(key = "suggestions", span = { GridItemSpan(maxLineSpan) }, contentType = "suggestions") {
                        Column {
                            Row(Modifier.fillMaxWidth()) {
                                suggestions.forEach { app ->
                                    AppTile(app, iconSize, settings.drawerLabels, labelStyle, { onClick(app, it) }, { onLong(app, it) }, Modifier.weight(1f))
                                }
                                repeat(cols - suggestions.size) { Spacer(Modifier.weight(1f)) }
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
                if (tab == 1 && workProfile?.quiet == true) {
                    item(key = "work-paused", span = { GridItemSpan(maxLineSpan) }) {
                        ProfileBanner("Work apps are paused", "Resume") { c.graph.apps.setQuietMode(workProfile, false) }
                    }
                }
                val paused = tab == 1 && workProfile?.quiet == true
                items(list, key = { it.key }, contentType = { "app" }) { app ->
                    AppTile(
                        app, iconSize, settings.drawerLabels, labelStyle, { onClick(app, it) }, { onLong(app, it) },
                        modifier = if (paused) Modifier.graphicsLayer { alpha = 0.45f } else Modifier,
                    )
                }
                if (tab == 1 && workProfile != null && !workProfile.quiet) {
                    item(key = "work-pause", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            FilledTonalButton(onClick = { c.graph.apps.setQuietMode(workProfile, true) }) { Text("Pause work apps") }
                        }
                    }
                }
                if (tab == 0 && privateProfile != null) {
                    item(key = "private-header", span = { GridItemSpan(maxLineSpan) }) {
                        PrivateHeader(locked = privateProfile.quiet) { c.graph.apps.setQuietMode(privateProfile, !privateProfile.quiet) }
                    }
                    if (!privateProfile.quiet) {
                        items(private, key = { it.key }, contentType = { "app" }) { app ->
                            AppTile(app, iconSize, settings.drawerLabels, labelStyle, { onClick(app, it) }, { onLong(app, it) })
                        }
                    }
                }
            }
            if (settings.drawerSort == DrawerSort.Alphabetical && list.size > 20) {
                FastScroller(list, headerCount, gridState, Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

@Composable
private fun ProfileBanner(text: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
        FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun PrivateHeader(locked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Private", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(if (locked) "Unlock" else "Lock", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    }
}

/** Letter rail: touch or drag to jump. Section indexes are computed once per list change. */
@Composable
private fun FastScroller(
    list: List<AppEntry>,
    headerCount: Int,
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
    modifier: Modifier,
) {
    val sections = remember(list) {
        val m = LinkedHashMap<String, Int>()
        list.forEachIndexed { i, app ->
            val ch = TextFold.fold(app.label).firstOrNull { !it.isWhitespace() } ?: '#'
            val letter = if (ch.isLetter()) ch.uppercaseChar().toString() else "#"
            if (letter !in m) m[letter] = i
        }
        m.entries.toList()
    }
    if (sections.size < 4) return
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf<String?>(null) }
    var railHeight by remember { mutableIntStateOf(1) }
    // The gesture blocks below outlive recompositions; read the header offset fresh each time.
    val offset by rememberUpdatedState(headerCount)

    fun jump(y: Float) {
        val i = ((y / railHeight) * sections.size).toInt().coerceIn(0, sections.size - 1)
        val (letter, index) = sections[i]
        if (active != letter) {
            active = letter
            scope.launch { state.scrollToItem(index + offset) }
        }
    }

    Box(modifier.fillMaxHeight().padding(vertical = 24.dp)) {
        Column(
            Modifier
                .width(22.dp)
                .fillMaxHeight()
                .onSizeChanged { railHeight = it.height.coerceAtLeast(1) }
                .pointerInput(sections) {
                    detectTapGestures(onPress = { o -> jump(o.y); tryAwaitRelease(); active = null })
                }
                .pointerInput(sections) {
                    detectVerticalDragGestures(
                        onDragStart = { jump(it.y) },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                    ) { change, _ ->
                        change.consume()
                        jump(change.position.y)
                    }
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            sections.forEach { (letter, _) ->
                Text(
                    letter,
                    fontSize = 10.sp,
                    fontWeight = if (letter == active) FontWeight.Bold else FontWeight.Medium,
                    color = if (letter == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        active?.let { letter ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .offset(x = (-56).dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(letter, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}
