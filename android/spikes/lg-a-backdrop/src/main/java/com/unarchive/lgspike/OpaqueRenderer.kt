package com.unarchive.lgspike

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal object OpaqueRenderer : BackdropRenderer {
    override val candidateAvailable = false
    @Composable override fun Background(modifier: Modifier, content: @Composable () -> Unit) { Box(modifier) { content() } }
    @Composable override fun Surface(spec: SurfaceSpec, mode: SampleMode, content: @Composable () -> Unit) {
        Surface(
            modifier = spec.modifier, shape = spec.shape, color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = if (spec.emphasized && mode != SampleMode.Opaque) 2.dp else 0.dp,
        ) { Box(Modifier.padding(spec.padding)) { content() } }
    }
}
