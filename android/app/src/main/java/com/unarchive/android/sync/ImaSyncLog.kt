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
            "cardId" to card.cardId.value,
            "cardVersion" to card.cardVersion.take(12),
            "targetType" to key.targetType,
            "targetId" to key.targetId,
            "targetName" to targetName,
            "folderId" to key.folderId,
            "folderName" to folderName,
            "noteId" to noteId,
            "elapsedMs" to (System.currentTimeMillis() - startedAtMs),
        )
        fields.putAll(metadata)
        val text = fields.entries.filter { it.value != null }.joinToString(" ") { (name, value) ->
            "$name=${sanitize(value.toString())}"
        }
        if (warning) AppLogger.warn(TAG, text) else AppLogger.info(TAG, text)
    }

    fun safeError(error: Throwable): String = (error::class.simpleName ?: "Exception") + ":" +
        error.message.orEmpty().lineSequence().firstOrNull().orEmpty()
            .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "<url>")
            .replace(Regex("(?i)(api[-_ ]?key|token|cookie|authorization)\\s*[:=]\\s*\\S+"), "$1=<redacted>")
            .take(160)

    private fun sanitize(value: String): String = value
        .replace(Regex("\\s+"), "_")
        .replace("=", ":")
        .replace(Regex("[^\\p{L}\\p{N}_.:/,+@-]"), "_")
        .take(240)
}
