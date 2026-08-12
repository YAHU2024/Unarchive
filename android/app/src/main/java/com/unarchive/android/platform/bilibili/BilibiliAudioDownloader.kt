package com.unarchive.android.platform.bilibili

import com.unarchive.android.platform.AudioDownloader
import com.unarchive.android.platform.AudioStream
import com.unarchive.android.platform.DownloadProgressListener
import com.unarchive.android.platform.DownloadedAudio
import com.unarchive.android.platform.VideoMetadata
import java.io.File
import kotlinx.coroutines.CancellationException

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
) : AudioDownloader {
    init {
        require(maximumBytes > 0) { "maximumBytes must be positive" }
    }

    override suspend fun download(
        metadata: VideoMetadata,
        stream: AudioStream,
        progressListener: DownloadProgressListener,
    ): DownloadedAudio {
        require(metadata.id.platform == PLATFORM && BVID_PATTERN.matches(metadata.id.value)) {
            "Bilibili download requires a canonical BV ID"
        }
        require(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            "Unable to create the audio cache directory"
        }
        val destination = File(cacheDirectory, "${metadata.id.value}.${extension(stream)}")
        if (destination.isFile && destination.length() in 1..maximumBytes) {
            return DownloadedAudio(destination, destination.length(), reused = true)
        }

        val partial = File(cacheDirectory, "${destination.name}.part")
        partial.delete()
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
                if (destination.exists() && !destination.delete()) {
                    throw IllegalStateException("Unable to replace cached Bilibili audio")
                }
                require(partial.renameTo(destination)) {
                    "Unable to finalize downloaded Bilibili audio"
                }
                return DownloadedAudio(destination, byteCount, reused = false)
            } catch (error: CancellationException) {
                partial.delete()
                throw error
            } catch (error: Exception) {
                partial.delete()
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

    private companion object {
        const val PLATFORM = "bilibili"
        val BVID_PATTERN = Regex("BV[0-9A-Za-z]{10}")
        val DOWNLOAD_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) Unarchive/0.1",
            "Referer" to "https://www.bilibili.com/",
        )
        const val DEFAULT_MAXIMUM_BYTES = 200L * 1024 * 1024
    }
}
