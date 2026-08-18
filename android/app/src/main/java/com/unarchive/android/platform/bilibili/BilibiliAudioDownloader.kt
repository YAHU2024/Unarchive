package com.unarchive.android.platform.bilibili

import com.unarchive.android.log.AppLogger
import com.unarchive.android.platform.AudioDownloader
import com.unarchive.android.platform.AudioStream
import com.unarchive.android.platform.DownloadProgressListener
import com.unarchive.android.platform.DownloadedAudio
import com.unarchive.android.platform.VideoMetadata
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

data class MediaDownloadRequest(
    val url: String,
    val headers: Map<String, String>,
    val destination: File,
    val maximumBytes: Long,
    val progressListener: DownloadProgressListener,
)

fun interface MediaDownloadTransport {
    suspend fun download(request: MediaDownloadRequest): Long
}

class BilibiliAudioDownloader(
    private val cacheDirectory: File,
    private val transport: MediaDownloadTransport = HttpsMediaDownloadTransport(),
    private val maximumBytes: Long = DEFAULT_MAXIMUM_BYTES,
    private val cacheLifetimeMs: Long = DEFAULT_CACHE_LIFETIME_MS,
    private val wallClockEpochMs: () -> Long = System::currentTimeMillis,
) : AudioDownloader {
    init {
        require(maximumBytes > 0) { "maximumBytes must be positive" }
        require(cacheLifetimeMs >= 0) { "cacheLifetimeMs cannot be negative" }
    }

    override suspend fun download(
        metadata: VideoMetadata,
        stream: AudioStream,
        forceRefresh: Boolean,
        progressListener: DownloadProgressListener,
    ): DownloadedAudio {
        require(metadata.id.platform == PLATFORM && BVID_PATTERN.matches(metadata.id.value)) {
            "Bilibili download requires a canonical BV ID"
        }
        require(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            "Unable to create the audio cache directory"
        }
        val destination = File(cacheDirectory, "${metadata.id.value}.${extension(stream)}")
        val cacheMetadataFile = File(cacheDirectory, "${destination.name}.json")
        val now = wallClockEpochMs()
        val cachedMetadata = readCacheMetadata(cacheMetadataFile)
        if (
            !forceRefresh &&
            destination.isFile &&
            destination.length() in 1..maximumBytes &&
            cachedMetadata?.isReusable(metadata, destination.length(), now, cacheLifetimeMs) == true
        ) {
            AppLogger.info(TAG, "音频缓存复用 视频=${metadata.id.value} 大小=${destination.length()}字节")
            return DownloadedAudio(destination, destination.length(), reused = true)
        }

        val partial = File(cacheDirectory, "${destination.name}.part")
        val metadataPartial = File(cacheDirectory, "${cacheMetadataFile.name}.part")
        partial.delete()
        metadataPartial.delete()
        val urls = (listOf(stream.url) + stream.backupUrls).distinct()
        require(urls.isNotEmpty()) { "Bilibili audio stream has no URL" }
        var lastFailure: Exception? = null

        for (url in urls) {
            try {
                require(url.startsWith("https://", ignoreCase = true)) {
                    "Bilibili audio URL must use HTTPS"
                }
                val byteCount = transport.download(
                    MediaDownloadRequest(
                        url = url,
                        headers = DOWNLOAD_HEADERS,
                        destination = partial,
                        maximumBytes = maximumBytes,
                        progressListener = progressListener,
                    ),
                )
                require(byteCount in 1..maximumBytes && partial.length() == byteCount) {
                    "Downloaded Bilibili audio is incomplete"
                }
                val downloadedAt = wallClockEpochMs()
                val newMetadata = AudioCacheMetadata(
                    platform = metadata.id.platform,
                    videoId = metadata.id.value,
                    canonicalUrl = metadata.canonicalUrl,
                    byteCount = byteCount,
                    downloadedAtEpochMs = downloadedAt,
                    mimeType = stream.mimeType,
                    bandwidth = stream.bandwidth,
                )
                metadataPartial.writeText(newMetadata.toJson().toString(), Charsets.UTF_8)
                finalizeCache(
                    audioPartial = partial,
                    audioDestination = destination,
                    metadataPartial = metadataPartial,
                    metadataDestination = cacheMetadataFile,
                )
                AppLogger.info(TAG, "音频下载完成 视频=${metadata.id.value} 大小=${byteCount}字节")
                return DownloadedAudio(destination, byteCount, reused = false)
            } catch (error: CancellationException) {
                partial.delete()
                metadataPartial.delete()
                throw error
            } catch (error: Exception) {
                partial.delete()
                metadataPartial.delete()
                lastFailure = error
            }
        }

        throw IllegalStateException(
            lastFailure?.message ?: "Unable to download Bilibili audio",
            lastFailure,
        )
    }

    private fun extension(stream: AudioStream): String = when {
        stream.mimeType == "audio/mp4" -> "m4a"
        stream.mimeType == "audio/webm" -> "webm"
        else -> "audio"
    }

    private fun readCacheMetadata(file: File): AudioCacheMetadata? = runCatching {
        JSONObject(file.readText(Charsets.UTF_8)).toAudioCacheMetadata()
    }.getOrNull()

    private fun replaceFile(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun finalizeCache(
        audioPartial: File,
        audioDestination: File,
        metadataPartial: File,
        metadataDestination: File,
    ) {
        val audioBackup = File(cacheDirectory, "${audioDestination.name}.backup")
        val metadataBackup = File(cacheDirectory, "${metadataDestination.name}.backup")
        audioBackup.delete()
        metadataBackup.delete()
        try {
            if (audioDestination.exists()) replaceFile(audioDestination, audioBackup)
            if (metadataDestination.exists()) replaceFile(metadataDestination, metadataBackup)
            replaceFile(audioPartial, audioDestination)
            replaceFile(metadataPartial, metadataDestination)
        } catch (error: Exception) {
            audioDestination.delete()
            metadataDestination.delete()
            if (audioBackup.exists()) replaceFile(audioBackup, audioDestination)
            if (metadataBackup.exists()) replaceFile(metadataBackup, metadataDestination)
            throw error
        } finally {
            audioBackup.delete()
            metadataBackup.delete()
        }
    }

    private companion object {
        const val TAG = "AudioDownload"
        const val PLATFORM = "bilibili"
        val BVID_PATTERN = Regex("BV[0-9A-Za-z]{10}")
        val DOWNLOAD_HEADERS = BilibiliHeaders.media
        const val DEFAULT_MAXIMUM_BYTES = 200L * 1024 * 1024
        const val DEFAULT_CACHE_LIFETIME_MS = 7L * 24 * 60 * 60 * 1_000
    }
}

private data class AudioCacheMetadata(
    val platform: String,
    val videoId: String,
    val canonicalUrl: String,
    val byteCount: Long,
    val downloadedAtEpochMs: Long,
    val mimeType: String?,
    val bandwidth: Long,
) {
    fun isReusable(
        video: VideoMetadata,
        actualByteCount: Long,
        nowEpochMs: Long,
        lifetimeMs: Long,
    ): Boolean =
        platform == video.id.platform &&
            videoId == video.id.value &&
            canonicalUrl == video.canonicalUrl &&
            byteCount == actualByteCount &&
            downloadedAtEpochMs in 0..nowEpochMs &&
            nowEpochMs - downloadedAtEpochMs <= lifetimeMs

    fun toJson() = JSONObject()
        .put("schema_version", 1)
        .put("platform", platform)
        .put("video_id", videoId)
        .put("canonical_url", canonicalUrl)
        .put("byte_count", byteCount)
        .put("downloaded_at_epoch_ms", downloadedAtEpochMs)
        .put("mime_type", mimeType)
        .put("bandwidth", bandwidth)
}

private fun JSONObject.toAudioCacheMetadata(): AudioCacheMetadata {
    require(getInt("schema_version") == 1) { "Unsupported audio cache schema" }
    return AudioCacheMetadata(
        platform = getString("platform"),
        videoId = getString("video_id"),
        canonicalUrl = getString("canonical_url"),
        byteCount = getLong("byte_count"),
        downloadedAtEpochMs = getLong("downloaded_at_epoch_ms"),
        mimeType = optString("mime_type").takeIf { it.isNotEmpty() },
        bandwidth = getLong("bandwidth"),
    )
}
