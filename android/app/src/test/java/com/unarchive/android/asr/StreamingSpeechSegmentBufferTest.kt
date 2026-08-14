package com.unarchive.android.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingSpeechSegmentBufferTest {
    @Test
    fun padsAndMergesAdjacentSpeechLikeBatchPlanner() {
        val emitted = mutableListOf<BufferedSpeechSegment>()
        val buffer = buffer(context = 5, maximum = 30, history = 8, emitted = emitted)
        buffer.append(samples(0, 8))
        buffer.updateSpeechActive(true)
        buffer.append(samples(8, 30))
        buffer.addSpeechRange(10, 15)
        buffer.addSpeechRange(20, 25)
        buffer.updateSpeechActive(false)
        buffer.append(samples(30, 40))

        assertEquals(listOf(5L to 30L), emitted.map { it.startSample to it.endSample })
        assertArrayEquals(samples(5, 30), emitted.single().samples, 0f)
    }

    @Test
    fun splitsContinuousSpeechAtMaximumAndReleasesCompletedAudio() {
        val emitted = mutableListOf<BufferedSpeechSegment>()
        val buffer = buffer(context = 2, maximum = 10, history = 4, emitted = emitted)
        buffer.updateSpeechActive(true)
        buffer.append(samples(0, 12))
        buffer.addSpeechRange(2, 20)
        buffer.append(samples(12, 25))
        buffer.updateSpeechActive(false)
        buffer.finish()

        assertEquals(
            listOf(0L to 10L, 10L to 20L, 20L to 22L),
            emitted.map { it.startSample to it.endSample },
        )
        assertTrue(emitted.all { it.samples.size <= 10 })
    }

    @Test
    fun retainsOnlyHistoryDuringLongSilence() {
        val emitted = mutableListOf<BufferedSpeechSegment>()
        val buffer = buffer(context = 5, maximum = 30, history = 8, emitted = emitted)
        repeat(1_000) { index ->
            buffer.append(floatArrayOf(index.toFloat()))
            buffer.updateSpeechActive(false)
        }

        assertTrue(buffer.maximumRetainedSamples <= 9)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun preservesPreSpeechHistoryUntilVadRangeArrives() {
        val emitted = mutableListOf<BufferedSpeechSegment>()
        val buffer = buffer(context = 5, maximum = 30, history = 8, emitted = emitted)
        buffer.append(samples(0, 20))
        buffer.updateSpeechActive(true)
        buffer.append(samples(20, 30))
        buffer.addSpeechRange(18, 27)
        buffer.updateSpeechActive(false)
        buffer.append(samples(30, 40))

        assertEquals(13L to 32L, emitted.single().startSample to emitted.single().endSample)
        assertArrayEquals(samples(13, 32), emitted.single().samples, 0f)
    }

    @Test
    fun clampsTrailingContextAtEndOfStream() {
        val emitted = mutableListOf<BufferedSpeechSegment>()
        val buffer = buffer(context = 5, maximum = 30, history = 8, emitted = emitted)
        buffer.append(samples(0, 10))
        buffer.updateSpeechActive(true)
        buffer.append(samples(10, 20))
        buffer.addSpeechRange(15, 20)
        buffer.finish()

        assertEquals(10L to 20L, emitted.single().startSample to emitted.single().endSample)
    }

    @Test
    fun noVadContinuousChunksRemainAvailableAndSplitAtMaximum() {
        val emitted = mutableListOf<BufferedSpeechSegment>()
        val buffer = buffer(context = 0, maximum = 10, history = 0, emitted = emitted)
        listOf(0 to 4, 4 to 9, 9 to 14, 14 to 18).forEach { (start, end) ->
            buffer.updateSpeechActive(true)
            buffer.append(samples(start, end))
            buffer.addSpeechRange(start.toLong(), end.toLong())
        }
        buffer.updateSpeechActive(false)
        buffer.finish()

        assertEquals(listOf(0L to 10L, 10L to 18L), emitted.map { it.startSample to it.endSample })
        assertArrayEquals(samples(0, 10), emitted[0].samples, 0f)
        assertArrayEquals(samples(10, 18), emitted[1].samples, 0f)
    }

    @Test
    fun delayedVadRangeCanReachBackAcrossWindowedUpstreamChunk() {
        val emitted = mutableListOf<BufferedSpeechSegment>()
        val buffer = buffer(context = 5, maximum = 40, history = 20, emitted = emitted)
        buffer.updateSpeechActive(false)
        buffer.append(samples(0, 10))
        buffer.updateSpeechActive(false)
        buffer.append(samples(10, 20))
        buffer.updateSpeechActive(true)
        buffer.append(samples(20, 30))
        buffer.addSpeechRange(8, 25)
        buffer.updateSpeechActive(false)
        buffer.append(samples(30, 40))

        assertEquals(3L to 30L, emitted.single().startSample to emitted.single().endSample)
        assertArrayEquals(samples(3, 30), emitted.single().samples, 0f)
    }

    @Test
    fun delayedRangeStartingBeforeRetainedAudioIsDropped() {
        val emitted = mutableListOf<BufferedSpeechSegment>()
        val buffer = buffer(context = 5, maximum = 40, history = 10, emitted = emitted)
        repeat(100) { index ->
            buffer.append(floatArrayOf(index.toFloat()))
            buffer.updateSpeechActive(false)
        }
        // totalSamples=100, retained window starts at 90. A delayed range starting
        // at 50 is fully before 90+5, so it is dropped without throwing.
        buffer.addSpeechRange(50, 95)
        buffer.updateSpeechActive(false)
        buffer.finish()

        assertTrue(emitted.isEmpty())
    }

    @Test
    fun delayedRangePartiallyBeforeRetainedAudioIsClamped() {
        val emitted = mutableListOf<BufferedSpeechSegment>()
        val buffer = buffer(context = 5, maximum = 40, history = 10, emitted = emitted)
        repeat(100) { index ->
            buffer.append(floatArrayOf(index.toFloat()))
            buffer.updateSpeechActive(false)
        }
        // totalSamples=100, retained window starts at 90. A range starting at 88
        // is clamped to 95 (earliest 90 + context 5), so it emits from 90.
        buffer.addSpeechRange(88, 110)
        buffer.append(samples(100, 115))
        buffer.updateSpeechActive(false)
        buffer.finish()

        assertTrue(emitted.isNotEmpty())
        assertTrue(emitted.all { it.startSample >= 90L })
    }

    private fun buffer(
        context: Int,
        maximum: Int,
        history: Int,
        emitted: MutableList<BufferedSpeechSegment>,
    ) = StreamingSpeechSegmentBuffer(context, maximum, history, emitted::add)

    private fun samples(start: Int, end: Int): FloatArray =
        FloatArray(end - start) { offset -> (start + offset).toFloat() }
}
