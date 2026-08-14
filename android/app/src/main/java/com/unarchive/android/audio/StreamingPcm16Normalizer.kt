package com.unarchive.android.audio

import kotlin.math.floor

class StreamingPcm16Normalizer(
    private val channelCount: Int,
    private val sourceSampleRate: Int,
    private val targetSampleRate: Int,
) {
    private val pendingFrame = FloatArray(channelCount.coerceAtLeast(1))
    private val sourceStep = sourceSampleRate.toDouble() / targetSampleRate
    private val monoSamples = ArrayDeque<Float>(2)
    private var pendingSampleCount = 0
    private var sourceFrameCount = 0L
    private var outputFrameCount = 0L
    private var monoBufferStartIndex = 0L
    private var finished = false

    internal var maximumRetainedSampleCount: Int = 0
        private set

    init {
        require(channelCount > 0) { "channelCount must be positive" }
        require(sourceSampleRate > 0) { "sourceSampleRate must be positive" }
        require(targetSampleRate > 0) { "targetSampleRate must be positive" }
    }

    /** Accepts interleaved samples normalized to [-1, 1]; emits mono [-1, 1]. */
    fun push(interleavedSamples: FloatArray): FloatArray {
        check(!finished) { "Normalizer is already finished" }
        if (interleavedSamples.isEmpty()) return FloatArray(0)

        val output = FloatArrayBuilder()
        interleavedSamples.forEach { sample ->
            pendingFrame[pendingSampleCount++] = sample
            updateMaximumRetainedSampleCount()
            if (pendingSampleCount == channelCount) {
                var sum = 0f
                repeat(channelCount) { channel ->
                    sum += pendingFrame[channel]
                }
                pendingSampleCount = 0
                acceptMono(sum / channelCount, output)
            }
        }
        return output.toArray()
    }

    fun finish(): FloatArray {
        check(!finished) { "Normalizer is already finished" }
        finished = true
        require(pendingSampleCount == 0) {
            "PCM sample count must be divisible by channelCount"
        }
        if (sourceFrameCount == 0L) return FloatArray(0)

        val output = FloatArrayBuilder()
        val targetFrameCount = if (sourceFrameCount == 1L) {
            1L
        } else {
            ((sourceFrameCount * targetSampleRate) / sourceSampleRate).coerceAtLeast(1L)
        }
        while (outputFrameCount < targetFrameCount) {
            val sourcePosition = outputFrameCount * sourceStep
            val leftIndex = floor(sourcePosition).toLong().coerceAtMost(sourceFrameCount - 1L)
            val left = bufferedMono(leftIndex)
            val right = bufferedMono((leftIndex + 1L).coerceAtMost(sourceFrameCount - 1L))
            val fraction = (sourcePosition - leftIndex).toFloat()
            output.add(left + (right - left) * fraction)
            outputFrameCount++
        }
        return output.toArray()
    }

    private fun acceptMono(sample: Float, output: FloatArrayBuilder) {
        monoSamples.addLast(sample)
        sourceFrameCount++

        val currentIndex = sourceFrameCount - 1L
        val availableTargetFrames = if (sourceSampleRate == targetSampleRate) {
            sourceFrameCount
        } else {
            ((sourceFrameCount * targetSampleRate) / sourceSampleRate).coerceAtLeast(1L)
        }
        while (outputFrameCount < availableTargetFrames) {
            val sourcePosition = outputFrameCount * sourceStep
            val leftIndex = floor(sourcePosition).toLong()
            val fraction = sourcePosition - leftIndex
            val ready = leftIndex < currentIndex ||
                (leftIndex == currentIndex && fraction == 0.0)
            if (!ready) break

            val left = bufferedMono(leftIndex)
            val right = if (fraction == 0.0) left else bufferedMono(leftIndex + 1L)
            output.add(left + (right - left) * fraction.toFloat())
            outputFrameCount++
        }
        discardUnneededMonoSamples(currentIndex)
        updateMaximumRetainedSampleCount()
    }

    private fun discardUnneededMonoSamples(currentIndex: Long) {
        val nextLeftIndex = floor(outputFrameCount * sourceStep).toLong()
        val earliestRequired = nextLeftIndex.coerceAtMost(currentIndex)
        while (monoSamples.size > 1 && monoBufferStartIndex < earliestRequired) {
            monoSamples.removeFirst()
            monoBufferStartIndex++
        }
    }

    private fun bufferedMono(index: Long): Float {
        val offset = (index - monoBufferStartIndex).toInt()
        check(offset in monoSamples.indices) { "Resampler retained insufficient source state" }
        return monoSamples.elementAt(offset)
    }

    private fun updateMaximumRetainedSampleCount() {
        maximumRetainedSampleCount = maxOf(
            maximumRetainedSampleCount,
            pendingSampleCount + monoSamples.size,
        )
    }

    private class FloatArrayBuilder {
        private var values = FloatArray(16)
        private var size = 0

        fun add(value: Float) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        fun toArray(): FloatArray = values.copyOf(size)
    }
}
