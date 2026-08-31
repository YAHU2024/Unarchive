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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Cloud transcription backend for the `CloudAsrEngine`. Implementations upload
 * an audio file and return plain transcribed text. The provider's default
 * response format is intentionally used because newer ASR models may reject
 * the optional `response_format` multipart field.
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
    private val model: String = SiliconFlowModelCatalog.DEFAULT_MODEL,
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 300_000,
    private val maxServiceUnavailableRetries: Int = 2,
    private val retryBaseDelayMs: Long = 1_000L,
) : CloudAsrClient {
    init {
        require(apiKey.isNotBlank()) { "SiliconFlow API key must not be blank" }
        require(SiliconFlowModelCatalog.normalize(model) == model) {
            "SiliconFlow model must be a normalized model identifier"
        }
        require(maxServiceUnavailableRetries >= 0) {
            "maxServiceUnavailableRetries cannot be negative"
        }
        require(retryBaseDelayMs >= 0) { "retryBaseDelayMs cannot be negative" }
    }

    override suspend fun transcribe(audioFile: File): String = withContext(Dispatchers.IO) {
        AppLogger.info("CloudAsr", "开始请求 SiliconFlow ASR 模型=$model")
        var serviceUnavailableRetries = 0
        while (true) {
            try {
                return@withContext transcribeOnce(audioFile)
            } catch (error: SiliconFlowHttpException) {
                if (error.statusCode != HttpURLConnection.HTTP_UNAVAILABLE ||
                    serviceUnavailableRetries >= maxServiceUnavailableRetries
                ) {
                    throw error
                }

                val retryNumber = serviceUnavailableRetries + 1
                serviceUnavailableRetries = retryNumber
                val delayMs = error.retryAfterMs
                    ?: retryBaseDelayMs * (1L shl (retryNumber - 1)).coerceAtMost(30L)
                AppLogger.warn(
                    "CloudAsr",
                    "SiliconFlow HTTP 503，${delayMs}ms 后重试（$retryNumber/$maxServiceUnavailableRetries）",
                )
                delay(delayMs)
            }
        }
        error("SiliconFlow transcription retry loop ended unexpectedly")
    }

    private fun transcribeOnce(audioFile: File): String {
        val boundary = "----Unarchive${System.currentTimeMillis()}"
        val connection = URL("$baseUrl/audio/transcriptions").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            // SiliconFlow documents both bearer and x-api-key authentication;
            // sending the latter as well keeps newer provider routes that only
            // inspect the API-key header compatible without exposing the key.
            connection.setRequestProperty("x-api-key", apiKey)
            connection.setRequestProperty("Accept", "application/json")
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
                val errorBody = readResponseBody(connection)
                throw SiliconFlowHttpException(
                    statusCode = status,
                    responseBody = errorBody,
                    retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After")),
                )
            }
            val response = connection.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            val readDone = android.os.SystemClock.elapsedRealtime()
            AppLogger.info("CloudAsrTiming", "服务端转写+响应耗时=${readDone - uploadDone}ms")
            val json = JSONObject(response)
            val keys = buildList {
                val iterator = json.keys()
                while (iterator.hasNext()) add(iterator.next())
            }.sorted().joinToString(",")
            if (!json.has("text")) {
                AppLogger.warn("CloudAsr", "响应缺少 text 字段，顶层字段=$keys")
                throw IOException("云端转写响应缺少 text 字段")
            }
            val text = json.optString("text", "")
            AppLogger.info("CloudAsr", "响应包含 text 字段，文本长度=${text.trim().length}，顶层字段=$keys")
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRetryAfterMs(value: String?): Long? = value
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }
        ?.coerceAtMost(30L)
        ?.times(1_000L)

    private fun readResponseBody(connection: HttpURLConnection): String {
        val stream = connection.errorStream ?: runCatching { connection.inputStream }.getOrNull()
        return stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty().trim()
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

/** Structured provider error so retry policy never relies on parsing a message string. */
class SiliconFlowHttpException(
    val statusCode: Int,
    val responseBody: String,
    val retryAfterMs: Long?,
) : IOException(
    buildString {
        append("云端转写失败：HTTP ").append(statusCode)
    },
)

/**
 * Cloud ASR engine (SiliconFlow). The provider-specific transcoder produces an
 * API-compatible upload, then the engine returns the text as a single segment;
 * the pipeline fills in the true audio duration because the cloud API returns
 * plain text without timestamps.
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
        if (durationMs <= 0L) {
            AppLogger.warn("CloudAsr", "源音频时长读取为 0，source=${source.displayName}")
        }
        val t0 = android.os.SystemClock.elapsedRealtime()
        val audio = transcoder.transcode(source.uri, context)
        val t1 = android.os.SystemClock.elapsedRealtime()
        AppLogger.info(
            "CloudAsrTiming",
            "转码耗时=${t1 - t0}ms, 源时长=${durationMs}ms, 输出=${audio.length() / 1024}KB",
        )
        try {
            if (audio.length() <= 0L) {
                AppLogger.warn("CloudAsr", "转码输出文件为空或过小，可能导致空文本（AUDIO_EMPTY_OR_TOO_SHORT）")
            }
            progressListener.onProgress(0.5f)
            val text = client.transcribe(audio).trim()
            val t2 = android.os.SystemClock.elapsedRealtime()
            AppLogger.info("CloudAsrTiming", "上传+服务端转写耗时=${t2 - t1}ms")
            progressListener.onProgress(1f)
            if (text.isEmpty()) {
                throw IOException("云端转写返回空文本（EMPTY_TEXT）")
            }
            val rawSegmentCount = CloudTranscriptSegmenter.rawSegmentCount(text)
            val segments = CloudTranscriptSegmenter.withEstimatedTiming(text, durationMs)
            AppLogger.info(
                "CloudAsr",
                "云端文本切分完成 originalChars=${text.length} before=$rawSegmentCount " +
                    "after=${segments.size} merged=${rawSegmentCount - segments.size} timing=ESTIMATED",
            )
            return AsrOutput(
                segments = segments,
                audioDurationMs = durationMs,
                timingAccuracy = TranscriptTimingAccuracy.ESTIMATED,
            )
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
