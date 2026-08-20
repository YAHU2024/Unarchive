package com.unarchive.android.pipeline

import com.unarchive.android.card.CardStageState
import com.unarchive.android.log.AppLogger
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

enum class BatchFailureRetryability { RETRYABLE, NON_RETRYABLE, UNKNOWN }

object BatchFailureClassifier {
    fun classify(message: String?): BatchFailureRetryability {
        val text = message.orEmpty()
        if (text.contains("EMPTY_TEXT") || text.contains("响应缺少 text") ||
            text.contains("AUDIO_EMPTY_OR_TOO_SHORT") || text.contains("音频为空") ||
            Regex("HTTP 4\\d{2}\\b").containsMatchIn(text)) {
            return BatchFailureRetryability.NON_RETRYABLE
        }
        if (Regex("HTTP (408|429|5\\d{2})\\b").containsMatchIn(text) ||
            text.contains("timeout", ignoreCase = true) || text.contains("timed out", ignoreCase = true) ||
            text.contains("超时") || text.contains("连接") || text.contains("网络")) {
            return BatchFailureRetryability.RETRYABLE
        }
        return BatchFailureRetryability.UNKNOWN
    }
}

data class BatchManifestItem(
    val videoId: String,
    val canonicalUrl: String,
    val title: String,
    val state: BatchItemState,
    val errorMessage: String? = null,
    val apiCode: Int? = null,
    val retryability: BatchFailureRetryability = BatchFailureRetryability.UNKNOWN,
    val cardState: CardStageState = CardStageState.SKIPPED,
    val cardId: String? = null,
    val cardVersion: String? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

data class BatchManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    val batchId: String,
    val folderId: String,
    val folderTitle: String? = null,
    val configSignature: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val items: List<BatchManifestItem>,
) {
    fun withItem(item: BatchManifestItem, now: Long = System.currentTimeMillis()): BatchManifest =
        copy(
            updatedAtEpochMs = now,
            items = items.map { if (it.videoId == item.videoId) item else it },
        )

    fun unfinished(): Boolean = items.any {
        it.state == BatchItemState.QUEUED ||
            it.state == BatchItemState.RUNNING ||
            it.state == BatchItemState.FAILED ||
            it.state == BatchItemState.CANCELLED ||
            it.cardState == CardStageState.QUEUED ||
            it.cardState == CardStageState.RUNNING ||
            it.cardState == CardStageState.FAILED ||
            it.cardState == CardStageState.PARTIAL
    }

    companion object { const val SCHEMA_VERSION = 1 }
}

class BatchManifestRepository(private val directory: File) {
    private val target: File get() = File(directory, FILE_NAME)

    @Synchronized
    fun load(): BatchManifest? = runCatching {
        if (!target.isFile) return null
        JSONObject(target.readText(Charsets.UTF_8)).toManifest()
    }.onFailure { error ->
        AppLogger.warn(TAG, "批次 manifest 读取失败，已忽略：${error.message}")
        target.delete()
    }.getOrNull()

    @Synchronized
    fun save(manifest: BatchManifest): BatchManifest {
        directory.mkdirs()
        check(directory.isDirectory) { "Cannot create batch manifest directory: $directory" }
        val temporary = File(directory, "$FILE_NAME.tmp")
        temporary.writeText(manifest.toJson().toString(), Charsets.UTF_8)
        try {
            try {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        return manifest
    }

    @Synchronized
    fun delete() {
        target.delete()
        File(directory, "$FILE_NAME.tmp").delete()
    }

    private companion object {
        const val TAG = "BatchManifest"
        const val FILE_NAME = "batch-manifest.json"
    }
}

private fun BatchManifest.toJson() = JSONObject()
    .put("schema_version", schemaVersion)
    .put("batch_id", batchId)
    .put("folder_id", folderId)
    .put("folder_title", folderTitle)
    .put("config_signature", configSignature)
    .put("created_at_epoch_ms", createdAtEpochMs)
    .put("updated_at_epoch_ms", updatedAtEpochMs)
    .put("items", JSONArray().apply {
        items.forEach { item ->
            put(JSONObject()
                .put("video_id", item.videoId)
                .put("canonical_url", item.canonicalUrl)
                .put("title", item.title)
                .put("state", item.state.name)
                .put("error_message", item.errorMessage)
                .put("api_code", item.apiCode)
                .put("retryability", item.retryability.name)
                .put("card_state", item.cardState.name)
                .put("card_id", item.cardId)
                .put("card_version", item.cardVersion)
                .put("updated_at_epoch_ms", item.updatedAtEpochMs))
        }
    })

private fun JSONObject.toManifest(): BatchManifest {
    require(getInt("schema_version") == BatchManifest.SCHEMA_VERSION) { "Unsupported batch manifest schema" }
    val array = getJSONArray("items")
    val items = buildList {
        repeat(array.length()) { index ->
            val item = array.getJSONObject(index)
            add(BatchManifestItem(
                videoId = item.getString("video_id"),
                canonicalUrl = item.getString("canonical_url"),
                title = item.optString("title"),
                state = BatchItemState.valueOf(item.getString("state")),
                errorMessage = item.optString("error_message").takeIf { it.isNotBlank() },
                apiCode = if (item.isNull("api_code")) null else item.optInt("api_code"),
                retryability = runCatching {
                    BatchFailureRetryability.valueOf(item.optString("retryability"))
                }.getOrDefault(BatchFailureRetryability.UNKNOWN),
                cardState = runCatching {
                    CardStageState.valueOf(item.optString("card_state"))
                }.getOrDefault(CardStageState.SKIPPED),
                cardId = item.optString("card_id").takeIf { it.isNotBlank() },
                cardVersion = item.optString("card_version").takeIf { it.isNotBlank() },
                updatedAtEpochMs = item.optLong("updated_at_epoch_ms", 0L),
            ))
        }
    }
    return BatchManifest(
        schemaVersion = getInt("schema_version"),
        batchId = getString("batch_id"),
        folderId = getString("folder_id"),
        folderTitle = optString("folder_title").takeIf { it.isNotBlank() },
        configSignature = getString("config_signature"),
        createdAtEpochMs = getLong("created_at_epoch_ms"),
        updatedAtEpochMs = getLong("updated_at_epoch_ms"),
        items = items,
    )
}
