package com.unarchive.android.asr

data class SpeechSampleRange(val startSample: Int, val endSample: Int) {
    init {
        require(startSample >= 0) { "startSample cannot be negative" }
        require(endSample >= startSample) { "endSample must not precede startSample" }
    }
}

object SpeechSegmentPlanner {
    fun withContext(
        speechRanges: List<SpeechSampleRange>,
        totalSamples: Int,
        contextSamples: Int,
        maximumSegmentSamples: Int,
    ): List<SpeechSampleRange> {
        require(totalSamples >= 0) { "totalSamples cannot be negative" }
        require(contextSamples >= 0) { "contextSamples cannot be negative" }
        require(maximumSegmentSamples > 0) { "maximumSegmentSamples must be positive" }

        val padded = speechRanges.map { range ->
            require(range.endSample <= totalSamples) { "Speech range exceeds audio duration" }
            SpeechSampleRange(
                startSample = (range.startSample - contextSamples).coerceAtLeast(0),
                endSample = (range.endSample + contextSamples).coerceAtMost(totalSamples),
            )
        }.filter { it.endSample > it.startSample }

        val merged = mutableListOf<SpeechSampleRange>()
        padded.forEach { range ->
            val previous = merged.lastOrNull()
            if (previous != null && range.startSample <= previous.endSample) {
                merged[merged.lastIndex] = SpeechSampleRange(
                    previous.startSample,
                    maxOf(previous.endSample, range.endSample),
                )
            } else {
                merged += range
            }
        }

        return merged.flatMap { range ->
            buildList {
                var start = range.startSample
                while (start < range.endSample) {
                    val end = (start.toLong() + maximumSegmentSamples)
                        .coerceAtMost(range.endSample.toLong())
                        .toInt()
                    add(SpeechSampleRange(start, end))
                    start = end
                }
            }
        }
    }
}
