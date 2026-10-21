package com.unarchive.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

internal val LocalChromeBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

internal val LocalDockClearance = staticCompositionLocalOf { 0.dp }

/** Records only page content. The dock samples this layer without capturing itself. */
@Composable
internal fun GlassScaffold(
    bottomBar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    var dockHeight by remember { mutableStateOf(0) }
    val clearance = with(LocalDensity.current) { dockHeight.toDp() }
    Box(Modifier.fillMaxSize().testTag("glass-scaffold")) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // Nested status cards and sheets use an opaque surface to avoid recursive sampling.
            // RootPage adds the measured Dock and navigation-safe area to scroll end padding.
            CompositionLocalProvider(LocalChromeBackdrop provides null, LocalDockClearance provides clearance) { content() }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { dockHeight = it.height }
                .testTag("dock-host"),
        ) {
            CompositionLocalProvider(LocalChromeBackdrop provides backdrop) { bottomBar() }
        }
    }
}
