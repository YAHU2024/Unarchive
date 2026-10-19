package com.unarchive.lgspike

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class SampleMode { Opaque, Simulated, Blur, Lens }

internal data class SampleInputs(
    val api: Int,
    val enabled: Boolean = true,
    val reducedTransparency: Boolean = false,
    val highContrast: Boolean = false,
    val constrained: Boolean = false,
    val candidateAvailable: Boolean = false,
    val lensOptIn: Boolean = false,
)

internal fun sampleMode(inputs: SampleInputs): SampleMode = with(inputs) {
    when {
        !enabled || reducedTransparency || highContrast -> SampleMode.Opaque
        constrained || api < 31 -> SampleMode.Simulated
        !candidateAvailable -> SampleMode.Simulated
        api >= 33 && lensOptIn -> SampleMode.Lens
        else -> SampleMode.Blur
    }
}

/** Only project-owned parameters cross this boundary; background scopes are renderer-owned. */
internal data class SurfaceSpec(
    val modifier: Modifier = Modifier,
    val shape: CornerBasedShape = RoundedCornerShape(20.dp),
    val padding: PaddingValues = PaddingValues(16.dp),
    val emphasized: Boolean = false,
    val allowTransparency: Boolean = true,
    val backgroundScope: String? = null,
)

internal interface BackdropRenderer {
    val candidateAvailable: Boolean
    @Composable fun Background(modifier: Modifier, content: @Composable () -> Unit)
    @Composable fun Surface(spec: SurfaceSpec, mode: SampleMode, content: @Composable () -> Unit)
}
