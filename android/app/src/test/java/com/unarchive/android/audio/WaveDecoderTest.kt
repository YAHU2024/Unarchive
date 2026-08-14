package com.unarchive.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

class WaveDecoderTest {
    @Test
    fun startsAtRequestedFrameBoundary() {
        val samples = ShortArray(16_000) { it.toShort() }
        val emitted = mutableListOf<Float>()

        val count = WaveDecoder.decodeChunks(
            input = ByteArrayInputStream(
                waveFile(channelCount = 1, sampleRate = 16_000, samples = samples),
            ),
            targetSampleRate = 16_000,
            startAtMs = 500,
            onSamples = { chunk -> emitted += chunk.toList() },
        )

        assertEquals(8_000L, count)
        assertEquals(samples[8_000] / 32768f, emitted.first(), 0f)
    }

    @Test
    fun decodesAndDownmixesStereoPcm16Wave() {
        val wave = waveFile(
            channelCount = 2,
            sampleRate = 48_000,
            samples = shortArrayOf(16_384, -16_384, 16_384, 16_384),
        )

        val decoded = WaveDecoder.decode(ByteArrayInputStream(wave), targetSampleRate = 48_000)

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

        val decoded = WaveDecoder.decode(ByteArrayInputStream(wave), targetSampleRate = 16_000)

        assertEquals(2, decoded.samples.size)
    }

    @Test
    fun decodesUnsignedPcm8Wave() {
        val wave = waveFile(
            formatTag = 1,
            bitsPerSample = 8,
            channelCount = 1,
            sampleRate = 16_000,
            data = byteArrayOf(0, 128.toByte(), 255.toByte()),
        )

        val decoded = WaveDecoder.decode(ByteArrayInputStream(wave), targetSampleRate = 16_000)

        assertArrayEquals(floatArrayOf(-1f, 0f, 127f / 128f), decoded.samples, 0.000001f)
    }

    @Test
    fun decodesPcm24Wave() {
        val wave = waveFile(
            formatTag = 1,
            bitsPerSample = 24,
            channelCount = 1,
            sampleRate = 16_000,
            data = byteArrayOf(0, 0, 0x40, 0, 0, 0xc0.toByte()),
        )

        val decoded = WaveDecoder.decode(ByteArrayInputStream(wave), targetSampleRate = 16_000)

        assertArrayEquals(floatArrayOf(0.5f, -0.5f), decoded.samples, 0.000001f)
    }

    @Test
    fun decodesPcm32Wave() {
        val wave = waveFile(
            formatTag = 1,
            bitsPerSample = 32,
            channelCount = 1,
            sampleRate = 16_000,
            data = byteArrayOf(0, 0, 0, 0x40, 0, 0, 0, 0xc0.toByte()),
        )

        val decoded = WaveDecoder.decode(ByteArrayInputStream(wave), targetSampleRate = 16_000)

        assertArrayEquals(floatArrayOf(0.5f, -0.5f), decoded.samples, 0.000001f)
    }

