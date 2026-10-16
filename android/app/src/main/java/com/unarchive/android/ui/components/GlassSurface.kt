package com.unarchive.android.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.theme.LocalGlassRenderer

/**
 * The single material entry point for bounded decorative surfaces.
 *
 * Pages and feature components provide content and semantic emphasis only. The
 * renderer selected by the theme owns the visual implementation and its
 * opaque fallback, so a future blur or liquid-glass adapter cannot leak into
 * business presentation code.
 */
@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    shape: CornerBasedShape? = null,
    shadowElevation: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    LocalGlassRenderer.current.render(
        spec = GlassSurfaceSpec(
            modifier = modifier,
            shape = shape ?: MaterialTheme.shapes.medium,
            emphasized = emphasized,
            contentPadding = contentPadding,
            shadowElevation = shadowElevation,
        ),
        content = content,
    )
}
