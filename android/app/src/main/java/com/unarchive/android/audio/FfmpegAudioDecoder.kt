package com.unarchive.android.audio

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** FFmpeg container decode into a validated, atomically-published PCM16 WAV. */
class FfmpegAudioDecoder(
    private val context: Context,
) {
    suspend fun decodeToCache(
        uri: Uri,
        cache: DecodedAudioCache,
        fingerprint: String,
        targetSampleRate: Int,
    ): File {
        require(targetSampleRate > 0) { "targetSampleRate must be positive" }
        val sink = cache.newFileSink(fingerprint)
        try {
            val session = execute(
                arrayOf(
                    "-hide_banner",
                    "-nostdin",
                    "-y",
                    "-i", inputArgument(uri),
                    "-map", "0:a:0",
                    "-vn",
                    "-ac", "1",
                    "-ar", targetSampleRate.toString(),
                    "-c:a", "pcm_s16le",
                    "-f", "wav",
                    sink.partFile.absolutePath,
                ),
            )
            if (ReturnCode.isCancel(session.returnCode)) {
                throw CancellationException("FFmpeg audio decode cancelled")
            }
            check(ReturnCode.isSuccess(session.returnCode)) {
                "FFmpeg audio decode failed: returnCode=${session.returnCode}\n" +
                    session.allLogsAsString.orEmpty().takeLast(MAX_ERROR_LOG_CHARS)
            }
            Pcm16WavInspector.inspect(sink.partFile, targetSampleRate)
            return sink.finish()
        } catch (error: Throwable) {
            sink.abort()
            throw error
        }
    }

    private fun inputArgument(uri: Uri): String = when (uri.scheme?.lowercase()) {
        null, "" -> uri.toString()
        ContentResolver.SCHEME_FILE -> requireNotNull(uri.path) { "Selected file URI has no path" }
        ContentResolver.SCHEME_CONTENT -> FFmpegKitConfig.getSafParameterForRead(context, uri)
        else -> uri.toString()
    }

    private suspend fun execute(arguments: Array<String>): FFmpegSession =
        suspendCancellableCoroutine { continuation ->
            val session = FFmpegKit.executeWithArgumentsAsync(arguments) { completed ->
                if (continuation.isActive) continuation.resume(completed)
            }
            continuation.invokeOnCancellation { session.cancel() }
        }

    private companion object {
        const val MAX_ERROR_LOG_CHARS = 2_000
    }
}
