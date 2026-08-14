package com.unarchive.android.audio

import java.io.EOFException
import java.io.InputStream

/**
 * Streaming WAV decoder.
 *
 * Supports RIFF/WAVE files with PCM (8/16/24/32-bit integer) and IEEE float
 * (32/64-bit) sample formats, including the WAVE_FORMAT_EXTENSIBLE container.
 * Samples are normalized to [-1, 1] floats, downmixed to mono and resampled to
 * [targetSampleRate] via [StreamingPcm16Normalizer].
 */
object WaveDecoder {
    fun decode(
        input: InputStream,
        targetSampleRate: Int,
        maximumDurationSeconds: Long = 4 * 60 * 60L,
        readBufferBytes: Int = DEFAULT_READ_BUFFER_BYTES,
        onChunk: () -> Unit = {},
    ): DecodedAudio {
        val output = FloatArrayCollector()
        decodeChunks(
            input = input,
            targetSampleRate = targetSampleRate,
            maximumDurationSeconds = maximumDurationSeconds,
            readBufferBytes = readBufferBytes,
            onChunk = onChunk,
            onSamples = output::addAll,
        )
        return DecodedAudio(output.toArray(), targetSampleRate)
    }

    fun decodeChunks(
        input: InputStream,
        targetSampleRate: Int,
        maximumDurationSeconds: Long = 4 * 60 * 60L,
        readBufferBytes: Int = DEFAULT_READ_BUFFER_BYTES,
        onChunk: () -> Unit = {},
        onProgress: (Float) -> Unit = {},
        startAtMs: Long = 0,
        onSamples: (FloatArray) -> Unit,
    ): Long {
        require(readBufferBytes > 0) { "readBufferBytes must be positive" }
        require(startAtMs >= 0) { "startAtMs cannot be negative" }
        require(input.readAscii(4) == "RIFF") { "Selected WAV has no RIFF header" }
        input.readUInt32Le()
        require(input.readAscii(4) == "WAVE") { "Selected file is not a WAVE container" }

        var format: WaveFormat? = null
        while (true) {
            val chunkId = input.readAsciiOrNull(4)
                ?: throw IllegalArgumentException("Selected WAV has no audio data")
            val chunkSize = input.readUInt32Le()
            require(chunkSize <= Int.MAX_VALUE.toLong()) { "WAV chunk is too large" }

            when (chunkId) {
                "fmt " -> format = input.readFormat(chunkSize.toInt())
                "data" -> {
                    val waveFormat = requireNotNull(format) { "WAV data appears before its format" }
                    val bytesPerSample = waveFormat.bytesPerSample
                    val maximumBytes = maximumDurationSeconds *
                        waveFormat.sampleRate * waveFormat.channelCount * bytesPerSample
                    require(chunkSize <= maximumBytes) {
                        "WAV audio exceeded the safety duration limit"
                    }
                    require(chunkSize % bytesPerSample == 0L) {
                        "WAV contains incomplete ${waveFormat.displayName} data"
                    }
                    val normalizer = StreamingPcm16Normalizer(
                        channelCount = waveFormat.channelCount,
                        sourceSampleRate = waveFormat.sampleRate,
                        targetSampleRate = targetSampleRate,
                    )
                    val frameBytes = waveFormat.channelCount * bytesPerSample
                    val requestedFrames = startAtMs * waveFormat.sampleRate / 1_000
                    val availableFrames = chunkSize / frameBytes
                    val skippedBytes = minOf(requestedFrames, availableFrames) * frameBytes
                    input.skipExactly(skippedBytes)
                    val remainingBytes = chunkSize - skippedBytes
                    var normalizedSampleCount = 0L
                    fun emit(samples: FloatArray) {
                        if (samples.isEmpty()) return
                        normalizedSampleCount += samples.size
                        onSamples(samples)
                    }
                    input.readSampleChunks(
                        byteCount = remainingBytes,
                        format = waveFormat,
                        readBufferBytes = readBufferBytes,
                        onChunk = onChunk,
                        onProgress = onProgress,
                    ) { samples -> emit(normalizer.push(samples)) }
                    emit(normalizer.finish())
                    return normalizedSampleCount
                }
                else -> input.skipExactly(chunkSize)
            }
            if (chunkSize % 2L != 0L) input.skipExactly(1)
        }
    }

