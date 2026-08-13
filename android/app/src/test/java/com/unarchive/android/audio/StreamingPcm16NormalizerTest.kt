package com.unarchive.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.ceil

class StreamingPcm16NormalizerTest {
    @Test
    fun matchesWholeArrayNormalizerAcrossFixedChunkBoundaries() {
        val cases = listOf(
            Case(channelCount = 1, sourceRate = 16_000, targetRate = 16_000),
            Case(channelCount = 2, sourceRate = 48_000, targetRate = 16_000),
            Case(channelCount = 1, sourceRate = 8_000, targetRate = 16_000),
            Case(channelCount = 2, sourceRate = 44_100, targetRate = 16_000),
        )
        val samples = ShortArray(2_003) { index ->
            ((index * 7_919L + 1_237L) % 65_536L - 32_768L).toShort()
        }

        cases.forEach { case ->
            val alignedSamples = samples.copyOf(samples.size - samples.size % case.channelCount)
            val expected = Pcm16Normalizer.toMonoFloat(
                alignedSamples,
                case.channelCount,
                case.sourceRate,
                case.targetRate,
            )
            val actual = normalizeInChunks(alignedSamples, case, intArrayOf(1, 2, 7, 31, 128))

            assertArrayEquals(case.toString(), expected, actual, 0.000001f)
        }
    }

    @Test
    fun matchesWholeArrayNormalizerAcrossRandomChunkBoundaries() {
        val random = Random(20260813L)
        repeat(50) {
            val case = Case(
                channelCount = if (random.nextBoolean()) 1 else 2,
                sourceRate = listOf(8_000, 16_000, 44_100, 48_000)[random.nextInt(4)],
                targetRate = listOf(8_000, 16_000, 22_050)[random.nextInt(3)],
            )
            val frameCount = 1 + random.nextInt(2_000)
            val samples = ShortArray(frameCount * case.channelCount) { random.nextInt().toShort() }
            val chunks = IntArray(20) { 1 + random.nextInt(97) }
            val expected = Pcm16Normalizer.toMonoFloat(
                samples,
                case.channelCount,
                case.sourceRate,
                case.targetRate,
            )

            val actual = normalizeInChunks(samples, case, chunks)

            assertArrayEquals(case.toString(), expected, actual, 0.000001f)
        }
    }

    @Test
    fun carriesIncompleteStereoFrameAcrossPushes() {
        val normalizer = StreamingPcm16Normalizer(2, 16_000, 16_000)

        val first = normalizer.push(shortArrayOf(16_384))
        val second = normalizer.push(shortArrayOf(-16_384, 16_384, 16_384))
        val final = normalizer.finish()

        assertArrayEquals(FloatArray(0), first, 0f)
        assertArrayEquals(floatArrayOf(0f, 0.5f), second + final, 0.000001f)
    }

    @Test
    fun flushesSingleFrameAndUpsampledTailLikeWholeArrayNormalizer() {
        val single = StreamingPcm16Normalizer(1, 8_000, 16_000)
        assertArrayEquals(floatArrayOf(0.5f), single.push(shortArrayOf(16_384)), 0.000001f)
        assertArrayEquals(FloatArray(0), single.finish(), 0f)

        val upsampled = StreamingPcm16Normalizer(1, 2, 4)
        val actual = upsampled.push(shortArrayOf(0, 16_384)) + upsampled.finish()
        val expected = Pcm16Normalizer.toMonoFloat(shortArrayOf(0, 16_384), 1, 2, 4)
        assertArrayEquals(expected, actual, 0.000001f)
    }

    @Test
    fun retainsOnlyChannelCarryAndTwoMonoSamples() {
        val normalizer = StreamingPcm16Normalizer(2, 48_000, 16_000)
        repeat(10_000) { index ->
            normalizer.push(shortArrayOf(index.toShort()))
            assertTrue(normalizer.maximumRetainedSampleCount <= 4)
        }
        normalizer.finish()

        assertTrue(normalizer.maximumRetainedSampleCount <= 4)
    }

    @Test
    fun rejectsIncompleteFrameAndUseAfterFinish() {
        val incomplete = StreamingPcm16Normalizer(2, 16_000, 16_000)
        incomplete.push(shortArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { incomplete.finish() }

        val finished = StreamingPcm16Normalizer(1, 16_000, 16_000)
        finished.finish()
        assertThrows(IllegalStateException::class.java) { finished.push(shortArrayOf(1)) }
        assertThrows(IllegalStateException::class.java) { finished.finish() }
    }

    @Test
    fun emptyInputProducesEmptyOutput() {
        val normalizer = StreamingPcm16Normalizer(1, 16_000, 16_000)

        assertEquals(0, normalizer.push(ShortArray(0)).size)
        assertEquals(0, normalizer.finish().size)
    }

    private fun normalizeInChunks(
        samples: ShortArray,
        case: Case,
        chunkSizes: IntArray,
    ): FloatArray {
        val normalizer = StreamingPcm16Normalizer(
            case.channelCount,
            case.sourceRate,
            case.targetRate,
        )
        val output = mutableListOf<Float>()
        var offset = 0
        var chunkIndex = 0
        while (offset < samples.size) {
            val end = (offset + chunkSizes[chunkIndex % chunkSizes.size]).coerceAtMost(samples.size)
            normalizer.push(samples.copyOfRange(offset, end)).forEach(output::add)
            offset = end
            chunkIndex++
        }
        normalizer.finish().forEach(output::add)
        val maximumExpectedState = case.channelCount +
            ceil(case.sourceRate.toDouble() / case.targetRate).toInt().coerceAtLeast(1)
        assertTrue(
            "$case retained ${normalizer.maximumRetainedSampleCount} samples",
            normalizer.maximumRetainedSampleCount <= maximumExpectedState,
        )
        return output.toFloatArray()
    }

    private data class Case(
        val channelCount: Int,
        val sourceRate: Int,
        val targetRate: Int,
    )
}
