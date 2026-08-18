package com.unarchive.android.platform

data class PlatformVideoId(val platform: String, val value: String)

data class VideoMetadata(
    val id: PlatformVideoId,
    val canonicalUrl: String,
    val title: String,
    val ownerName: String,
    val durationSeconds: Long,
    val cid: Long,
)

data class AudioStream(
    val url: String,
    val backupUrls: List<String>,
    val bandwidth: Long,
    val mimeType: String?,
    val codecs: String?,
)

data class VideoStream(
    val url: String,
    val backupUrls: List<String>,
    val bandwidth: Long,
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val codecs: String?,
)

/**
 * A single platform CC-subtitle cue, aligned to milliseconds so callers can
 * treat it like a [com.unarchive.android.asr.TranscriptSegment] with a single
 * mapping. Platform adapters return this shape so the platform layer stays
 * independent of the ASR model types.
 */
data class SubtitleSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

interface VideoPlatformAdapter {
    val platform: String

    fun parseReference(input: String): VideoReference

    suspend fun resolveReference(input: String): VideoReference.Canonical

    suspend fun fetchMetadata(reference: VideoReference.Canonical): VideoMetadata

    suspend fun resolveAudio(metadata: VideoMetadata): AudioStream

    suspend fun resolveVideo(metadata: VideoMetadata): VideoStream

    /**
     * Returns the video's platform CC/AI subtitles, or null when the video has
     * no subtitles or they cannot be fetched. Implementations must swallow
     * network/parse failures and return null so the caller can fall back to
     * local ASR.
     */
    suspend fun fetchSubtitles(metadata: VideoMetadata): List<SubtitleSegment>?
}

sealed interface VideoReference {
    data class Canonical(val id: PlatformVideoId, val url: String) : VideoReference
    data class Redirect(val url: String) : VideoReference
}
