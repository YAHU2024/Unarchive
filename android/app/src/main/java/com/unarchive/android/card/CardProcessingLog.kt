package com.unarchive.android.card

import com.unarchive.android.log.AppLogger

/** Structured, privacy-aware events for card generation and portable export. */
object CardProcessingLog {
    private const val TAG = "CardProcessing"

    object Stage {
        const val CARD = "知识卡片"
        const val BASE_CARD = "基础卡片"
        const val AI = "AI 分析"
        const val SCREENSHOTS = "章节截图"
        const val ASSETS = "截图资产"
        const val EXPORT = "卡片导出"
        const val EXPORT_ASSETS = "导出图片"
        const val EXPORT_SHARE = "系统分享"
    }

    object State {
        const val STARTED = "开始"
        const val PUBLISHED = "已保存"
        const val SUCCEEDED = "成功"
        const val COMPLETED = "完成"
        const val FAILED = "失败"
        const val SKIPPED = "跳过"
        const val CANCELLED = "取消"
        const val LAUNCHED = "已发出"
    }

    fun event(
        operationId: String,
        stage: String,
        state: String,
        cardId: KnowledgeCardId? = null,
        elapsedMs: Long? = null,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        AppLogger.info(
            TAG,
            formatEvent(operationId, stage, state, cardId, elapsedMs, metadata),
        )
    }

    internal fun formatEvent(
        operationId: String,
        stage: String,
        state: String,
        cardId: KnowledgeCardId?,
        elapsedMs: Long?,
        metadata: Map<String, Any?>,
    ): String {
        val fields = linkedMapOf<String, String>()
        fields["operationId"] = operationId
        fields["stage"] = stage
        fields["state"] = state
        // Card identity is intentionally omitted. Diagnostics need the stage
        // and outcome, but a private video/card identifier must not enter logs.
        elapsedMs?.let { fields["elapsedMs"] = it.toString() }
        metadata.toSortedMap().forEach { (key, value) ->
            if (value != null) fields[key] = value.toString()
        }
        return fields.entries.joinToString(" ") { (key, value) ->
            "$key=${sanitize(value)}"
        }
    }

    /** Keeps diagnostics useful without persisting response bodies, URLs, or credentials. */
    fun safeError(error: Throwable): String {
        val type = error::class.simpleName ?: "Exception"
        val message = error.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "<url>")
            ?.replace(
                Regex("(?i)(api[-_ ]?key|token|cookie|authorization)\\s*[:=]\\s*\\S+"),
                "\$1=<redacted>",
            )
            ?.trim()
            ?.take(160)
            .orEmpty()
        return if (message.isBlank()) type else "$type:$message"
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("\\s+"), "_")
        .replace("=", ":")
        .replace(Regex("[^\\p{L}\\p{N}_.:/,+@-]"), "_")
        .take(240)
}
