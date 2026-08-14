package com.unarchive.android.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SentenceBoundaryDetectorTest {
    private val sampleRate = 16_000

    private fun tone(seconds: Double, amplitude: Float = 0.3f): FloatArray {
        val count = (seconds * sampleRate).toInt()
        return FloatArray(count) { index ->
            (amplitude * sin(2.0 * PI * 220.0 * index / sampleRate)).toFloat()
        }
    }

    private fun silence(seconds: Double): FloatArray = FloatArray((seconds * sampleRate).toInt())

    private fun concat(vararg parts: FloatArray): FloatArray {
        val total = parts.sumOf { it.size }
        val output = FloatArray(total)
        var offset = 0
        for (part in parts) {
            part.copyInto(output, offset)
            offset += part.size
        }
        return output
    }

    @Test
    fun returnsEmptyForShortOrSilentInput() {
        assertArrayEquals(IntArray(0), SentenceBoundaryDetector.findSplitSamples(tone(1.0), sampleRate))
        assertArrayEquals(IntArray(0), SentenceBoundaryDetector.findSplitSamples(silence(10.0), sampleRate))
        assertArrayEquals(IntArray(0), SentenceBoundaryDetector.findSplitSamples(FloatArray(0), sampleRate))
    }

    @Test
    fun splitsAtPauseLongerThanMinimum() {
        // 6s speech - 400ms silence - 6s speech: pause >= 250ms -> split.
        val samples = concat(tone(6.0), silence(0.4), tone(6.0))
        val boundaries = SentenceBoundaryDetector.findSplitSamples(samples, sampleRate)

        assertEquals(1, boundaries.size)
        val expected = (6.0 * sampleRate).toInt() + 0.4 / 2 * sampleRate
        assertTrue(
            "boundary $boundaries not within pause",
            kotlin.math.abs(boundaries[0] - expected) < sampleRate / 50,
        )
    }

    @Test
    fun ignoresPausesShorterThanMinimum() {
        // 6s speech - 100ms silence - 6s speech: pause < 250ms -> no split.
        val samples = concat(tone(6.0), silence(0.1), tone(6.0))
        assertArrayEquals(IntArray(0), SentenceBoundaryDetector.findSplitSamples(samples, sampleRate))
    }

    @Test
    fun respectsMinimumSubSegmentLength() {
        // 1s speech - 500ms silence - 8s speech: first sub-segment too short -> no split.
        val samples = concat(tone(1.0), silence(0.5), tone(8.0))
        assertArrayEquals(IntArray(0), SentenceBoundaryDetector.findSplitSamples(samples, sampleRate))

        // 8s speech - 500ms silence - 8s speech: both sides long enough -> split.
        val samples2 = concat(tone(8.0), silence(0.5), tone(8.0))
        assertEquals(1, SentenceBoundaryDetector.findSplitSamples(samples2, sampleRate).size)
    }

    @Test
    fun splitsMultipleSentencesInOneSegment() {
        // 5s x3 sentences with 300ms pauses: two splits, sub-segments >= 2s.
        val samples = concat(tone(5.0), silence(0.3), tone(5.0), silence(0.3), tone(5.0))
        val boundaries = SentenceBoundaryDetector.findSplitSamples(samples, sampleRate)

        assertEquals(2, boundaries.size)
        assertTrue(boundaries[0] < boundaries[1])
        assertTrue(boundaries[1] < samples.size)
    }

    @Test
    fun splitsDespiteTrailingContextPaddingSilence() {
        // 8s speech - 400ms silence - 4s speech - 500ms padding: the pause must
        // still split even though the segment ends in silence.
        val samples = concat(tone(8.0), silence(0.4), tone(4.0), silence(0.5))
        val boundaries = SentenceBoundaryDetector.findSplitSamples(samples, sampleRate)

        assertEquals(1, boundaries.size)
    }

    @Test
    fun rejectsInvalidParameters() {
        assertTrue(
            runCatching { SentenceBoundaryDetector.findSplitSamples(tone(1.0), 0) }.isFailure,
        )
        assertTrue(
            runCatching {
                SentenceBoundaryDetector.findSplitSamples(tone(1.0), sampleRate, minPauseMs = -1)
            }.isFailure,
        )
    }
}
