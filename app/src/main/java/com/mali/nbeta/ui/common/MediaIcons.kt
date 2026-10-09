package com.mali.nbeta.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Media transport glyphs (not part of the core Material icon set). */
object MediaIcons {
    private fun icon(name: String, autoMirror: Boolean = false, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror).apply {
            path(fill = SolidColor(Color.Black), pathBuilder = block)
        }.build()

    val Play by lazy { icon("Play", autoMirror = true) { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() } }
    val Pause by lazy {
        icon("Pause") {
            moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close()
            moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close()
        }
    }
    val Next by lazy {
        icon("Next", autoMirror = true) {
            moveTo(6f, 6f); lineTo(14.5f, 12f); lineTo(6f, 18f); close()
            moveTo(16f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(16f, 18f); close()
        }
    }
    val Previous by lazy {
        icon("Previous", autoMirror = true) {
            moveTo(18f, 6f); lineTo(9.5f, 12f); lineTo(18f, 18f); close()
            moveTo(6f, 6f); lineTo(8f, 6f); lineTo(8f, 18f); lineTo(6f, 18f); close()
        }
    }
}
