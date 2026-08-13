package com.unarchive.android.audio

import java.io.EOFException
import java.io.InputStream

object Pcm16WaveDecoder {
    fun decode(
        input: InputStream,
        targetSampleRate: Int,
        maximumDurationSeconds: Long = 5 * 60L,
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
        maximumDurationSeconds: Long = 5 * 60L,
        readBufferBytes: Int = DEFAULT_READ_BUFFER_BYTES,
        onChunk: () -> Unit = {},
        onProgress: (Float) -> Unit = {},
        onSamples: (FloatArray) -> Unit,
    ): Long {
        require(readBufferBytes > 0) { "readBufferBytes must be positive" }
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
                    val maximumBytes = maximumDurationSeconds *
                        waveFormat.sampleRate * waveFormat.channelCount * Short.SIZE_BYTES
                    require(chunkSize <= maximumBytes) {
                        "WAV audio exceeded the 5-minute safety limit"
                    }
                    require(chunkSize % Short.SIZE_BYTES == 0L) {
                        "WAV contains incomplete PCM16 data"
                    }
                    val normalizer = StreamingPcm16Normalizer(
                        channelCount = waveFormat.channelCount,
                        sourceSampleRate = waveFormat.sampleRate,
                        targetSampleRate = targetSampleRate,
                    )
                    var normalizedSampleCount = 0L
                    fun emit(samples: FloatArray) {
                        if (samples.isEmpty()) return
                        normalizedSampleCount += samples.size
                        onSamples(samples)
                    }
                    input.readPcm16Chunks(
                        byteCount = chunkSize,
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
        val audioFormat = bytes.uint16Le(0)
        val channelCount = bytes.uint16Le(2)
        val sampleRate = bytes.uint32Le(4)
        val bitsPerSample = bytes.uint16Le(14)
        require(audioFormat == PCM_FORMAT) { "WAV encoding $audioFormat is not PCM" }
        require(channelCount > 0) { "WAV channel count must be positive" }
        require(sampleRate in 1..Int.MAX_VALUE.toLong()) { "WAV sample rate is invalid" }
        require(bitsPerSample == 16) { "WAV must contain PCM16 audio" }
        return WaveFormat(channelCount, sampleRate.toInt())
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

    private fun InputStream.readPcm16Chunks(
        byteCount: Long,
        readBufferBytes: Int,
        onChunk: () -> Unit,
        onProgress: (Float) -> Unit,
        consume: (ShortArray) -> Unit,
    ) {
        val buffer = ByteArray(readBufferBytes)
        var remaining = byteCount
        var consumedBytes = 0L
        var pendingLowByte = -1
        while (remaining > 0) {
            onChunk()
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val count = read(buffer, 0, requested)
            if (count < 0) throw EOFException("Unexpected end of WAV file")
            if (count == 0) continue
            remaining -= count
            consumedBytes += count

            val sampleCount = (count + if (pendingLowByte >= 0) 1 else 0) / Short.SIZE_BYTES
            val samples = ShortArray(sampleCount)
            var byteIndex = 0
            var sampleIndex = 0
            if (pendingLowByte >= 0) {
                samples[sampleIndex++] = (pendingLowByte or (buffer[byteIndex].toInt() shl 8)).toShort()
                pendingLowByte = -1
                byteIndex++
            }
            while (byteIndex + 1 < count) {
                samples[sampleIndex++] = (
                    (buffer[byteIndex].toInt() and 0xff) or
                        (buffer[byteIndex + 1].toInt() shl 8)
                    ).toShort()
                byteIndex += Short.SIZE_BYTES
            }
            if (byteIndex < count) pendingLowByte = buffer[byteIndex].toInt() and 0xff
            if (samples.isNotEmpty()) consume(samples)
            onProgress(consumedBytes.toFloat() / byteCount.coerceAtLeast(1L))
        }
        check(pendingLowByte < 0) { "WAV contains incomplete PCM16 data" }
    }

    private fun ByteArray.uint16Le(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.uint32Le(offset: Int): Long =
        (uint16Le(offset).toLong() or (uint16Le(offset + 2).toLong() shl 16)) and 0xffffffffL

    private data class WaveFormat(val channelCount: Int, val sampleRate: Int)

    private const val PCM_FORMAT = 1
    private const val MIN_FORMAT_SIZE = 16
    private const val DEFAULT_READ_BUFFER_BYTES = 32 * 1_024
}
