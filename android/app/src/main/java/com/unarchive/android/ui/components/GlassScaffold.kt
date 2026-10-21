package com.unarchive.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

internal val LocalChromeBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** Records only page content. The dock samples this layer without capturing itself. */
@Composable
internal fun GlassScaffold(
    bottomBar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // Nested status cards and sheets use an opaque surface to avoid recursive sampling.
            // Pages own their readable end padding so the last row can scroll clear of the dock.
            CompositionLocalProvider(LocalChromeBackdrop provides null) { content() }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxSize(),
        ) {
            CompositionLocalProvider(LocalChromeBackdrop provides backdrop) { bottomBar() }
        }
    }
}
