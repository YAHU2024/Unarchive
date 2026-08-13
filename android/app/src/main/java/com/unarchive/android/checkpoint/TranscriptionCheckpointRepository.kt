package com.unarchive.android.checkpoint

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.result.VideoResultKey
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

interface TranscriptionCheckpointRepository {
    fun find(key: VideoResultKey): TranscriptionCheckpoint?
    fun save(checkpoint: TranscriptionCheckpoint): TranscriptionCheckpoint
    fun delete(key: VideoResultKey)
}

class FileTranscriptionCheckpointRepository(
    private val directory: File,
) : TranscriptionCheckpointRepository {
    override fun find(key: VideoResultKey): TranscriptionCheckpoint? = synchronized(this) {
        read(fileFor(key))?.takeIf { it.source.key == key }
    }

    override fun save(checkpoint: TranscriptionCheckpoint): TranscriptionCheckpoint = synchronized(this) {
        require(directory.mkdirs() || directory.isDirectory) {
            "Cannot create checkpoint directory: $directory"
        }
        val target = fileFor(checkpoint.source.key)
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(checkpoint.toJson().toString(), Charsets.UTF_8)
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
        checkpoint
    }

    override fun delete(key: VideoResultKey): Unit = synchronized(this) {
        fileFor(key).delete()
        File(directory, "${fileFor(key).name}.tmp").delete()
        Unit
    }

    private fun fileFor(key: VideoResultKey): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${key.platform}:${key.videoId}".toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
        return File(directory, "$digest.json")
    }

    private fun read(file: File): TranscriptionCheckpoint? = runCatching {
        JSONObject(file.readText(Charsets.UTF_8)).toCheckpoint()
    }.getOrNull()
}

private fun TranscriptionCheckpoint.toJson() = JSONObject()
    .put("schema_version", 1)
    .put("platform", source.key.platform)
    .put("video_id", source.key.videoId)
    .put("canonical_url", source.canonicalUrl)
    .put("byte_count", source.byteCount)
    .put("modified_at_epoch_ms", source.modifiedAtEpochMs)
    .put("content_fingerprint", source.contentFingerprint)
    .put("engine", config.engine)
    .put("language", config.language)
    .put("sample_rate_hz", config.sampleRateHz)
    .put("enable_vad", config.enableVad)
    .put("context_padding_ms", config.contextPaddingMs)
    .put("pipeline_version", config.pipelineVersion)
    .put("audio_duration_ms", audioDurationMs)
    .put("completed_through_ms", completedThroughMs)
    .put("created_at_epoch_ms", createdAtEpochMs)
    .put("updated_at_epoch_ms", updatedAtEpochMs)
    .put("segments", JSONArray().apply {
        segments.forEach { segment ->
            put(JSONObject()
                .put("start_ms", segment.startMs)
                .put("end_ms", segment.endMs)
                .put("text", segment.text))
        }
    })

private fun JSONObject.toCheckpoint(): TranscriptionCheckpoint {
    require(getInt("schema_version") == 1) { "Unsupported checkpoint schema" }
    val segmentsJson = getJSONArray("segments")
    val segments = buildList {
        repeat(segmentsJson.length()) { index ->
            val segment = segmentsJson.getJSONObject(index)
            add(TranscriptSegment(
                startMs = segment.getLong("start_ms"),
                endMs = segment.getLong("end_ms"),
                text = segment.getString("text"),
            ))
        }
    }
    return TranscriptionCheckpoint(
        source = TranscriptionSourceIdentity(
            key = VideoResultKey(getString("platform"), getString("video_id")),
            canonicalUrl = getString("canonical_url"),
            byteCount = getLong("byte_count"),
            modifiedAtEpochMs = getLong("modified_at_epoch_ms"),
            contentFingerprint = getString("content_fingerprint"),
        ),
        config = TranscriptionConfigIdentity(
            engine = getString("engine"),
            language = getString("language"),
            sampleRateHz = getInt("sample_rate_hz"),
            enableVad = getBoolean("enable_vad"),
            contextPaddingMs = getInt("context_padding_ms"),
            pipelineVersion = getInt("pipeline_version"),
        ),
        audioDurationMs = getLong("audio_duration_ms"),
        completedThroughMs = getLong("completed_through_ms"),
        segments = segments,
        createdAtEpochMs = getLong("created_at_epoch_ms"),
        updatedAtEpochMs = getLong("updated_at_epoch_ms"),
    )
}
