package com.unarchive.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPoliciesTest {
    @Test
    fun disabledUserSettingUsesOpaqueFallback() {
        val policy = LiquidGlassPolicy.resolve(LiquidGlassInputs(userEnabled = false, apiLevel = 35))
        assertEquals(LiquidGlassMode.Disabled, policy.mode)
        assertFalse(policy.usesTransparency)
        assertFalse(policy.isDecorativeEnabled)
    }

    @Test
    fun accessibilitySettingsAlwaysUseOpaqueFallback() {
        val reduced = LiquidGlassPolicy.resolve(
            LiquidGlassInputs(userEnabled = true, apiLevel = 35, reducedTransparency = true),
        )
        val contrast = LiquidGlassPolicy.resolve(
            LiquidGlassInputs(userEnabled = true, apiLevel = 35, highContrast = true),
        )
        assertEquals(LiquidGlassMode.Disabled, reduced.mode)
        assertEquals(LiquidGlassMode.Disabled, contrast.mode)
        assertFalse(reduced.usesTransparency)
        assertFalse(contrast.usesTransparency)
    }

    @Test
    fun constrainedDeviceKeepsProjectOwnedDecorationWithoutTransparency() {
        val policy = LiquidGlassPolicy.resolve(
            LiquidGlassInputs(userEnabled = true, apiLevel = 26, performanceTier = PerformanceTier.Constrained),
        )
        assertEquals(LiquidGlassMode.Simulated, policy.mode)
        assertTrue(policy.isDecorativeEnabled)
        assertFalse(policy.usesTransparency)
    }

    @Test
    fun apiBelow31UsesOpaqueSimulation() {
        val policy = LiquidGlassPolicy.resolve(LiquidGlassInputs(userEnabled = true, apiLevel = 30))
        assertEquals(LiquidGlassMode.Simulated, policy.mode)
        assertFalse(policy.usesTransparency)
    }

    @Test
    fun normalDeviceUsesSimulatedModeUntilAdapterIsAccepted() {
        val policy = LiquidGlassPolicy.resolve(LiquidGlassInputs(userEnabled = true, apiLevel = 35))
        assertEquals(LiquidGlassMode.Simulated, policy.mode)
        assertTrue(policy.usesTransparency)
    }

    @Test
    fun reducedMotionHasShorterBoundedDurations() {
        assertEquals(MotionMode.Reduced, MotionPolicy.Reduced.mode)
        assertTrue(MotionPolicy.Reduced.shortDurationMillis < MotionPolicy().shortDurationMillis)
        assertTrue(MotionPolicy.Reduced.standardDurationMillis < MotionPolicy().standardDurationMillis)
        assertFalse(MotionPolicy.Reduced.animateLayout)
        assertEquals(0f, MotionPolicy.Reduced.pageTravelFraction)
        assertEquals(0f, MotionPolicy.Reduced.stateTravelDp)
        assertEquals(1f, MotionPolicy.Reduced.stateInitialAlpha)
    }

    @Test
    fun systemRemoveAnimationsSelectsStaticSpatialPolicy() {
        assertEquals(MotionPolicy.Reduced, MotionPolicy.fromAnimatorDurationScale(0f))
        assertEquals(MotionMode.Full, MotionPolicy.fromAnimatorDurationScale(0.5f).mode)
        assertEquals(MotionMode.Full, MotionPolicy.fromAnimatorDurationScale(2f).mode)
    }
}
