package com.unarchive.android.audio

import java.io.File
import java.io.RandomAccessFile

/** Fast structural validation for FFmpeg's normalized PCM16 WAV output. */
object Pcm16WavInspector {
    fun inspect(file: File, expectedSampleRate: Int): Result {
        require(expectedSampleRate > 0) { "expectedSampleRate must be positive" }
        require(file.isFile) { "WAV file is missing: $file" }
        return RandomAccessFile(file, "r").use { input ->
            val fileLength = input.length()
            require(fileLength >= MINIMUM_WAV_BYTES) { "WAV file is too small: $fileLength bytes" }
            require(input.readAscii(4) == "RIFF") { "WAV has no RIFF header" }
            val declaredRiffSize = input.readUInt32Le()
            require(declaredRiffSize + RIFF_PREFIX_BYTES <= fileLength) {
                "WAV RIFF size exceeds the file: declared=$declaredRiffSize file=$fileLength"
            }
            require(input.readAscii(4) == "WAVE") { "File is not a WAVE container" }

            var format: WaveFormat? = null
            var dataOffset = -1L
            var dataBytes = -1L
            while (input.filePointer + CHUNK_HEADER_BYTES <= fileLength) {
                val chunkId = input.readAscii(4)
                val chunkBytes = input.readUInt32Le()
                val payloadOffset = input.filePointer
                val paddedEnd = payloadOffset + chunkBytes + (chunkBytes and 1L)
                require(paddedEnd <= fileLength) {
                    "WAV chunk $chunkId exceeds the file: bytes=$chunkBytes"
                }
                when (chunkId) {
                    "fmt " -> {
                        require(chunkBytes >= PCM_FORMAT_BYTES) { "WAV fmt chunk is incomplete" }
                        format = WaveFormat(
                            encoding = input.readUInt16Le(),
                            channels = input.readUInt16Le(),
                            sampleRate = input.readUInt32Le(),
                            byteRate = input.readUInt32Le(),
                            blockAlign = input.readUInt16Le(),
                            bitsPerSample = input.readUInt16Le(),
                        )
                    }
                    "data" -> {
                        dataOffset = payloadOffset
                        dataBytes = chunkBytes
                    }
                }
                input.seek(paddedEnd)
                if (format != null && dataOffset >= 0) break
            }

            val waveFormat = requireNotNull(format) { "WAV has no fmt chunk" }
            require(waveFormat.encoding == PCM_ENCODING) { "WAV is not PCM: ${waveFormat.encoding}" }
            require(waveFormat.channels == MONO_CHANNELS) {
                "WAV is not mono: ${waveFormat.channels} channels"
            }
            require(waveFormat.sampleRate == expectedSampleRate.toLong()) {
                "Unexpected WAV sample rate: ${waveFormat.sampleRate}"
            }
            require(waveFormat.bitsPerSample == PCM16_BITS) {
                "WAV is not PCM16: ${waveFormat.bitsPerSample} bits"
            }
            require(waveFormat.blockAlign == PCM16_BLOCK_ALIGN) {
                "Unexpected WAV block alignment: ${waveFormat.blockAlign}"
            }
            require(waveFormat.byteRate == expectedSampleRate.toLong() * PCM16_BLOCK_ALIGN) {
                "Unexpected WAV byte rate: ${waveFormat.byteRate}"
            }
            require(dataOffset >= 0 && dataBytes > 0) { "WAV contains no PCM data" }
            require(dataBytes % PCM16_BLOCK_ALIGN == 0L) { "WAV PCM data is incomplete" }

            input.seek(dataOffset)
            var remaining = dataBytes
            var pendingLowByte: Int? = null
            var hasNonZeroSample = false
            val buffer = ByteArray(SCAN_BUFFER_BYTES)
            while (remaining > 0 && !hasNonZeroSample) {
                val requested = minOf(buffer.size.toLong(), remaining).toInt()
                val count = input.read(buffer, 0, requested)
                require(count > 0) { "Unexpected end of WAV PCM data" }
                remaining -= count
                for (index in 0 until count) {
                    val value = buffer[index].toInt() and 0xff
                    val lowByte = pendingLowByte
                    if (lowByte == null) {
                        pendingLowByte = value
                    } else {
                        hasNonZeroSample = lowByte != 0 || value != 0
                        pendingLowByte = null
                        if (hasNonZeroSample) break
                    }
                }
            }
            require(hasNonZeroSample) { "WAV contains only zero PCM samples" }
            Result(sampleCount = dataBytes / PCM16_BLOCK_ALIGN)
        }
    }

    data class Result(val sampleCount: Long)

    private data class WaveFormat(
        val encoding: Int,
        val channels: Int,
        val sampleRate: Long,
        val byteRate: Long,
        val blockAlign: Int,
        val bitsPerSample: Int,
    )

    private fun RandomAccessFile.readAscii(length: Int): String =
        ByteArray(length).also { readFully(it) }.toString(Charsets.US_ASCII)

    private fun RandomAccessFile.readUInt16Le(): Int {
        val low = read()
        val high = read()
        require(low >= 0 && high >= 0) { "Unexpected end of WAV file" }
        return low or (high shl 8)
    }

    private fun RandomAccessFile.readUInt32Le(): Long =
        readUInt16Le().toLong() or (readUInt16Le().toLong() shl 16)

    private const val MINIMUM_WAV_BYTES = 44L
    private const val RIFF_PREFIX_BYTES = 8L
    private const val CHUNK_HEADER_BYTES = 8L
    private const val PCM_FORMAT_BYTES = 16L
    private const val PCM_ENCODING = 1
    private const val MONO_CHANNELS = 1
    private const val PCM16_BITS = 16
    private const val PCM16_BLOCK_ALIGN = 2
    private const val SCAN_BUFFER_BYTES = 32 * 1_024
}
