package com.unarchive.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.concurrent.CancellationException

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

    @Test
    fun matchesWholeArrayNormalizerAcrossUnalignedReadChunks() {
        val samples = ShortArray(4_002) { index ->
            ((index * 4_099L + 811L) % 65_536L - 32_768L).toShort()
        }
        val wave = waveFile(2, 44_100, samples)
        val expected = Pcm16Normalizer.toMonoFloat(samples, 2, 44_100, 16_000)

        val decoded = Pcm16WaveDecoder.decode(
            input = ByteArrayInputStream(wave),
            targetSampleRate = 16_000,
            readBufferBytes = 7,
        )

        assertArrayEquals(expected, decoded.samples, 0.000001f)
    }

    @Test
    fun limitsPcmReadRequestsToConfiguredBuffer() {
        val wave = waveFile(1, 16_000, ShortArray(20_000) { it.toShort() })
        val input = TrackingInputStream(wave)

        val decoded = Pcm16WaveDecoder.decode(
            input = input,
            targetSampleRate = 16_000,
            readBufferBytes = 257,
        )

        assertEquals(20_000, decoded.samples.size)
        assertTrue(input.maximumReadLength <= 257)
    }

    @Test
    fun rejectsTruncatedPcmDataDuringChunkedRead() {
        val complete = waveFile(1, 16_000, shortArrayOf(1, 2, 3, 4))
        val truncated = complete.copyOf(complete.size - 2)

        assertThrows(EOFException::class.java) {
            Pcm16WaveDecoder.decode(
                ByteArrayInputStream(truncated),
                targetSampleRate = 16_000,
                readBufferBytes = 3,
            )
        }
    }

    @Test
    fun rejectsPcmThatEndsMidChannelFrame() {
        val wave = waveFile(2, 16_000, shortArrayOf(1, 2, 3))

        assertThrows(IllegalArgumentException::class.java) {
            Pcm16WaveDecoder.decode(
                ByteArrayInputStream(wave),
                targetSampleRate = 16_000,
                readBufferBytes = 3,
            )
        }
    }

    @Test
    fun checksCancellationBetweenPcmReadChunks() {
        val wave = waveFile(1, 16_000, ShortArray(20_000) { it.toShort() })
        var chunkCount = 0

        assertThrows(CancellationException::class.java) {
            Pcm16WaveDecoder.decode(
                input = ByteArrayInputStream(wave),
                targetSampleRate = 16_000,
                readBufferBytes = 257,
                onChunk = {
                    chunkCount++
                    if (chunkCount == 2) throw CancellationException("cancelled")
                },
            )
        }
        assertEquals(2, chunkCount)
    }

    @Test
    fun chunkApiMatchesCompatibilityDecodeAndReportsActualSampleCount() {
        val wave = waveFile(1, 8_000, shortArrayOf(0, 16_384, 0))
        val expected = Pcm16WaveDecoder.decode(
            ByteArrayInputStream(wave),
            targetSampleRate = 16_000,
            readBufferBytes = 3,
        )
        val chunks = mutableListOf<Float>()

        val count = Pcm16WaveDecoder.decodeChunks(
            input = ByteArrayInputStream(wave),
            targetSampleRate = 16_000,
            readBufferBytes = 3,
        ) { samples -> samples.forEach(chunks::add) }

        assertEquals(expected.samples.size.toLong(), count)
        assertArrayEquals(expected.samples, chunks.toFloatArray(), 0f)
    }

    @Test
    fun chunkApiReportsMonotonicDecodeProgress() {
        val wave = waveFile(1, 16_000, ShortArray(1_000) { it.toShort() })
        val progress = mutableListOf<Float>()

        Pcm16WaveDecoder.decodeChunks(
            input = ByteArrayInputStream(wave),
            targetSampleRate = 16_000,
            readBufferBytes = 127,
            onProgress = progress::add,
        ) { }

        assertTrue(progress.isNotEmpty())
        assertTrue(progress.zipWithNext().all { (before, after) -> after >= before })
        assertEquals(1f, progress.last(), 0f)
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

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var maximumReadLength = 0
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            maximumReadLength = maxOf(maximumReadLength, length)
            return super.read(buffer, offset, length)
        }
    }
}
