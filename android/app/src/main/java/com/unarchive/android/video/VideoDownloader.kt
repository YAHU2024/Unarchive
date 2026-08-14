package com.unarchive.android.video

import com.unarchive.android.platform.DownloadProgressListener
import com.unarchive.android.platform.VideoStream
import com.unarchive.android.platform.bilibili.HttpsMediaDownloadTransport
import com.unarchive.android.platform.bilibili.MediaDownloadRequest
import com.unarchive.android.platform.bilibili.MediaDownloadTransport
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Downloads a DASH video stream into [cacheDirectory]. The file is treated as a
 * transient scratch asset: the caller deletes it after extracting frames.
 */
class VideoDownloader(
    private val cacheDirectory: File,
    private val transport: MediaDownloadTransport = HttpsMediaDownloadTransport(followRedirects = true),
) {
    suspend fun download(
        stream: VideoStream,
        videoId: String,
        progressListener: DownloadProgressListener,
    ): File {
        cacheDirectory.mkdirs()
        val destination = File(cacheDirectory, "$videoId.m4s")
        if (destination.isFile && destination.length() > 0) return destination

        val part = File(cacheDirectory, "$videoId.m4s.part")
        part.delete()
        val urls = (listOf(stream.url) + stream.backupUrls).distinct()
        var lastFailure: Exception? = null
        for (url in urls) {
            try {
                val byteCount = transport.download(
                    MediaDownloadRequest(
                        url = url,
                        headers = DOWNLOAD_HEADERS,
                        destination = part,
                        maximumBytes = MAXIMUM_VIDEO_BYTES,
                        progressListener = progressListener,
                    ),
                )
                require(byteCount > 0 && part.length() == byteCount) { "视频下载不完整" }
                if (!part.renameTo(destination)) {
                    part.copyTo(destination, overwrite = true)
                    part.delete()
                }
                return destination
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (e: Exception) {
                part.delete()
                lastFailure = e
            }
        }
        throw IllegalStateException(lastFailure?.message ?: "视频下载失败", lastFailure)
    }

    companion object {
        private const val MAXIMUM_VIDEO_BYTES = 2L * 1024 * 1024 * 1024
        private val DOWNLOAD_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://www.bilibili.com",
        )
    }
}
