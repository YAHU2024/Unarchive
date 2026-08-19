package com.unarchive.android.result

import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

interface VideoResultRepository {
    fun list(): List<StoredVideoResult>

    fun find(key: VideoResultKey): StoredVideoResult?

    fun save(result: StoredVideoResult): StoredVideoResult
}

class FileVideoResultRepository(
    private val directory: File,
) : VideoResultRepository {
    override fun list(): List<StoredVideoResult> = synchronized(this) {
        directory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .mapNotNull(::readResult)
            .sortedByDescending { it.updatedAtEpochMs }
    }

    override fun find(key: VideoResultKey): StoredVideoResult? = synchronized(this) {
        readResult(fileFor(key))?.takeIf { it.key == key }
    }

    override fun save(result: StoredVideoResult): StoredVideoResult = synchronized(this) {
        directory.mkdirs()
        check(directory.isDirectory) { "Cannot create result directory: $directory" }

        val target = fileFor(result.key)
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(result.toJson().toString(), Charsets.UTF_8)
        try {
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
        result
    }

    private fun fileFor(key: VideoResultKey): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${key.platform}:${key.videoId}".toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
        return File(directory, "$digest.json")
    }

    private fun readResult(file: File): StoredVideoResult? = runCatching {
        JSONObject(file.readText(Charsets.UTF_8)).toStoredVideoResult()
    }.getOrNull()
}

private fun StoredVideoResult.toJson() = JSONObject()
    .put("schema_version", 1)
    .put("platform", key.platform)
    .put("video_id", key.videoId)
    .put("canonical_url", canonicalUrl)
    .put("title", title)
    .put("owner_name", ownerName)
    .put("video_duration_seconds", videoDurationSeconds)
    .put("engine", engine.name)
    .put("processing_duration_ms", processingDurationMs)
    .put("audio_duration_ms", audioDurationMs)
    .put("created_at_epoch_ms", createdAtEpochMs)
    .put("updated_at_epoch_ms", updatedAtEpochMs)
    .put("config_signature", configSignature)
    .put("timing_accuracy", timingAccuracy.name)
    .put(
        "segments",
        JSONArray().apply {
            segments.forEach { segment ->
                put(
                    JSONObject()
                        .put("start_ms", segment.startMs)
                        .put("end_ms", segment.endMs)
                        .put("text", segment.text),
                )
            }
        },
    )

private fun JSONObject.toStoredVideoResult(): StoredVideoResult {
    require(getInt("schema_version") == 1) { "Unsupported result schema" }
    val segmentArray = getJSONArray("segments")
    val segments = buildList {
        repeat(segmentArray.length()) { index ->
            val segment = segmentArray.getJSONObject(index)
            add(
                TranscriptSegment(
                    startMs = segment.getLong("start_ms"),
                    endMs = segment.getLong("end_ms"),
                    text = segment.getString("text"),
                ),
            )
        }
    }
    return StoredVideoResult(
        key = VideoResultKey(getString("platform"), getString("video_id")),
        canonicalUrl = getString("canonical_url"),
        title = getString("title"),
        ownerName = getString("owner_name"),
        videoDurationSeconds = getLong("video_duration_seconds"),
        engine = AsrEngineKind.valueOf(getString("engine")),
        processingDurationMs = getLong("processing_duration_ms"),
        audioDurationMs = getLong("audio_duration_ms"),
        segments = segments,
        timingAccuracy = runCatching {
            TranscriptTimingAccuracy.valueOf(optString("timing_accuracy"))
        }.getOrDefault(TranscriptTimingAccuracy.EXACT),
        createdAtEpochMs = getLong("created_at_epoch_ms"),
        updatedAtEpochMs = getLong("updated_at_epoch_ms"),
        // optString: results saved before this field existed parse as blank
        // and are never reused as-is.
        configSignature = optString("config_signature", ""),
    )
}
