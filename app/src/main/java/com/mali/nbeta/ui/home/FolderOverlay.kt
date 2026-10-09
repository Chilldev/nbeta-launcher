package com.mali.nbeta.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mali.nbeta.data.Container
import com.mali.nbeta.data.Layout
import com.mali.nbeta.data.LauncherSettings
import com.mali.nbeta.ui.LauncherController
import com.mali.nbeta.ui.common.surfaceLabelStyle

/**
 * An open folder, drawn in the launcher's own window so a long-press-drag can carry an app out of it: while dragging,
 * the overlay turns invisible (its tile still owns the gesture) and closes when the drag ends.
 */
@Composable
fun FolderOverlay(c: LauncherController, settings: LauncherSettings) {
    val id = c.openFolder ?: return
    val folder = Layout.folder(settings, id)
    if (folder == null) {
        LaunchedEffect(id) { c.openFolder = null }
        return
    }
    val dragging = c.dnd.active
    var draggedOut by remember(id) { mutableStateOf(false) }
    LaunchedEffect(dragging) {
        if (dragging) draggedOut = true
        else if (draggedOut) c.openFolder = null
    }
    BackHandler(enabled = !dragging) { c.openFolder = null }
    val focus = LocalFocusManager.current
    val appear = remember(id) { MutableTransitionState(false).apply { targetState = true } }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (dragging) 0f else 1f }
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(id) { detectTapGestures { c.openFolder = null } },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        AnimatedVisibility(appear, enter = fadeIn(spring(stiffness = 1500f)) + scaleIn(spring(dampingRatio = 0.8f, stiffness = 1100f), 0.9f)) {
            Column(
                Modifier
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }
                    .padding(horizontal = 12.dp, vertical = 18.dp),
            ) {
                var name by remember(id) { mutableStateOf(folder.name) }
                val save = {
                    if (name.isNotBlank() && name != folder.name) c.graph.settings.update { Layout.renameFolder(it, id, name) }
                }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save(); focus.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .onFocusChanged { if (!it.isFocused) save() },
                )
                ItemGrid(
                    c, Container.Folder(id), folder.items, 4, settings.iconSizeDp.dp, true, surfaceLabelStyle(),
                    acceptsDrops = false,
                )
            }
        }
        }
    }
}
