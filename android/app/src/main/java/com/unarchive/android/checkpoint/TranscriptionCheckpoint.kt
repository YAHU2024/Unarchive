package com.unarchive.android.checkpoint

import com.unarchive.android.asr.AsrConfig
import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.result.VideoResultKey

data class TranscriptionSourceIdentity(
    val key: VideoResultKey,
    val canonicalUrl: String,
    val byteCount: Long,
    val modifiedAtEpochMs: Long,
    val contentFingerprint: String,
)

data class TranscriptionConfigIdentity(
    val engine: String,
    val language: String,
    val sampleRateHz: Int,
    val enableVad: Boolean,
    val contextPaddingMs: Int,
    val pipelineVersion: Int = PIPELINE_VERSION,
) {
    companion object {
        const val PIPELINE_VERSION = 1

        fun from(config: AsrConfig) = TranscriptionConfigIdentity(
            engine = config.engine.name,
            language = config.language,
            sampleRateHz = config.sampleRateHz,
            enableVad = config.enableVad,
            contextPaddingMs = config.contextPaddingMs,
        )
    }
}

data class TranscriptionCheckpoint(
    val source: TranscriptionSourceIdentity,
    val config: TranscriptionConfigIdentity,
    val audioDurationMs: Long,
    val completedThroughMs: Long,
    val segments: List<TranscriptSegment>,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    init {
        require(audioDurationMs >= 0) { "audioDurationMs cannot be negative" }
        require(completedThroughMs >= 0) { "completedThroughMs cannot be negative" }
    }
}

data class ResumePlan(
    val checkpoint: TranscriptionCheckpoint,
    val resumeStartMs: Long,
    val replaceFromMs: Long,
)

object TranscriptionResumePlanner {
    const val OVERLAP_MS = 3_000L

    fun plan(
        checkpoint: TranscriptionCheckpoint?,
        source: TranscriptionSourceIdentity,
        config: TranscriptionConfigIdentity,
    ): ResumePlan? = checkpoint
        ?.takeIf { it.source == source && it.config == config && it.completedThroughMs > 0 }
        ?.let { validCheckpoint ->
            val boundaryOverlapStart =
                (validCheckpoint.completedThroughMs - OVERLAP_MS).coerceAtLeast(0)
            val firstAffectedSegment = validCheckpoint.segments.firstOrNull {
                it.endMs > boundaryOverlapStart
            }
            val replaceFrom = firstAffectedSegment?.startMs ?: boundaryOverlapStart
            val resumeStart = firstAffectedSegment
                ?.let { (it.startMs - OVERLAP_MS).coerceAtLeast(0) }
                ?: boundaryOverlapStart
            ResumePlan(validCheckpoint, resumeStart, replaceFrom)
        }
}
