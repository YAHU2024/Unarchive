package com.unarchive.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A predictable frosted-surface fallback. It intentionally has no blur
 * dependency; a future blur implementation can stay behind this component.
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
    Surface(
        modifier = modifier.border(
            BorderStroke(
                width = 1.dp,
                color = colors.outlineVariant.copy(alpha = if (emphasized) 0.9f else 0.62f),
            ),
            shape,
        ),
        shape = shape,
        color = if (emphasized) colors.surface else colors.surface.copy(alpha = 0.86f),
        tonalElevation = if (emphasized) 2.dp else 0.dp,
        content = { Box(modifier = Modifier.padding(contentPadding)) { content() } },
    )
}