    @Test
    fun decodesFloat32Wave() {
        val floats = floatArrayOf(0.5f, -0.25f)
        val data = ByteBuffer.allocate(floats.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { floats.forEach(::putFloat) }
            .array()
        val wave = waveFile(
            formatTag = 3,
            bitsPerSample = 32,
            channelCount = 1,
            sampleRate = 16_000,
            data = data,
        )

        val decoded = WaveDecoder.decode(ByteArrayInputStream(wave), targetSampleRate = 16_000)

        assertArrayEquals(floats, decoded.samples, 0.000001f)
    }

    @Test
    fun decodesFloat64Wave() {
        val doubles = doubleArrayOf(0.5, -0.25)
        val data = ByteBuffer.allocate(doubles.size * java.lang.Double.SIZE / 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { doubles.forEach(::putDouble) }
            .array()
        val wave = waveFile(
            formatTag = 3,
            bitsPerSample = 64,
            channelCount = 1,
            sampleRate = 16_000,
            data = data,
        )

        val decoded = WaveDecoder.decode(ByteArrayInputStream(wave), targetSampleRate = 16_000)

        assertArrayEquals(floatArrayOf(0.5f, -0.25f), decoded.samples, 0.000001f)
    }

    @Test
    fun decodesExtensiblePcm16Wave() {
        val wave = waveFile(
            formatTag = 1,
            bitsPerSample = 16,
            channelCount = 1,
            sampleRate = 16_000,
            data = le16Bytes(intArrayOf(16_384, -16_384)),
            extensible = true,
        )

        val decoded = WaveDecoder.decode(ByteArrayInputStream(wave), targetSampleRate = 16_000)

        assertArrayEquals(floatArrayOf(0.5f, -0.5f), decoded.samples, 0.000001f)
    }

    @Test
    fun decodesExtensibleFloat32Wave() {
        val floats = floatArrayOf(0.5f)
        val data = ByteBuffer.allocate(floats.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { floats.forEach(::putFloat) }
            .array()
        val wave = waveFile(
            formatTag = 3,
            bitsPerSample = 32,
            channelCount = 1,
            sampleRate = 16_000,
            data = data,
            extensible = true,
        )

        val decoded = WaveDecoder.decode(ByteArrayInputStream(wave), targetSampleRate = 16_000)

        assertArrayEquals(floats, decoded.samples, 0.000001f)
    }

    @Test
    fun rejectsUnsupportedWaveEncoding() {
        val wave = waveFile(
            formatTag = 6,
            bitsPerSample = 16,
            channelCount = 1,
            sampleRate = 16_000,
            data = le16Bytes(intArrayOf(0)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            WaveDecoder.decode(ByteArrayInputStream(wave), targetSampleRate = 16_000)
        }
    }

    @Test
    fun rejectsUnsupportedBitDepth() {
        val wave = waveFile(
            formatTag = 1,
            bitsPerSample = 12,
            channelCount = 1,
            sampleRate = 16_000,
            data = byteArrayOf(0, 0),
        )

        assertThrows(IllegalArgumentException::class.java) {
            WaveDecoder.decode(ByteArrayInputStream(wave), targetSampleRate = 16_000)
        }
    }

    @Test
    fun matchesWholeArrayNormalizerAcrossUnalignedReadChunks() {
        val samples = ShortArray(4_002) { index ->
            ((index * 4_099L + 811L) % 65_536L - 32_768L).toShort()
        }
        val wave = waveFile(2, 44_100, samples)
        val expected = Pcm16Normalizer.toMonoFloat(samples, 2, 44_100, 16_000)

        val decoded = WaveDecoder.decode(
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

        val decoded = WaveDecoder.decode(
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
            WaveDecoder.decode(
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
            WaveDecoder.decode(
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
            WaveDecoder.decode(
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
        val expected = WaveDecoder.decode(
            ByteArrayInputStream(wave),
            targetSampleRate = 16_000,
            readBufferBytes = 3,
        )
        val chunks = mutableListOf<Float>()

        val count = WaveDecoder.decodeChunks(
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

        WaveDecoder.decodeChunks(
            input = ByteArrayInputStream(wave),
            targetSampleRate = 16_000,
            readBufferBytes = 127,
            onProgress = progress::add,
        ) { }

        assertTrue(progress.isNotEmpty())
        assertTrue(progress.zipWithNext().all { (before, after) -> after >= before })
        assertEquals(1f, progress.last(), 0f)
    }

    @Test
    fun decodesFloat32WaveAcrossUnalignedReadChunks() {
        val floats = FloatArray(3_001) { index -> ((index % 97) - 48) / 48f }
        val data = ByteBuffer.allocate(floats.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { floats.forEach(::putFloat) }
            .array()
        val wave = waveFile(
            formatTag = 3,
            bitsPerSample = 32,
            channelCount = 1,
            sampleRate = 16_000,
            data = data,
        )

        val decoded = WaveDecoder.decode(
            input = ByteArrayInputStream(wave),
            targetSampleRate = 16_000,
            readBufferBytes = 7,
        )

        assertArrayEquals(floats, decoded.samples, 0.000001f)
    }

    private fun waveFile(
        channelCount: Int,
        sampleRate: Int,
        samples: ShortArray,
        includeOddJunkChunk: Boolean = false,
    ): ByteArray = waveFile(
        formatTag = 1,
        bitsPerSample = 16,
        channelCount = channelCount,
        sampleRate = sampleRate,
        data = ByteArray(samples.size * Short.SIZE_BYTES).also { bytes ->
            samples.forEachIndexed { index, sample ->
                bytes[index * 2] = (sample.toInt() and 0xff).toByte()
                bytes[index * 2 + 1] = (sample.toInt() ushr 8 and 0xff).toByte()
            }
        },
        includeOddJunkChunk = includeOddJunkChunk,
    )

    private fun waveFile(
        formatTag: Int,
        bitsPerSample: Int,
        channelCount: Int,
        sampleRate: Int,
        data: ByteArray,
        includeOddJunkChunk: Boolean = false,
        extensible: Boolean = false,
    ): ByteArray {
        val bytesPerSample = bitsPerSample / 8
        val fmtBody = ByteArrayOutputStream().apply {
            writeLe16(if (extensible) 0xfffe else formatTag)
            writeLe16(channelCount)
            writeLe32(sampleRate)
            writeLe32(sampleRate * channelCount * bytesPerSample)
            writeLe16(channelCount * bytesPerSample)
            writeLe16(bitsPerSample)
            if (extensible) {
                writeLe16(22)
                writeLe16(bitsPerSample)
                writeLe32(0)
                writeLe16(formatTag)
                writeLe16(0x0000)
                writeLe32(0x00100000)
                writeLe32(0xAA000080.toInt())
                writeLe32(0x719B3800)
            }
        }
        val body = ByteArrayOutputStream().apply {
            write("fmt ".toByteArray())
            writeLe32(if (extensible) 40 else 16)
            write(fmtBody.toByteArray())
            if (includeOddJunkChunk) {
                write("JUNK".toByteArray())
                writeLe32(1)
                write(7)
                write(0)
            }
            write("data".toByteArray())
            writeLe32(data.size)
            write(data)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            writeLe32(body.size + 4)
            write("WAVE".toByteArray())
            write(body)
        }.toByteArray()
    }

    private fun le16Bytes(values: IntArray): ByteArray =
        ByteArray(values.size * 2).also { bytes ->
            values.forEachIndexed { index, value ->
                bytes[index * 2] = (value and 0xff).toByte()
                bytes[index * 2 + 1] = (value ushr 8 and 0xff).toByte()
            }
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
