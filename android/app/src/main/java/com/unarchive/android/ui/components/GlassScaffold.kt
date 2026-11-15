package com.unarchive.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

internal val LocalChromeBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
internal val LocalDockClearance = staticCompositionLocalOf { 0.dp }

/** Dock geometry comes from the same tokens as GlassDock; no subcomposition in first draw. */
@Composable
internal fun GlassScaffold(bottomBar: @Composable () -> Unit, content: @Composable () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    val canvas = MaterialTheme.colorScheme.background
    // 12dp surface content padding + 16dp outer vertical padding, plus safe bottom.
    val clearance = dockItemHeight() + 28.dp + with(LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    Box(Modifier.fillMaxSize().imePadding().background(canvas).testTag("glass-scaffold")) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(canvas)) {
            CompositionLocalProvider(LocalChromeBackdrop provides null, LocalDockClearance provides clearance) {
                Box(Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    content()
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).testTag("dock-host")) {
            CompositionLocalProvider(LocalChromeBackdrop provides backdrop) { bottomBar() }
        }
    }
}