    private fun InputStream.readFormat(chunkSize: Int): WaveFormat {
        require(chunkSize >= MIN_FORMAT_SIZE) { "WAV format chunk is incomplete" }
        val bytes = readExactly(chunkSize)
        var audioFormat = bytes.uint16Le(0)
        if (audioFormat == EXTENSIBLE_FORMAT) {
            require(chunkSize >= EXTENSIBLE_FORMAT_SIZE) {
                "WAVE_FORMAT_EXTENSIBLE format chunk is incomplete"
            }
            // The SubFormat GUID starts after cbSize + validBitsPerSample +
            // channelMask; its first two bytes carry the real format code.
            audioFormat = bytes.uint16Le(EXTENSIBLE_SUBFORMAT_OFFSET)
        }
        val channelCount = bytes.uint16Le(2)
        val sampleRate = bytes.uint32Le(4)
        val bitsPerSample = bytes.uint16Le(14)
        require(channelCount > 0) { "WAV channel count must be positive" }
        require(sampleRate in 1..Int.MAX_VALUE.toLong()) { "WAV sample rate is invalid" }
        val sampleType = when (audioFormat) {
            PCM_FORMAT -> when (bitsPerSample) {
                8 -> SampleType.PCM8
                16 -> SampleType.PCM16
                24 -> SampleType.PCM24
                32 -> SampleType.PCM32
                else -> throw IllegalArgumentException(
                    "Unsupported WAV PCM bit depth: $bitsPerSample",
                )
            }
            FLOAT_FORMAT -> when (bitsPerSample) {
                32 -> SampleType.FLOAT32
                64 -> SampleType.FLOAT64
                else -> throw IllegalArgumentException(
                    "Unsupported WAV float bit depth: $bitsPerSample",
                )
            }
            else -> throw IllegalArgumentException("Unsupported WAV encoding: $audioFormat")
        }
        return WaveFormat(channelCount, sampleRate.toInt(), sampleType)
    }

    /**
     * Reads [byteCount] raw sample bytes and emits [FloatArray] chunks of
     * decoded samples. Handles arbitrary read boundaries: partial samples at
     * the start and end of a read are carried in [pending] bytes.
     */
    private fun InputStream.readSampleChunks(
        byteCount: Long,
        format: WaveFormat,
        readBufferBytes: Int,
        onChunk: () -> Unit,
        onProgress: (Float) -> Unit,
        consume: (FloatArray) -> Unit,
    ) {
        val bytesPerSample = format.bytesPerSample
        val buffer = ByteArray(readBufferBytes)
        val pending = ByteArray(bytesPerSample)
        var pendingCount = 0
        var remaining = byteCount
        var consumedBytes = 0L
        val samples = FloatArray(readBufferBytes / bytesPerSample + 1)
        while (remaining > 0) {
            onChunk()
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val count = read(buffer, 0, requested)
            if (count < 0) throw EOFException("Unexpected end of WAV file")
            if (count == 0) continue
            remaining -= count
            consumedBytes += count

            var byteIndex = 0
            var sampleIndex = 0
            if (pendingCount > 0) {
                while (pendingCount < bytesPerSample && byteIndex < count) {
                    pending[pendingCount++] = buffer[byteIndex++]
                }
                if (pendingCount == bytesPerSample) {
                    samples[sampleIndex++] = format.decodeSample(pending, 0)
                    pendingCount = 0
                }
            }
            while (byteIndex + bytesPerSample <= count) {
                samples[sampleIndex++] = format.decodeSample(buffer, byteIndex)
                byteIndex += bytesPerSample
            }
            while (byteIndex < count) pending[pendingCount++] = buffer[byteIndex++]

            if (sampleIndex > 0) consume(samples.copyOf(sampleIndex))
            onProgress(consumedBytes.toFloat() / byteCount.coerceAtLeast(1L))
        }
        check(pendingCount == 0) { "WAV contains incomplete ${format.displayName} data" }
    }

