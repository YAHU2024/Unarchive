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

interface VideoPlatformAdapter {
    val platform: String

    fun parseReference(input: String): VideoReference

    suspend fun resolveReference(input: String): VideoReference.Canonical

    suspend fun fetchMetadata(reference: VideoReference.Canonical): VideoMetadata

    suspend fun resolveAudio(metadata: VideoMetadata): AudioStream
}

sealed interface VideoReference {
    data class Canonical(val id: PlatformVideoId, val url: String) : VideoReference
    data class Redirect(val url: String) : VideoReference
}
