package com.unarchive.android.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Selected Lucide 0.468.0 paths, licensed under ISC; see assets/licenses/Lucide.txt. */
internal object AppIcons {
    val Create = icon("pencil", "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z", "m15 5 4 4")
    val Notes = icon("notebook-text", "M2 6h4M2 10h4M2 14h4M2 18h4", "M6 2h12a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M9.5 8h5M9.5 12H16M9.5 16H14")
    val Graph = icon("network", "M17 16h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1z", "M3 16h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1z", "M10 2h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z", "M5 16v-3a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3M12 12V8")
    val Me = icon("user-round", "M17 8a5 5 0 1 1-10 0a5 5 0 1 1 10 0", "M20 21a8 8 0 0 0-16 0")
    val Back = icon("chevron-left", "m15 18-6-6 6-6", autoMirror = true)
    val Chevron = icon("chevron-right", "m9 18 6-6-6-6", autoMirror = true)
    val Check = icon("check", "M20 6 9 17l-5-5")
    val Info = icon("info", "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0", "M12 16v-4M12 8h.01")
    val Refresh = icon("refresh-cw", "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16M8 16H3v5")
    val More = icon("ellipsis", "M5 12h.01M12 12h.01M19 12h.01")
    val Share = icon("share-2", "M21 5a3 3 0 1 1-6 0a3 3 0 1 1 6 0", "M9 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0", "M21 19a3 3 0 1 1-6 0a3 3 0 1 1 6 0", "m8.59 13.51 6.83 3.98m-.01-10.98-6.82 3.98")

    private fun icon(name: String, vararg paths: String, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror).apply {
            paths.forEach { data ->
                addPath(
                    pathData = addPathNodes(data), pathFillType = PathFillType.NonZero,
                    stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
