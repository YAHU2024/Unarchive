package com.unarchive.android.platform

import java.io.File

data class DownloadedAudio(
    val file: File,
    val byteCount: Long,
    val reused: Boolean,
)

fun interface DownloadProgressListener {
    fun onProgress(bytesDownloaded: Long, totalBytes: Long?)
}

interface AudioDownloader {
    suspend fun download(
        metadata: VideoMetadata,
        stream: AudioStream,
        progressListener: DownloadProgressListener,
    ): DownloadedAudio
}
