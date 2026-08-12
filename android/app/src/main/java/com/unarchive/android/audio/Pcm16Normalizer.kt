package com.unarchive.android.audio

import kotlin.math.floor

object Pcm16Normalizer {
    fun toMonoFloat(
        interleavedSamples: ShortArray,
        channelCount: Int,
        sourceSampleRate: Int,
        targetSampleRate: Int,
    ): FloatArray {
        require(channelCount > 0) { "channelCount must be positive" }
        require(sourceSampleRate > 0) { "sourceSampleRate must be positive" }
        require(targetSampleRate > 0) { "targetSampleRate must be positive" }
        require(interleavedSamples.size % channelCount == 0) {
            "PCM sample count must be divisible by channelCount"
        }

        val sourceFrames = interleavedSamples.size / channelCount
        if (sourceFrames == 0) return FloatArray(0)
        val mono = FloatArray(sourceFrames) { frame ->
            var sum = 0f
            repeat(channelCount) { channel ->
                sum += interleavedSamples[frame * channelCount + channel] / 32768f
            }
            sum / channelCount
        }
        if (sourceSampleRate == targetSampleRate || sourceFrames == 1) return mono

        val targetFrames = ((sourceFrames.toLong() * targetSampleRate) / sourceSampleRate)
            .coerceAtLeast(1)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val sourceStep = sourceSampleRate.toDouble() / targetSampleRate
        return FloatArray(targetFrames) { targetIndex ->
            val sourcePosition = targetIndex * sourceStep
            val left = floor(sourcePosition).toInt().coerceAtMost(sourceFrames - 1)
            val right = (left + 1).coerceAtMost(sourceFrames - 1)
            val fraction = (sourcePosition - left).toFloat()
            mono[left] + (mono[right] - mono[left]) * fraction
        }
    }
}
