package com.unarchive.android.audio

import com.unarchive.android.audio.MediaCodecPcmAccumulator.PcmEncoding
import com.unarchive.android.audio.MediaCodecPcmAccumulator.PcmOutputFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MediaCodecPcmAccumulatorTest {
    @Test
    fun pcm16ChunksMatchWholeArrayNormalizerAcrossByteBoundaries() {
        val samples = ShortArray(4_002) { index ->
            ((index * 4_099L + 811L) % 65_536L - 32_768L).toShort()
        }
        val expected = Pcm16Normalizer.toMonoFloat(samples, 2, 44_100, 16_000)
        val bytes = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { samples.forEach(::putShort) }
            .array()
        val accumulator = accumulator(PcmOutputFormat(44_100, 2, PcmEncoding.PCM_16BIT))

        pushInChunks(accumulator, bytes, intArrayOf(1, 7, 32, 513))
        val actual = accumulator.finish()

        assertArrayEquals(expected, actual.samples, 0.000001f)
        assertTrue(accumulator.maximumPendingByteCount < Short.SIZE_BYTES)
    }

    @Test
    fun floatChunksClampAndMatchPcm16Conversion() {
        val floats = floatArrayOf(-2f, -1f, -0.5f, 0f, 0.5f, 1f, 2f)
        val bytes = ByteBuffer.allocate(floats.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { floats.forEach(::putFloat) }
            .array()
        val expectedPcm = floats.map {
            (it.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }.toShortArray()
        val expected = Pcm16Normalizer.toMonoFloat(expectedPcm, 1, 16_000, 16_000)
        val accumulator = accumulator(PcmOutputFormat(16_000, 1, PcmEncoding.PCM_FLOAT))

        pushInChunks(accumulator, bytes, intArrayOf(3, 1, 5))
        val actual = accumulator.finish()

        assertArrayEquals(expected, actual.samples, 0.000001f)
        assertTrue(accumulator.maximumPendingByteCount < Float.SIZE_BYTES)
    }

    @Test
    fun allowsFormatCorrectionBeforeFirstOutput() {
        val accumulator = MediaCodecPcmAccumulator(16_000, 300)
        accumulator.updateFormat(PcmOutputFormat(48_000, 2, PcmEncoding.PCM_16BIT))
        accumulator.updateFormat(PcmOutputFormat(16_000, 1, PcmEncoding.PCM_16BIT))
        accumulator.push(shortBuffer(shortArrayOf(16_384)))

        assertArrayEquals(floatArrayOf(0.5f), accumulator.finish().samples, 0.000001f)
    }

    @Test
    fun rejectsFormatChangeAfterOutputStarts() {
        val accumulator = accumulator(PcmOutputFormat(16_000, 1, PcmEncoding.PCM_16BIT))
        accumulator.push(shortBuffer(shortArrayOf(1)))

        assertThrows(IllegalArgumentException::class.java) {
            accumulator.updateFormat(PcmOutputFormat(48_000, 2, PcmEncoding.PCM_16BIT))
        }
    }

    @Test
    fun rejectsIncompleteSampleAtEndOfStream() {
        val accumulator = accumulator(PcmOutputFormat(16_000, 1, PcmEncoding.PCM_FLOAT))
        accumulator.push(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))

        assertThrows(IllegalArgumentException::class.java) { accumulator.finish() }
    }

    @Test
    fun enforcesDurationFromDecodedSamplesWithoutWholePcmBuffer() {
        val accumulator = MediaCodecPcmAccumulator(targetSampleRate = 4, maximumDurationSeconds = 1)
        accumulator.updateFormat(PcmOutputFormat(4, 1, PcmEncoding.PCM_16BIT))

        assertThrows(IllegalArgumentException::class.java) {
            accumulator.push(shortBuffer(shortArrayOf(1, 2, 3, 4, 5)))
        }
    }

    @Test
    fun returnsEmptyAudioWhenConfiguredStreamHasNoSamples() {
        val accumulator = accumulator(PcmOutputFormat(16_000, 1, PcmEncoding.PCM_16BIT))

        val audio = accumulator.finish()

        assertEquals(16_000, audio.sampleRate)
        assertEquals(0, audio.samples.size)
    }

    @Test
    fun streamingModeEmitsChunksWithoutCollectingCompatibilityOutput() {
        val emitted = mutableListOf<Float>()
        val accumulator = MediaCodecPcmAccumulator(
            targetSampleRate = 16_000,
            maximumDurationSeconds = 300,
            onSamples = { samples -> samples.forEach(emitted::add) },
            collectOutput = false,
        )
        accumulator.updateFormat(PcmOutputFormat(16_000, 1, PcmEncoding.PCM_16BIT))
        accumulator.push(shortBuffer(shortArrayOf(0, 16_384, -16_384)))

        val count = accumulator.finishStreaming()

        assertEquals(3L, count)
        assertArrayEquals(floatArrayOf(0f, 0.5f, -0.5f), emitted.toFloatArray(), 0.000001f)
    }

    private fun accumulator(format: PcmOutputFormat) =
        MediaCodecPcmAccumulator(targetSampleRate = 16_000, maximumDurationSeconds = 300)
            .also { it.updateFormat(format) }

    private fun pushInChunks(
        accumulator: MediaCodecPcmAccumulator,
        bytes: ByteArray,
        chunkSizes: IntArray,
    ) {
        var offset = 0
        var chunkIndex = 0
        while (offset < bytes.size) {
            val end = (offset + chunkSizes[chunkIndex % chunkSizes.size]).coerceAtMost(bytes.size)
            accumulator.push(ByteBuffer.wrap(bytes, offset, end - offset).slice())
            offset = end
            chunkIndex++
        }
    }

    private fun shortBuffer(samples: ShortArray): ByteBuffer =
        ByteBuffer.allocate(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                samples.forEach(::putShort)
                flip()
            }
}
