package com.unarchive.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.theme.LocalLiquidGlassPolicy
import com.unarchive.android.ui.theme.LocalLiquidGlassTokens

/**
 * A project-owned liquid-glass surface.
 *
 * The effect is deliberately bounded to decorative surfaces. It uses a
 * translucent tonal layer, a pair of soft specular highlights and a fine
 * outline instead of blurring the content behind the surface. This keeps
 * scrolling text, Markdown and logs readable on every API level. When the
 * user disables the effect, the same component becomes an opaque surface.
 */
@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val glassPolicy = LocalLiquidGlassPolicy.current
    val liquidGlassEnabled = glassPolicy.isDecorativeEnabled
    val tokens = LocalLiquidGlassTokens.current
    val fill = if (glassPolicy.usesTransparency) {
        colors.surface.copy(alpha = if (emphasized) tokens.emphasizedSurfaceAlpha else tokens.surfaceAlpha)
    } else {
        colors.surface
    }
    Surface(
        modifier = modifier.border(
            BorderStroke(
                width = 1.dp,
                color = colors.outlineVariant.copy(alpha = if (liquidGlassEnabled) {
                    if (emphasized) tokens.emphasizedBorderAlpha else tokens.borderAlpha
                } else 1f),
            ),
            shape,
        ),
        shape = shape,
        color = fill,
        tonalElevation = if (emphasized) 2.dp else 0.dp,
        content = {
            LiquidGlassDecoration(
                enabled = glassPolicy.usesTransparency,
                emphasized = emphasized,
                shape = shape,
            ) { Box(modifier = Modifier.padding(contentPadding)) { content() } }
        },
    )
}

@Composable
private fun LiquidGlassDecoration(
    enabled: Boolean,
    emphasized: Boolean,
    shape: CornerBasedShape,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tokens = LocalLiquidGlassTokens.current
    val modifier = if (enabled) {
        Modifier
            .clip(shape)
            .drawWithCache {
                val highlight = Brush.linearGradient(
                    colors = listOf(
                        colors.primary.copy(alpha = if (emphasized) tokens.emphasizedHighlightAlpha else tokens.highlightAlpha),
                        Color.Transparent,
                        colors.tertiary.copy(alpha = tokens.tertiaryHighlightAlpha),
                    ),
                )
                val sheen = Brush.radialGradient(
                    colors = listOf(colors.onSurface.copy(alpha = tokens.sheenAlpha), Color.Transparent),
                    radius = size.maxDimension * 0.72f,
                )
                onDrawWithContent {
                    drawRect(highlight)
                    drawRect(sheen)
                    drawContent()
                }
            }
    } else {
        Modifier.clip(shape)
    }
    Box(modifier = modifier) { content() }
}
