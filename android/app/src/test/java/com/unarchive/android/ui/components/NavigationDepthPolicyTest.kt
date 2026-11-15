package com.unarchive.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationDepthPolicyTest {
    @Test
    fun shadowFadesThroughLastTwelvePercentAndIsGoneAtEndpoint() {
        assertEquals(1f, NavigationDepthPolicy.shadowAlpha(500f, 1000f), 0f)
        assertEquals(1f, NavigationDepthPolicy.shadowAlpha(880f, 1000f), 0.001f)
        assertEquals(0.5f, NavigationDepthPolicy.shadowAlpha(940f, 1000f), 0.001f)
        assertEquals(0f, NavigationDepthPolicy.shadowAlpha(1000f, 1000f), 0f)
        assertEquals(0f, NavigationDepthPolicy.shadowAlpha(1100f, 1000f), 0f)
        val alphas = (880..1000 step 5).map { NavigationDepthPolicy.shadowAlpha(it.toFloat(), 1000f) }
        assertTrue(alphas.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test
    fun stationaryOrInvalidPageHasNoShadow() {
        assertEquals(0f, NavigationDepthPolicy.shadowAlpha(0f, 1000f), 0f)
        assertEquals(0f, NavigationDepthPolicy.shadowAlpha(-100f, 1000f), 0f)
        assertEquals(0f, NavigationDepthPolicy.shadowAlpha(50f, 0f), 0f)
    }
}
