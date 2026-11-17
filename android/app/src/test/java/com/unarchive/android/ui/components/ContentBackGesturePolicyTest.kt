package com.unarchive.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBackGesturePolicyTest {
    @Test
    fun activationUsesShortDistanceAndPauseOrSmallJitterKeepsCommitIntent() {
        val intent = ContentBackGestureIntent(activation = 72f, reversal = 28f)
        intent.update(71f)
        assertFalse(intent.shouldCommit)
        intent.update(72f)
        assertTrue(intent.shouldCommit)
        intent.update(100f)
        intent.update(92f)
        intent.update(92f)
        assertTrue(intent.shouldCommit)
    }

    @Test
    fun clearReversalCancelsAndFreshRightMovementCanRestoreIntent() {
        val intent = ContentBackGestureIntent(72f, 28f)
        intent.update(100f)
        intent.update(73f)
        assertTrue(intent.shouldCommit)
        intent.update(72f)
        assertFalse(intent.shouldCommit)
        intent.update(60f)
        intent.update(80f)
        assertFalse(intent.shouldCommit)
        intent.update(88f)
        assertTrue(intent.shouldCommit)
        intent.update(0f)
        assertFalse(intent.shouldCommit)
    }

    @Test
    fun intentIsIndependentOfScreenWidthAndVisualProgressClamps() {
        assertEquals(0.6f, ContentBackGesturePolicy.progress(600f, 1000f), 0.001f)
        assertEquals(0f, ContentBackGesturePolicy.progress(-100f, 1000f), 0f)
        assertEquals(1f, ContentBackGesturePolicy.progress(1200f, 1000f), 0f)
        assertEquals(72f, ContentBackGesturePolicy.ActivationDp, 0f)
        assertEquals(28f, ContentBackGesturePolicy.ReversalDp, 0f)
    }

    @Test
    fun settleUsesRemainingDistanceAndReleaseVelocityWithinBoundedTime() {
        val slow = ContentBackGesturePolicy.settleDuration(0.3f, 1f, 0f, 280)
        val fast = ContentBackGesturePolicy.settleDuration(0.3f, 1f, 8f, 280)
        assertTrue(fast < slow)
        assertTrue(fast >= 100)
        assertTrue(slow <= 280)
        assertEquals(0, ContentBackGesturePolicy.settleDuration(0.3f, 1f, 0f, 0))
    }
}
