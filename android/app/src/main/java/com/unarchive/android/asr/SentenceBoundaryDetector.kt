package com.unarchive.android.asr

import kotlin.math.sqrt

/**
 * Splits a recognized speech segment into sentence-sized sub-ranges by
 * locating pauses (low-energy runs) inside the segment.
 *
 * Silero VAD segments break only at silence runs >= minSilenceDuration
 * (0.5 s by default), so a single VAD range can still contain several
 * sentences glued together. SenseVoice int8 emits sentence-final punctuation
 * sparsely, which leaves no reliable text anchor to split on; re-splitting
 * the segment audio at internal pauses restores sentence-level timestamp
 * blocks without any extra model.
 *
 * Energy is measured per 20 ms frame (RMS). A boundary is accepted only when
 * the pause is at least [minPauseMs] long and both neighboring sub-segments
 * keep at least [minSubSegmentSeconds] of speech, so short interjections are
 * not cut off.
 */
object SentenceBoundaryDetector {
    /**
     * Returns sample indices at which [samples] should be split, or an empty
     * array when the segment has no usable internal pause.
     */
    fun findSplitSamples(
        samples: FloatArray,
        sampleRate: Int,
        minPauseMs: Int = DEFAULT_MIN_PAUSE_MS,
        minSubSegmentSeconds: Int = DEFAULT_MIN_SUB_SEGMENT_SECONDS,
        frameMs: Int = DEFAULT_FRAME_MS,
    ): IntArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(minPauseMs >= 0) { "minPauseMs cannot be negative" }
        require(minSubSegmentSeconds > 0) { "minSubSegmentSeconds must be positive" }
        require(frameMs > 0) { "frameMs must be positive" }
        if (samples.isEmpty()) return IntArray(0)

        val frameSize = sampleRate * frameMs / 1_000
        val frameCount = samples.size / frameSize
        if (frameCount < 2) return IntArray(0)

        val frameRms = FloatArray(frameCount)
        var totalEnergy = 0.0
        repeat(frameCount) { frame ->
            var sumSquares = 0.0
            val start = frame * frameSize
            for (index in start until start + frameSize) {
                val sample = samples[index].toDouble()
                sumSquares += sample * sample
            }
            val rms = sqrt(sumSquares / frameSize)
            frameRms[frame] = rms.toFloat()
            totalEnergy += sumSquares
        }
        val segmentRms = sqrt(totalEnergy / samples.size).toFloat()
        // Pause floor: quiet enough to be a break, but scaled to the segment
        // so loud-but-low relative energy stays a candidate pause.
        val threshold = (segmentRms * RELATIVE_PAUSE_RATIO).coerceIn(MIN_PAUSE_RMS, MAX_PAUSE_RMS)

        val minimumPauseFrames = (minPauseMs / frameMs).coerceAtLeast(1)
        val minimumSubSegmentFrames = minSubSegmentSeconds * 1_000 / frameMs

        val boundaries = mutableListOf<Int>()
        var frame = 0
        while (frame < frameCount) {
            if (frameRms[frame] >= threshold) {
                frame++
                continue
            }
            var pauseEnd = frame
            while (pauseEnd < frameCount && frameRms[pauseEnd] < threshold) pauseEnd++
            val pauseFrames = pauseEnd - frame
            val speechBefore = frame
            // Count real speech frames after the pause (excluding any trailing
            // silence such as the segment's context padding), so a pause that
            // merges into the tail cannot create a tiny final sub-segment.
            var speechAfter = 0
            for (candidate in pauseEnd until frameCount) {
                if (frameRms[candidate] >= threshold) speechAfter++
            }
            if (
                pauseFrames >= minimumPauseFrames &&
                speechBefore >= minimumSubSegmentFrames &&
                speechAfter >= minimumSubSegmentFrames
            ) {
                // Split at the middle of the pause to avoid clipping the edges.
                val splitFrame = frame + pauseFrames / 2
                boundaries.add((splitFrame * frameSize).coerceIn(0, samples.size))
            }
            frame = pauseEnd
        }
        return boundaries.toIntArray()
    }

    private const val RELATIVE_PAUSE_RATIO = 0.15f
    private const val MIN_PAUSE_RMS = 0.005f
    private const val MAX_PAUSE_RMS = 0.05f
    private const val DEFAULT_MIN_PAUSE_MS = 250
    private const val DEFAULT_MIN_SUB_SEGMENT_SECONDS = 2
    private const val DEFAULT_FRAME_MS = 20
}
