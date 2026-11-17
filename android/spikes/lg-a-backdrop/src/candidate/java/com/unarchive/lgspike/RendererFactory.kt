package com.unarchive.lgspike

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

@Composable internal fun rememberBackdropRenderer(): BackdropRenderer {
    val background = rememberLayerBackdrop()
    return remember(background) { CandidateBackdropRenderer(background) }
}

private class CandidateBackdropRenderer(private val background: LayerBackdrop) : BackdropRenderer {
    override val candidateAvailable = true
    @Composable override fun Background(modifier: Modifier, content: @Composable () -> Unit) {
        Box(modifier.layerBackdrop(background)) { content() }
    }
    @Composable override fun Surface(spec: SurfaceSpec, mode: SampleMode, content: @Composable () -> Unit) {
        if (!spec.allowTransparency || mode == SampleMode.Opaque || mode == SampleMode.Simulated) {
            OpaqueRenderer.Surface(spec, mode, content)
            return
        }
        val tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
        Box(spec.modifier.drawBackdrop(
            backdrop = background, shape = { spec.shape },
            effects = {
                blur(12.dp.toPx())
                colorControls(saturation = 1.1f)
                if (mode == SampleMode.Lens) lens(16.dp.toPx(), 0.5f, depthEffect = true)
            },
            highlight = { Highlight.Default }, shadow = { Shadow.Default },
            onDrawSurface = { drawRect(tint) },
        ).padding(spec.padding)) { content() }
    }
}
