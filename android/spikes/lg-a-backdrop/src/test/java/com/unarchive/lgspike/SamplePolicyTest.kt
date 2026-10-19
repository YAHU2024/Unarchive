package com.unarchive.lgspike

import org.junit.Assert.assertEquals
import org.junit.Test

class SamplePolicyTest {
    @Test fun olderPlatformsAndUnavailableCandidateNeverSelectNativeEffects() {
        for (api in 26..30) assertEquals(SampleMode.Simulated, sampleMode(SampleInputs(api, candidateAvailable = true, lensOptIn = true)))
        for (api in 31..37) assertEquals(SampleMode.Simulated, sampleMode(SampleInputs(api)))
    }
    @Test fun renderEffectAndLensHaveSeparateOptInBoundaries() {
        for (api in 31..32) assertEquals(SampleMode.Blur, sampleMode(SampleInputs(api, candidateAvailable = true, lensOptIn = true)))
        assertEquals(SampleMode.Blur, sampleMode(SampleInputs(33, candidateAvailable = true)))
        assertEquals(SampleMode.Lens, sampleMode(SampleInputs(33, candidateAvailable = true, lensOptIn = true)))
    }
    @Test fun accessibilityAndUserOverrideAlwaysWin() {
        val baseline = SampleInputs(35, candidateAvailable = true, lensOptIn = true)
        listOf(baseline.copy(enabled = false), baseline.copy(reducedTransparency = true), baseline.copy(highContrast = true)).forEach {
            assertEquals(SampleMode.Opaque, sampleMode(it))
        }
        assertEquals(SampleMode.Simulated, sampleMode(baseline.copy(constrained = true)))
    }
}
