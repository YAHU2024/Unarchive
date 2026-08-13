package com.unarchive.android.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

class MediaCodecPcmAccumulator(
    private val targetSampleRate: Int,
    private val maximumDurationSeconds: Long,
    private val onSamples: (FloatArray) -> Unit = {},
    private val collectOutput: Boolean = true,
) {
    private var format: PcmOutputFormat? = null
    private var normalizer: StreamingPcm16Normalizer? = null
    private var pendingBytes = ByteArray(Float.SIZE_BYTES)
    private var pendingByteCount = 0
    private var decodedSampleCount = 0L
    private var finished = false
    private val output = FloatArrayCollector()
    private var normalizedSampleCount = 0L

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
            emit(currentNormalizer.push(samples))
        }
    }

    fun finish(): DecodedAudio {
        check(!finished) { "PCM accumulator is already finished" }
        finished = true
        require(pendingByteCount == 0) { "Decoder returned an incomplete PCM sample" }
        val currentNormalizer = requireNotNull(normalizer) { "Decoder produced no PCM format" }
        emit(currentNormalizer.finish())
        return DecodedAudio(output.toArray(), targetSampleRate)
    }

    fun finishStreaming(): Long {
        finish()
        return normalizedSampleCount
    }

    private fun emit(samples: FloatArray) {
        if (samples.isEmpty()) return
        normalizedSampleCount += samples.size
        onSamples(samples)
        if (collectOutput) output.addAll(samples)
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

}
