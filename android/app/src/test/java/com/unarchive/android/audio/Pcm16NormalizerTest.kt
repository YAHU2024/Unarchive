package com.unarchive.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Pcm16NormalizerTest {
    @Test
    fun convertsMonoPcm16ToFloat() {
        val result = Pcm16Normalizer.toMonoFloat(
            interleavedSamples = shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE),
            channelCount = 1,
            sourceSampleRate = 16_000,
            targetSampleRate = 16_000,
        )

        assertArrayEquals(floatArrayOf(-1f, 0f, Short.MAX_VALUE / 32768f), result, 0.0001f)
    }

    @Test
    fun averagesStereoChannels() {
        val result = Pcm16Normalizer.toMonoFloat(
            interleavedSamples = shortArrayOf(16_384, -16_384, 16_384, 16_384),
            channelCount = 2,
            sourceSampleRate = 16_000,
            targetSampleRate = 16_000,
        )

        assertArrayEquals(floatArrayOf(0f, 0.5f), result, 0.0001f)
    }

    @Test
    fun linearlyResamplesToTargetRate() {
        val result = Pcm16Normalizer.toMonoFloat(
            interleavedSamples = shortArrayOf(0, 16_384, 0, -16_384),
            channelCount = 1,
            sourceSampleRate = 4,
            targetSampleRate = 2,
        )

        assertEquals(2, result.size)
        assertArrayEquals(floatArrayOf(0f, 0f), result, 0.0001f)
    }

    @Test
    fun rejectsMisalignedInterleavedPcm() {
        assertThrows(IllegalArgumentException::class.java) {
            Pcm16Normalizer.toMonoFloat(shortArrayOf(1), 2, 16_000, 16_000)
        }
    }
}
