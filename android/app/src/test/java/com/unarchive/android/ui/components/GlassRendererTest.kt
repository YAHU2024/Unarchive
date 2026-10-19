package com.unarchive.android.ui.components

import com.unarchive.android.ui.theme.LiquidGlassMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassRendererTest {
    @Test
    fun disabledModeSelectsOpaqueRenderer() {
        assertTrue(glassRendererFor(LiquidGlassMode.Disabled, usesTransparency = true) === DisabledGlassRenderer)
    }

    @Test
    fun simulatedModePreservesTransparencyDecision() {
        val renderer = glassRendererFor(LiquidGlassMode.Simulated, usesTransparency = false)

        assertEquals(SimulatedGlassRenderer(usesTransparency = false), renderer)
    }

    @Test
    fun adapterModesSelectBackdropRenderer() {
        assertEquals(
            BackdropGlassRenderer(liquid = false),
            glassRendererFor(LiquidGlassMode.Blur, usesTransparency = true),
        )
        assertEquals(
            BackdropGlassRenderer(liquid = true),
            glassRendererFor(LiquidGlassMode.LiquidGlass, usesTransparency = false),
        )
    }
}
