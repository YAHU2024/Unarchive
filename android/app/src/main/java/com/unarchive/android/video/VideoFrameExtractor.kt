package com.unarchive.android.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Extracts JPEG frames from a local video file at the given timestamps using
 * [MediaMetadataRetriever]. Returns null for timestamps where no frame could be
 * decoded (e.g. past the end of the video).
 */
class VideoFrameExtractor {
    fun extractFrames(videoFile: File, timestampsMs: List<Long>): List<ByteArray?> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            return timestampsMs.map { timestampMs ->
                val bitmap = retriever.getFrameAtTime(
                    timestampMs * 1_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
                if (bitmap == null) {
                    null
                } else {
                    try {
                        compressToJpeg(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun compressToJpeg(bitmap: Bitmap, quality: Int = 70): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        return output.toByteArray()
    }
}
