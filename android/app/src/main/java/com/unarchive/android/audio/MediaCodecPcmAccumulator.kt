package com.unarchive.android.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

class MediaCodecPcmAccumulator(
    private val targetSampleRate: Int,
    private val maximumDurationSeconds: Long,
) {
    private var format: PcmOutputFormat? = null
    private var normalizer: StreamingPcm16Normalizer? = null
    private var pendingBytes = ByteArray(Float.SIZE_BYTES)
    private var pendingByteCount = 0
    private var decodedSampleCount = 0L
    private var finished = false
    private val output = FloatArrayBuilder()

    internal var maximumPendingByteCount = 0
        private set

    init {
        require(targetSampleRate > 0) { "targetSampleRate must be positive" }
        require(maximumDurationSeconds > 0) { "maximumDurationSeconds must be positive" }
    }

    fun updateFormat(newFormat: PcmOutputFormat) {
        check(!finished) { "PCM accumulator is already finished" }
        val current = format
        require(current == null || decodedSampleCount == 0L || current == newFormat) {
            "Decoder PCM format changed after audio output started"
        }
        if (current != newFormat) {
            require(pendingByteCount == 0) { "Decoder PCM format changed mid-sample" }
            format = newFormat
            normalizer = StreamingPcm16Normalizer(
                channelCount = newFormat.channelCount,
                sourceSampleRate = newFormat.sampleRate,
                targetSampleRate = targetSampleRate,
            )
        }
    }

    fun push(buffer: ByteBuffer) {
        check(!finished) { "PCM accumulator is already finished" }
        val currentFormat = requireNotNull(format) { "Decoder PCM format is not configured" }
        val currentNormalizer = requireNotNull(normalizer)
        val input = buffer.duplicate().order(ByteOrder.nativeOrder())
        val sampleBytes = currentFormat.encoding.bytesPerSample
        val samples = ShortArray((pendingByteCount + input.remaining()) / sampleBytes)
        var sampleIndex = 0

        if (pendingByteCount > 0) {
            while (pendingByteCount < sampleBytes && input.hasRemaining()) {
                pendingBytes[pendingByteCount++] = input.get()
            }
            if (pendingByteCount == sampleBytes) {
                samples[sampleIndex++] = decodeSample(
                    ByteBuffer.wrap(pendingBytes, 0, sampleBytes).order(ByteOrder.nativeOrder()),
                    currentFormat.encoding,
                )
                pendingByteCount = 0
            }
        }

        while (input.remaining() >= sampleBytes) {
            samples[sampleIndex++] = decodeSample(input, currentFormat.encoding)
        }
        while (input.hasRemaining()) pendingBytes[pendingByteCount++] = input.get()
        maximumPendingByteCount = maxOf(maximumPendingByteCount, pendingByteCount)

        if (samples.isNotEmpty()) {
            decodedSampleCount += samples.size
            enforceDuration(currentFormat)
            output.addAll(currentNormalizer.push(samples))
        }
    }

    fun finish(): DecodedAudio {
        check(!finished) { "PCM accumulator is already finished" }
        finished = true
        require(pendingByteCount == 0) { "Decoder returned an incomplete PCM sample" }
        val currentNormalizer = requireNotNull(normalizer) { "Decoder produced no PCM format" }
        output.addAll(currentNormalizer.finish())
        return DecodedAudio(output.toArray(), targetSampleRate)
    }

    private fun enforceDuration(currentFormat: PcmOutputFormat) {
        val maximumSamples = maximumDurationSeconds *
            currentFormat.sampleRate * currentFormat.channelCount
        require(decodedSampleCount <= maximumSamples) {
            "Decoded audio exceeded the 5-minute safety limit"
        }
    }

    private fun decodeSample(buffer: ByteBuffer, encoding: PcmEncoding): Short = when (encoding) {
        PcmEncoding.PCM_16BIT -> buffer.short
        PcmEncoding.PCM_FLOAT ->
            (buffer.float.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
    }

    data class PcmOutputFormat(
        val sampleRate: Int,
        val channelCount: Int,
        val encoding: PcmEncoding,
    ) {
        init {
            require(sampleRate > 0) { "sampleRate must be positive" }
            require(channelCount > 0) { "channelCount must be positive" }
        }
    }

    enum class PcmEncoding(internal val bytesPerSample: Int) {
        PCM_16BIT(Short.SIZE_BYTES),
        PCM_FLOAT(Float.SIZE_BYTES),
    }

    private class FloatArrayBuilder {
        private var values = FloatArray(1_024)
        private var size = 0

        fun addAll(samples: FloatArray) {
            if (samples.isEmpty()) return
            val requiredSize = size + samples.size
            if (requiredSize > values.size) {
                var newSize = values.size
                while (newSize < requiredSize) newSize *= 2
                values = values.copyOf(newSize)
            }
            samples.copyInto(values, destinationOffset = size)
            size = requiredSize
        }

        fun toArray(): FloatArray = values.copyOf(size)
    }
}
