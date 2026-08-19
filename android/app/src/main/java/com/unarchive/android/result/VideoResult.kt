package com.unarchive.android.result

import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.pipeline.SingleVideoResult

/** Platform marker for locally benchmarked audio results (no upstream video). */
const val LOCAL_AUDIO_PLATFORM = "local-audio"

data class VideoResultKey(
    val platform: String,
    val videoId: String,
) {
    init {
        require(platform.isNotBlank()) { "platform cannot be blank" }
        require(videoId.isNotBlank()) { "videoId cannot be blank" }
    }
}

data class StoredVideoResult(
    val key: VideoResultKey,
    val canonicalUrl: String,
    val title: String,
    val ownerName: String,
    val videoDurationSeconds: Long,
    val engine: AsrEngineKind,
    val processingDurationMs: Long,
    val audioDurationMs: Long,
    val segments: List<TranscriptSegment>,
    val timingAccuracy: TranscriptTimingAccuracy = TranscriptTimingAccuracy.EXACT,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /**
     * Signature of the AsrConfig that produced this result. Blank for results
     * saved before this field existed; such results are never reused as-is.
     */
    val configSignature: String = "",
) {
    val transcript: String
        get() = segments.joinToString(separator = "\n") { it.text }

    fun shareText(): String = buildString {
        appendLine(title)
        if (ownerName.isNotBlank()) appendLine(ownerName)
        appendLine(canonicalUrl)
        appendLine()
        segments.forEach { segment ->
            appendLine("[${segment.startMs.asTimestamp()} - ${segment.endMs.asTimestamp()}] ${segment.text}")
        }
    }.trim()

    companion object {
        fun fromPipeline(
            result: SingleVideoResult,
            nowEpochMs: Long,
            configSignature: String,
            createdAtEpochMs: Long = nowEpochMs,
        ) = StoredVideoResult(
            key = VideoResultKey(result.metadata.id.platform, result.metadata.id.value),
            canonicalUrl = result.metadata.canonicalUrl,
            title = result.metadata.title,
            ownerName = result.metadata.ownerName,
            videoDurationSeconds = result.metadata.durationSeconds,
            engine = result.benchmark.engine,
            processingDurationMs = result.benchmark.processingDurationMs,
            audioDurationMs = result.benchmark.audioDurationMs,
            segments = result.benchmark.segments,
            timingAccuracy = result.benchmark.timingAccuracy,
            createdAtEpochMs = createdAtEpochMs,
            updatedAtEpochMs = nowEpochMs,
            configSignature = configSignature,
        )
    }
}

fun Long.asTimestamp(): String {
    val totalSeconds = (this / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
