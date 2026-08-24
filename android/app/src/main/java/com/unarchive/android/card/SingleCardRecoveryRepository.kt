package com.unarchive.android.card

import com.unarchive.android.result.VideoResultKey
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

enum class SingleCardRecoveryStage { BASE, ANALYSIS, SCREENSHOTS, PUBLISH }

enum class SingleCardRecoveryState {
    RECOVERABLE,
    RECOVERING,
    SUCCEEDED,
    FAILED,
    ABANDONED,
}

/** Durable state for a user-confirmed, single-video card recovery attempt. */
data class SingleCardRecoveryRecord(
    val operationId: String,
    val cardId: KnowledgeCardId,
    val cardVersion: String,
    val resultKey: VideoResultKey,
    val stage: SingleCardRecoveryStage,
    val state: SingleCardRecoveryState,
    val configSignature: String,
    val attemptCount: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val finalCardVersion: String? = null,
    val lastError: String? = null,
) {
    init {
        require(operationId.isNotBlank()) { "operationId cannot be blank" }
        require(cardVersion.isNotBlank()) { "cardVersion cannot be blank" }
        require(configSignature.isNotBlank()) { "configSignature cannot be blank" }
        require(attemptCount >= 0) { "attemptCount cannot be negative" }
        require(createdAtEpochMs >= 0L) { "createdAtEpochMs cannot be negative" }
        require(updatedAtEpochMs >= createdAtEpochMs) { "updatedAtEpochMs cannot precede createdAtEpochMs" }
    }
}

class FileSingleCardRecoveryRepository(private val directory: File) {
    fun list(): List<SingleCardRecoveryRecord> = synchronized(this) {
        directory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .mapNotNull { file -> runCatching { read(file) }.getOrNull() }
            .sortedByDescending(SingleCardRecoveryRecord::updatedAtEpochMs)
    }

    fun save(record: SingleCardRecoveryRecord): SingleCardRecoveryRecord = synchronized(this) {
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create recovery directory" }
        val target = File(directory, "${record.operationId}.json")
        val temporary = File(directory, "${record.operationId}.json.tmp")
        temporary.writeText(record.toJson().toString(), Charsets.UTF_8)
        try {
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        record
    }

    private fun read(file: File): SingleCardRecoveryRecord {
        val json = JSONObject(file.readText(Charsets.UTF_8))
        return SingleCardRecoveryRecord(
            operationId = json.getString("operation_id"),
            cardId = KnowledgeCardId(json.getString("platform"), json.getString("video_id")),
            cardVersion = json.getString("card_version"),
            resultKey = VideoResultKey(json.getString("result_platform"), json.getString("result_video_id")),
            stage = runCatching { SingleCardRecoveryStage.valueOf(json.getString("stage")) }
                .getOrElse { SingleCardRecoveryStage.BASE },
            state = runCatching { SingleCardRecoveryState.valueOf(json.getString("state")) }
                .getOrElse { SingleCardRecoveryState.FAILED },
            configSignature = json.getString("config_signature"),
            attemptCount = json.optInt("attempt_count", 0).coerceAtLeast(0),
            createdAtEpochMs = json.getLong("created_at_epoch_ms"),
            updatedAtEpochMs = json.getLong("updated_at_epoch_ms"),
            finalCardVersion = json.optString("final_card_version").takeIf(String::isNotBlank),
            lastError = json.optString("last_error").takeIf(String::isNotBlank),
        )
    }

    private fun SingleCardRecoveryRecord.toJson(): JSONObject = JSONObject()
        .put("operation_id", operationId)
        .put("platform", cardId.platform)
        .put("video_id", cardId.videoId)
        .put("card_version", cardVersion)
        .put("result_platform", resultKey.platform)
        .put("result_video_id", resultKey.videoId)
        .put("stage", stage.name)
        .put("state", state.name)
        .put("config_signature", configSignature)
        .put("attempt_count", attemptCount)
        .put("created_at_epoch_ms", createdAtEpochMs)
        .put("updated_at_epoch_ms", updatedAtEpochMs)
        .put("final_card_version", finalCardVersion)
        .put("last_error", lastError)
}
