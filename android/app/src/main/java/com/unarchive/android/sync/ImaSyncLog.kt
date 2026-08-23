package com.unarchive.android.sync

import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeSyncKey
import com.unarchive.android.log.AppLogger

/** Structured, privacy-safe diagnostics for one ima synchronization operation. */
internal object ImaSyncLog {
    private const val TAG = "ImaSync"

    fun event(
        operationId: String,
        card: KnowledgeCard,
        key: KnowledgeSyncKey,
        targetName: String,
        folderName: String,
        stage: String,
        state: String,
        startedAtMs: Long,
        noteId: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
        warning: Boolean = false,
    ) {
        val fields = linkedMapOf<String, Any?>(
            "operationId" to operationId,
            "stage" to stage,
            "state" to state,
            "targetType" to key.targetType,
            "targetName" to targetName,
            "folderName" to folderName,
            "elapsedMs" to (System.currentTimeMillis() - startedAtMs),
        )
        fields.putAll(metadata)
        val text = fields.entries.filter { it.value != null }.joinToString(" ") { (name, value) ->
            "$name=${sanitize(value.toString())}"
        }
        if (warning) AppLogger.warn(TAG, text) else AppLogger.info(TAG, text)
    }

    fun safeError(error: Throwable): String = when (error) {
        is ImaQuotaExceededException -> "目标配额已用尽"
        is ImaRateLimitException -> "请求频率受限"
        is ImaAlreadyAddedException -> "目标已关联"
        else -> "同步请求失败"
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("\\s+"), "_")
        .replace("=", ":")
        .replace(Regex("[^\\p{L}\\p{N}_.:/,+@-]"), "_")
        .take(240)
}
