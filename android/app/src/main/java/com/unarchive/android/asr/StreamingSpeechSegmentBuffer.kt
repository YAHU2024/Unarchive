package com.unarchive.android.asr

class StreamingSpeechSegmentBuffer(
    private val contextSamples: Int,
    private val maximumSegmentSamples: Int,
    private val historySamples: Int,
    private val onSegment: (BufferedSpeechSegment) -> Unit,
) {
    private val chunks = ArrayDeque<AudioChunk>()
    private var totalSamples = 0L
    private var pendingRange: PendingRange? = null
    private var speechActive = false
    private var activeRetainStart: Long? = null

    internal var maximumRetainedSamples = 0L
        private set

    init {
        require(contextSamples >= 0) { "contextSamples cannot be negative" }
        require(maximumSegmentSamples > 0) { "maximumSegmentSamples must be positive" }
        require(historySamples >= contextSamples) {
            "historySamples must cover contextSamples"
        }
    }

    fun append(samples: FloatArray) {
        if (samples.isEmpty()) return
        chunks.addLast(AudioChunk(totalSamples, samples))
        totalSamples += samples.size
        updateMaximumRetainedSamples()
        emitFullSegments()
        drainReady(force = false)
    }

    fun updateSpeechActive(active: Boolean) {
        if (active && !speechActive) {
            activeRetainStart = (totalSamples - historySamples).coerceAtLeast(0L)
        }
        speechActive = active
        if (!active && pendingRange == null) activeRetainStart = null
        drainReady(force = false)
    }

    fun addSpeechRange(startSample: Long, endSample: Long) {
        require(startSample >= 0) { "startSample cannot be negative" }
        require(endSample >= startSample) { "endSample must not precede startSample" }
        if (endSample == startSample) return

        // A delayed VAD range (e.g. audio ending mid-speech) can start before the
        // retained audio window. Clamp it to what is still available; drop the
        // range entirely if its samples are already gone.
        val earliestAvailable = chunks.firstOrNull()?.availableStart ?: totalSamples
        val clampedStart = maxOf(startSample, earliestAvailable + contextSamples)
        if (clampedStart >= endSample) return

        // Fixed context padding on both sides. Streaming emission guarantees
        // the window never reaches the neighbouring speech: a segment is only
        // released after `contextSamples` of separation arrive, and a later
        // range is clamped to start at or after the released padded end, so
        // padding covers silence only.
        val padded = PendingRange(
            rawStart = clampedStart,
            rawEnd = endSample,
            start = (clampedStart - contextSamples).coerceAtLeast(0L),
            end = endSample + contextSamples,
        )
        val current = pendingRange
        pendingRange = when {
            current == null -> padded
            padded.start <= current.end -> PendingRange(
                rawStart = current.rawStart,
                rawEnd = maxOf(current.rawEnd, padded.rawEnd),
                start = current.start,
                end = maxOf(current.end, padded.end),
            )
            else -> {
                emitRange(current, end = minOf(current.end, totalSamples))
                padded
            }
        }
        activeRetainStart = null
        emitFullSegments()
    }

    fun finish() {
        speechActive = false
        activeRetainStart = null
        drainReady(force = true)
        trimBefore(totalSamples)
    }

    private fun drainReady(force: Boolean) {
        emitFullSegments()
        val current = pendingRange ?: run {
            trimReleasedAudio()
            return
        }
        val availableEnd = minOf(current.end, totalSamples)
        val enoughSeparation = totalSamples >= current.end + contextSamples
        if (force || (!speechActive && enoughSeparation)) {
            emitRange(current, end = availableEnd)
            pendingRange = null
            trimReleasedAudio()
        }
    }

    private fun emitFullSegments() {
        var current = pendingRange ?: return
        while (current.end - current.start >= maximumSegmentSamples &&
            totalSamples >= current.start + maximumSegmentSamples
        ) {
            val end = current.start + maximumSegmentSamples
            emitRange(current, end = end)
            current = current.advanceStart(end)
            pendingRange = current
        }
    }

    private fun emitRange(range: PendingRange, end: Long) {
        if (end <= range.start) return
        val size = (end - range.start).toInt()
        require(size <= maximumSegmentSamples) { "Speech segment exceeded the configured maximum" }
        val availableStart = chunks.firstOrNull()?.availableStart ?: totalSamples
        require(range.start >= availableStart && end <= totalSamples) {
            "Speech segment audio is no longer available " +
                "(range=${range.start}-$end availableStart=$availableStart totalSamples=$totalSamples)"
        }
        val samples = FloatArray(size)
        chunks.forEach { chunk ->
            val copyStart = maxOf(range.start, chunk.availableStart)
            val copyEnd = minOf(end, chunk.end)
            if (copyEnd > copyStart) {
                chunk.samples.copyInto(
                    destination = samples,
                    destinationOffset = (copyStart - range.start).toInt(),
                    startIndex = (copyStart - chunk.start).toInt(),
                    endIndex = (copyEnd - chunk.start).toInt(),
                )
            }
        }
        // Raw (speech-only) portion of this emitted block.
        val rawStart = maxOf(range.rawStart, range.start)
        val rawEnd = minOf(range.rawEnd, end)
        onSegment(
            BufferedSpeechSegment(
                startSample = range.start,
                endSample = end,
                samples = samples,
                rawStartSample = rawStart,
                rawEndSample = rawEnd,
            ),
        )
        trimBefore(end)
    }

    private fun trimReleasedAudio() {
        val retainFrom = when {
            pendingRange != null -> pendingRange!!.start
            speechActive -> activeRetainStart ?: (totalSamples - historySamples).coerceAtLeast(0L)
            else -> (totalSamples - historySamples).coerceAtLeast(0L)
        }
        trimBefore(retainFrom)
    }

    private fun trimBefore(sampleIndex: Long) {
        while (chunks.isNotEmpty()) {
            val first = chunks.first()
            if (first.end <= sampleIndex) {
                chunks.removeFirst()
            } else {
                first.discardBefore(sampleIndex)
                break
            }
        }
    }

    private fun updateMaximumRetainedSamples() {
        maximumRetainedSamples = maxOf(
            maximumRetainedSamples,
            chunks.sumOf { it.end - it.availableStart },
        )
    }

    private data class PendingRange(
        val rawStart: Long,
        val rawEnd: Long,
        val start: Long,
        val end: Long,
    ) {
        fun advanceStart(newStart: Long): PendingRange = PendingRange(rawStart, rawEnd, newStart, end)
    }

    private class AudioChunk(
        val start: Long,
        val samples: FloatArray,
    ) {
        private var offset = 0
        val availableStart: Long get() = start + offset
        val end: Long get() = start + samples.size

        fun discardBefore(sampleIndex: Long) {
            offset = maxOf(offset, (sampleIndex - start).coerceAtLeast(0L).toInt())
                .coerceAtMost(samples.size)
        }
    }
}

data class BufferedSpeechSegment(
    val startSample: Long,
    val endSample: Long,
    val samples: FloatArray,
    /** VAD speech boundary excluding recognition context padding. */
    val rawStartSample: Long,
    val rawEndSample: Long,
)
