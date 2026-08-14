package com.unarchive.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressBehaviorTest {
    @Test
    fun advancesWithoutMovingBackward() {
        assertEquals(0.5f, advanceProgress(0.18f, 0.5f))
        assertEquals(0.5f, advanceProgress(0.5f, 0.2f))
    }

    @Test
    fun clampsProviderProgressToValidBounds() {
        assertEquals(0f, advanceProgress(0f, -1f))
        assertEquals(1f, advanceProgress(0.8f, 2f))
    }
}
