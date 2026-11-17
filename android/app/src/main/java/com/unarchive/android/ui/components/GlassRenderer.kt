package com.unarchive.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import android.os.Build
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.unarchive.android.ui.theme.LiquidGlassMode
import com.unarchive.android.ui.theme.LocalLiquidGlassTokens

internal data class GlassSurfaceSpec(
    val modifier: Modifier,
    val shape: CornerBasedShape,
    val emphasized: Boolean,
    val contentPadding: PaddingValues,
    val shadowElevation: Dp = 0.dp,
)

/** Project-owned material boundary. Third-party material types stay behind this interface. */
internal interface GlassRenderer {
    @Composable
    fun render(spec: GlassSurfaceSpec, content: @Composable () -> Unit)
}

internal object DisabledGlassRenderer : GlassRenderer {
    @Composable
    override fun render(spec: GlassSurfaceSpec, content: @Composable () -> Unit) {
        val colors = MaterialTheme.colorScheme
        Surface(
            modifier = spec.modifier.border(BorderStroke(1.dp, colors.outlineVariant), spec.shape),
            shape = spec.shape,
            color = colors.surface,
            tonalElevation = if (spec.emphasized) 2.dp else 0.dp,
            shadowElevation = spec.shadowElevation,
        ) { Box(Modifier.clip(spec.shape).padding(spec.contentPadding)) { content() } }
    }
}

/** Backdrop-backed renderer for API 31+ decorative Chrome surfaces. */
internal data class BackdropGlassRenderer(val liquid: Boolean) : GlassRenderer {
    @Composable
    override fun render(spec: GlassSurfaceSpec, content: @Composable () -> Unit) {
        val backdrop = LocalChromeBackdrop.current
        if (backdrop == null) {
            DisabledGlassRenderer.render(spec, content)
            return
        }
        val colors = MaterialTheme.colorScheme
        val tokens = LocalLiquidGlassTokens.current
        val fillAlpha = if (spec.emphasized) tokens.emphasizedSurfaceAlpha else tokens.surfaceAlpha
        val borderAlpha = if (spec.emphasized) tokens.emphasizedBorderAlpha else tokens.borderAlpha
        val backdropModifier = spec.modifier
            .border(BorderStroke(1.dp, colors.outlineVariant.copy(alpha = borderAlpha)), spec.shape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { spec.shape },
                effects = {
                    blur(radius = if (spec.emphasized) 22f else 16f)
                    if (liquid && Build.VERSION.SDK_INT >= 33) {
                        lens(refractionHeight = 18f, refractionAmount = 0.12f)
                    }
                },
                onDrawSurface = {
                    drawRect(colors.surface.copy(alpha = fillAlpha))
                },
            )
        Box(backdropModifier.clip(spec.shape).padding(spec.contentPadding)) { content() }
    }
}

internal data class SimulatedGlassRenderer(val usesTransparency: Boolean) : GlassRenderer {
    @Composable
    override fun render(spec: GlassSurfaceSpec, content: @Composable () -> Unit) {
        val colors = MaterialTheme.colorScheme
        val tokens = LocalLiquidGlassTokens.current
        val fill = if (usesTransparency) {
            colors.surface.copy(alpha = if (spec.emphasized) tokens.emphasizedSurfaceAlpha else tokens.surfaceAlpha)
        } else colors.surface
        Surface(
            modifier = spec.modifier.border(
                BorderStroke(
                    1.dp,
                    colors.outlineVariant.copy(alpha = if (spec.emphasized) tokens.emphasizedBorderAlpha else tokens.borderAlpha),
                ),
                spec.shape,
            ),
            shape = spec.shape,
            color = fill,
            tonalElevation = if (spec.emphasized) 2.dp else 0.dp,
            shadowElevation = spec.shadowElevation,
        ) {
            val decoration = Modifier.clip(spec.shape).drawWithCache {
                val highlight = Brush.linearGradient(
                    colors = listOf(
                        colors.primary.copy(alpha = if (spec.emphasized) tokens.emphasizedHighlightAlpha else tokens.highlightAlpha),
                        Color.Transparent,
                        colors.tertiary.copy(alpha = tokens.tertiaryHighlightAlpha),
                    ),
                )
                val sheen = Brush.radialGradient(
                    colors = listOf(colors.onSurface.copy(alpha = tokens.sheenAlpha), Color.Transparent),
                    radius = size.maxDimension * 0.72f,
                )
                onDrawWithContent {
                    if (usesTransparency) { drawRect(highlight); drawRect(sheen) }
                    drawContent()
                }
            }
            Box(decoration.padding(spec.contentPadding)) { content() }
        }
    }
}

internal fun glassRendererFor(mode: LiquidGlassMode, usesTransparency: Boolean): GlassRenderer =
    if (!usesTransparency && mode != LiquidGlassMode.Simulated) DisabledGlassRenderer else when (mode) {
        LiquidGlassMode.Disabled -> DisabledGlassRenderer
        LiquidGlassMode.Simulated -> SimulatedGlassRenderer(usesTransparency)
        LiquidGlassMode.Blur -> BackdropGlassRenderer(liquid = false)
        LiquidGlassMode.LiquidGlass -> BackdropGlassRenderer(liquid = true)
    }
