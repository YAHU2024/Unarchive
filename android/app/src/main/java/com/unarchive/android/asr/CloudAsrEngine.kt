package com.unarchive.android.asr

import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.unarchive.android.log.AppLogger
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Cloud transcription backend for the `CloudAsrEngine`. Implementations upload
 * an audio file and return plain transcribed text.
 */
fun interface CloudAsrClient {
    suspend fun transcribe(audioFile: File): String
}

/**
 * SiliconFlow `audio/transcriptions` client (OpenAI-compatible endpoint).
 * Uploads the audio as `multipart/form-data` and returns the `text` field.
 */
class SiliconFlowAsrClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.siliconflow.cn/v1",
    private val model: String = "FunAudioLLM/SenseVoiceSmall",
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 300_000,
) : CloudAsrClient {
    override suspend fun transcribe(audioFile: File): String = withContext(Dispatchers.IO) {
        val boundary = "----Unarchive${System.currentTimeMillis()}"
        val connection = URL("$baseUrl/audio/transcriptions").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            val uploadStart = android.os.SystemClock.elapsedRealtime()
            connection.outputStream.use { output ->
                writePart(output, boundary, "model", model)
                writeFilePart(output, boundary, "file", audioFile)
                output.write("--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
            }
            val uploadDone = android.os.SystemClock.elapsedRealtime()
            AppLogger.info("CloudAsrTiming", "上传耗时=${uploadDone - uploadStart}ms, 文件=${audioFile.length() / 1024}KB")

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream
                    ?.readBytes()?.toString(StandardCharsets.UTF_8).orEmpty()
                throw IOException("云端转写失败：HTTP $status $errorBody")
            }
            val response = connection.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            val readDone = android.os.SystemClock.elapsedRealtime()
            AppLogger.info("CloudAsrTiming", "服务端转写+响应耗时=${readDone - uploadDone}ms")
            JSONObject(response).optString("text", "")
        } finally {
            connection.disconnect()
        }
    }

    private fun writePart(output: java.io.OutputStream, boundary: String, name: String, value: String) {
        val sb = StringBuilder()
            .append("--$boundary\r\n")
            .append("Content-Disposition: form-data; name=\"$name\"\r\n")
            .append("\r\n")
            .append(value)
            .append("\r\n")
        output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeFilePart(output: java.io.OutputStream, boundary: String, name: String, file: File) {
        val header = StringBuilder()
            .append("--$boundary\r\n")
            .append("Content-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"\r\n")
            .append("Content-Type: ${contentTypeFor(file)}\r\n")
            .append("\r\n")
        output.write(header.toString().toByteArray(StandardCharsets.UTF_8))
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
        output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
    }

    private fun contentTypeFor(file: File): String = when (file.extension.lowercase()) {
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }
}

/**
 * Cloud ASR engine (SiliconFlow SenseVoice). Transcodes the audio to 16 kHz
 * AAC, uploads it, and returns the text as a single segment; the pipeline
 * fills in the true audio duration, since the cloud API returns plain text
 * without timestamps.
 */
class CloudAsrEngine(
    private val context: android.content.Context?,
    private val client: CloudAsrClient,
    private val transcoder: CloudAudioTranscoder = FfmpegAacTranscoder(),
) : AsrEngine {
    override val kind: AsrEngineKind = AsrEngineKind.SILICONFLOW_CLOUD

    override suspend fun transcribe(
        source: AudioSource,
        config: AsrConfig,
        progressListener: AsrProgressListener,
    ): AsrOutput {
        progressListener.onProgress(0.05f)
        val durationMs = withContext(Dispatchers.IO) { readAudioDurationMs(source.uri, context) }
        AppLogger.debug("CloudAsrEngine", "读取音频时长=${durationMs}ms source=${source.displayName}")
        val t0 = android.os.SystemClock.elapsedRealtime()
        val audio = transcoder.transcode(source.uri, context)
        val t1 = android.os.SystemClock.elapsedRealtime()
        AppLogger.info("CloudAsrTiming", "转码耗时=${t1 - t0}ms, 输出=${audio.length() / 1024}KB")
        try {
            progressListener.onProgress(0.5f)
            val text = client.transcribe(audio).trim()
            val t2 = android.os.SystemClock.elapsedRealtime()
            AppLogger.info("CloudAsrTiming", "上传+服务端转写耗时=${t2 - t1}ms")
            progressListener.onProgress(1f)
            if (text.isEmpty()) {
                throw IOException("云端转写返回空文本")
            }
            val segments = listOf(TranscriptSegment(0, durationMs, text))
            return AsrOutput(segments, audioDurationMs = durationMs)
        } finally {
            audio.delete()
        }
    }

    /**
     * The cloud API returns plain text with no duration or timestamps, so the
     * engine reads the source audio duration itself. Centralizing this here —
     * instead of in each caller — keeps every pipeline path (video and local
     * audio) returning a consistent duration.
     */
    private fun readAudioDurationMs(uri: String, context: android.content.Context?): Long =
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                val parsed = Uri.parse(uri)
                when (parsed.scheme?.lowercase()) {
                    ContentResolver.SCHEME_CONTENT -> retriever.setDataSource(
                        requireNotNull(context) { "content:// audio requires a Context" },
                        parsed,
                    )
                    else -> retriever.setDataSource(parsed.toString())
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
}
