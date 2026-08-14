package com.unarchive.android.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechSegmentPlannerTest {
    @Test
    fun addsBoundedContextAndMergesOverlaps() {
        val result = SpeechSegmentPlanner.withContext(
            speechRanges = listOf(
                SpeechSampleRange(1_000, 2_000),
                SpeechSampleRange(2_500, 3_000),
            ),
            totalSamples = 4_000,
            contextSamples = 500,
            maximumSegmentSamples = 10_000,
        )

        assertEquals(listOf(SpeechSampleRange(500, 3_500)), result)
    }

    @Test
    fun clampsContextToAudioBounds() {
        val result = SpeechSegmentPlanner.withContext(
            speechRanges = listOf(SpeechSampleRange(100, 900)),
            totalSamples = 1_000,
            contextSamples = 500,
            maximumSegmentSamples = 10_000,
        )

        assertEquals(listOf(SpeechSampleRange(0, 1_000)), result)
    }

    @Test
    fun splitsMergedRangesAtMaximumLength() {
        val result = SpeechSegmentPlanner.withContext(
            speechRanges = listOf(SpeechSampleRange(0, 25)),
            totalSamples = 25,
            contextSamples = 0,
            maximumSegmentSamples = 10,
        )

        assertEquals(
            listOf(
                SpeechSampleRange(0, 10),
                SpeechSampleRange(10, 20),
                SpeechSampleRange(20, 25),
            ),
            result,
        )
    }
}