    private fun InputStream.readAscii(length: Int): String =
        readAsciiOrNull(length) ?: throw EOFException("Unexpected end of WAV file")

    private fun InputStream.readAsciiOrNull(length: Int): String? {
        val first = read()
        if (first < 0) return null
        val bytes = ByteArray(length)
        bytes[0] = first.toByte()
        readFully(bytes, 1, length - 1)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun InputStream.readUInt32Le(): Long = readExactly(4).uint32Le(0)

    private fun InputStream.readExactly(length: Int): ByteArray =
        ByteArray(length).also { readFully(it, 0, length) }

    private fun InputStream.readFully(bytes: ByteArray, offset: Int, length: Int) {
        var position = offset
        val end = offset + length
        while (position < end) {
            val count = read(bytes, position, end - position)
            if (count < 0) throw EOFException("Unexpected end of WAV file")
            position += count
        }
    }

    private fun InputStream.skipExactly(byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                throw EOFException("Unexpected end of WAV file")
            }
        }
    }

    private data class WaveFormat(
        val channelCount: Int,
        val sampleRate: Int,
        val sampleType: SampleType,
    ) {
        val bytesPerSample: Int get() = sampleType.bytesPerSample
        val displayName: String get() = sampleType.displayName

        fun decodeSample(bytes: ByteArray, offset: Int): Float = when (sampleType) {
            SampleType.PCM8 -> ((bytes[offset].toInt() and 0xff) - 128) / 128f
            SampleType.PCM16 -> bytes.uint16Le(offset).toShort() / 32768f
            SampleType.PCM24 -> signExtend24(bytes, offset) / 8_388_608f
            SampleType.PCM32 -> bytes.uint32Le(offset).toInt() / 2_147_483_648f
            SampleType.FLOAT32 -> Float.fromBits(bytes.uint32Le(offset).toInt())
            SampleType.FLOAT64 ->
                java.lang.Double.longBitsToDouble(bytes.uint64Le(offset)).toFloat()
        }
    }

    private enum class SampleType(
        val bytesPerSample: Int,
        val displayName: String,
    ) {
        PCM8(1, "PCM8 audio"),
        PCM16(2, "PCM16 audio"),
        PCM24(3, "PCM24 audio"),
        PCM32(4, "PCM32 audio"),
        FLOAT32(4, "float32 audio"),
        FLOAT64(8, "float64 audio"),
    }

    private fun signExtend24(bytes: ByteArray, offset: Int): Int {
        val value = (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16)
        return if (value and 0x800000 != 0) value or 0xff000000.toInt() else value
    }

    private fun ByteArray.uint16Le(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.uint32Le(offset: Int): Long =
        (uint16Le(offset).toLong() or (uint16Le(offset + 2).toLong() shl 16)) and 0xffffffffL

    private fun ByteArray.uint64Le(offset: Int): Long =
        uint32Le(offset) or (uint32Le(offset + 4) shl 32)

    private const val PCM_FORMAT = 1
    private const val FLOAT_FORMAT = 3
    private const val EXTENSIBLE_FORMAT = 0xfffe
    private const val MIN_FORMAT_SIZE = 16
    private const val EXTENSIBLE_FORMAT_SIZE = 40
    private const val EXTENSIBLE_SUBFORMAT_OFFSET = 24
    private const val DEFAULT_READ_BUFFER_BYTES = 32 * 1_024
}
