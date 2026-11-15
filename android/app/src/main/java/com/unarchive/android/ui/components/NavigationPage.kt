package com.unarchive.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.theme.LocalMotionPolicy

/** Opaque page content, with a bounded scrim below and a soft shadow outside its leading edge. */
@Composable
internal fun NavigationPage(route: String, entryId: String, content: @Composable () -> Unit) {
    val motion = LocalMotionPolicy.current
    val systemForeground = LocalSystemBackForeground.current
    var left by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxSize()
            .then(if (systemForeground != null && systemForeground != entryId) {
                Modifier.clearAndSetSemantics { }
            } else Modifier)
            .preparedAutomaticPage(entryId)
            .onPlaced { left = it.positionInRoot().x }
            .drawWithCache {
                // Cache shader/geometry. Read placement only in drawing, so motion does
                // not rebuild the cache, recompose content, or introduce a layout loop.
                val shadowWidth = 24.dp.toPx()
                val shadowBrush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f)),
                    startX = -shadowWidth, endX = 0f,
                )
                val shadowSize = Size(shadowWidth, size.height)
                onDrawWithContent {
                    val offset = left
                    if (motion.animateLayout && offset > 0.5f) {
                        drawRect(
                            shadowBrush,
                            topLeft = Offset(-shadowWidth, 0f),
                            size = shadowSize,
                            alpha = NavigationDepthPolicy.shadowAlpha(offset, size.width),
                        )
                    }
                    drawContent()
                    if (motion.animateLayout && offset < -0.5f) {
                        val depth = (-offset / (size.width * motion.secondaryPageParallaxFraction).coerceAtLeast(1f))
                            .coerceIn(0f, 1f)
                        drawRect(Color.Black.copy(alpha = 0.10f * depth))
                    }
                }
            }
            .background(MaterialTheme.colorScheme.background)
            .testTag("navigation-page-$route"),
    ) { content() }
}

internal object NavigationDepthPolicy {
    /** Disappear before the page is removed, including the synthetic endpoint hold. */
    fun shadowAlpha(left: Float, width: Float): Float {
        if (width <= 0f || left <= 0f) return 0f
        return ((1f - left / width) / 0.12f).coerceIn(0f, 1f)
    }
}
