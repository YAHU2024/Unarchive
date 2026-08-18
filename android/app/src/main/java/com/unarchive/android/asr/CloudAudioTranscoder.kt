package com.unarchive.android.asr

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Converts an audio container into an AAC (ADTS) file for upload.
 *
 * The SiliconFlow endpoint rejects M4A/MP4 containers, and it accepts both
 * 16 kHz mono AAC and the original (e.g. 44.1 kHz stereo) AAC stream. So we
 * prefer a **stream copy** (`-c:a copy`) that only remuxes the container and
 * performs no re-encoding at all. This matters on-device: ffmpeg-kit-min's
 * AAC encoder is slow (~28x realtime), so re-encoding a 12-minute clip takes
 * ~25 s, whereas a stream copy is pure I/O and finishes in well under a
 * second. We fall back to a full 16 kHz mono re-encode only when the source
 * is not AAC (stream copy into ADTS then fails).
 *
 * Accepts both `file://` and `content://` URIs (the latter resolved through
 * SAF via [FFmpegKitConfig.getSafParameterForRead]).
 */
fun interface CloudAudioTranscoder {
    /** Returns a newly created AAC file the caller owns and must delete. */
    suspend fun transcode(sourceUri: String, context: Context?): File
}

/** FFmpeg-backed transcoder that prefers stream copy over re-encoding. */
class FfmpegAacTranscoder : CloudAudioTranscoder {
    override suspend fun transcode(sourceUri: String, context: Context?): File {
        val inputArgument = inputArgument(Uri.parse(sourceUri), context)

        // Fast path: stream copy the AAC stream into an ADTS container.
        val copyOutput = File.createTempFile("cloud_asr_", ".aac")
        val copySession = runFfmpeg(
            arrayOf(
                "-hide_banner", "-nostdin", "-y",
                "-i", inputArgument,
                "-map", "0:a:0",
                "-vn",
                "-c:a", "copy",
                "-f", "adts",
                copyOutput.absolutePath,
            ),
        )
        if (ReturnCode.isSuccess(copySession.returnCode) && copyOutput.length() > 0) {
            return copyOutput
        }
        copyOutput.delete()

        // Fallback: full re-encode to 16 kHz mono AAC (source is not AAC).
        val reOutput = File.createTempFile("cloud_asr_", ".aac")
        val reSession = runFfmpeg(
            arrayOf(
                "-hide_banner", "-nostdin", "-y",
                "-i", inputArgument,
                "-map", "0:a:0",
                "-vn",
                "-ac", "1",
                "-ar", "16000",
                "-c:a", "aac",
                "-b:a", "64k",
                "-f", "adts",
                reOutput.absolutePath,
            ),
        )
        check(ReturnCode.isSuccess(reSession.returnCode)) {
            "云端转写音频转码失败：returnCode=${reSession.returnCode}\n" +
                reSession.allLogsAsString.orEmpty().takeLast(2_000)
        }
        return reOutput
    }

    private suspend fun runFfmpeg(arguments: Array<String>): FFmpegSession =
        suspendCancellableCoroutine { continuation ->
            val running = FFmpegKit.executeWithArgumentsAsync(arguments) { completed ->
                if (continuation.isActive) continuation.resume(completed)
            }
            continuation.invokeOnCancellation { running.cancel() }
        }

    private fun inputArgument(uri: Uri, context: Context?): String = when (uri.scheme?.lowercase()) {
        ContentResolver.SCHEME_FILE -> requireNotNull(uri.path) { "Selected file URI has no path" }
        ContentResolver.SCHEME_CONTENT -> FFmpegKitConfig.getSafParameterForRead(
            requireNotNull(context) { "content:// audio requires a Context" },
            uri,
        )
        else -> uri.toString()
    }
}
