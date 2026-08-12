package com.unarchive.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class Pcm16WaveDecoderTest {
    @Test
    fun decodesAndDownmixesStereoPcm16Wave() {
        val wave = waveFile(
            channelCount = 2,
            sampleRate = 48_000,
            samples = shortArrayOf(16_384, -16_384, 16_384, 16_384),
        )

        val decoded = Pcm16WaveDecoder.decode(ByteArrayInputStream(wave), targetSampleRate = 48_000)

        assertEquals(48_000, decoded.sampleRate)
        assertArrayEquals(floatArrayOf(0f, 0.5f), decoded.samples, 0.0001f)
    }

    @Test
    fun skipsUnknownChunksWithOddPadding() {
        val wave = waveFile(
            channelCount = 1,
            sampleRate = 16_000,
            samples = shortArrayOf(0, Short.MAX_VALUE),
            includeOddJunkChunk = true,
        )

        val decoded = Pcm16WaveDecoder.decode(ByteArrayInputStream(wave), targetSampleRate = 16_000)

        assertEquals(2, decoded.samples.size)
    }

    @Test
    fun rejectsNonPcm16Wave() {
        val wave = waveFile(1, 16_000, shortArrayOf(0)).also {
            it[20] = 3
        }

        assertThrows(IllegalArgumentException::class.java) {
            Pcm16WaveDecoder.decode(ByteArrayInputStream(wave), targetSampleRate = 16_000)
        }
    }

    private fun waveFile(
        channelCount: Int,
        sampleRate: Int,
        samples: ShortArray,
        includeOddJunkChunk: Boolean = false,
    ): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write("fmt ".toByteArray())
            writeLe32(16)
            writeLe16(1)
            writeLe16(channelCount)
            writeLe32(sampleRate)
            writeLe32(sampleRate * channelCount * Short.SIZE_BYTES)
            writeLe16(channelCount * Short.SIZE_BYTES)
            writeLe16(16)
            if (includeOddJunkChunk) {
                write("JUNK".toByteArray())
                writeLe32(1)
                write(7)
                write(0)
            }
            write("data".toByteArray())
            writeLe32(samples.size * Short.SIZE_BYTES)
            samples.forEach { writeLe16(it.toInt()) }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            writeLe32(body.size + 4)
            write("WAVE".toByteArray())
            write(body)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        writeLe16(value)
        writeLe16(value ushr 16)
    }
}
