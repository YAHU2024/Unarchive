package com.unarchive.android.analyzer

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.card.CardAnalysis
import com.unarchive.android.result.asTimestamp
import org.json.JSONObject

/** Builds the prompt, calls the LLM, and parses the structured [CardAnalysis]. */
class CardAnalyzer(
    private val client: LlmClient = LlmClient(),
) {
    suspend fun analyze(
        apiKey: String,
        segments: List<TranscriptSegment>,
        audioDurationMs: Long,
        thinkingEnabled: Boolean = false,
    ): CardAnalysis {
        val content = client.chat(
            apiKey = apiKey,
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildUserPrompt(segments),
            thinkingEnabled = thinkingEnabled,
        )
        return CardAnalysis.fromJson(JSONObject(content), audioDurationMs)
    }

    companion object {
        const val SYSTEM_PROMPT =
            "你是一个学习笔记助手，擅长把视频转录整理成结构化知识卡片。请始终只输出 JSON。"

        fun buildUserPrompt(segments: List<TranscriptSegment>): String {
            val transcript = segments.joinToString("\n") { "[${it.startMs.asTimestamp()}] ${it.text}" }
            return buildString {
                appendLine("下面是视频的逐句转录，每句以 [mm:ss] 时间戳开头：")
                appendLine(transcript)
                appendLine()
                appendLine("请基于以上转录输出一个 JSON 对象，字段如下：")
                appendLine("1. summary：整体摘要，面向学习笔记，2-4 句话，提炼核心观点和可执行要点。")
                appendLine("2. key_points：3-6 个关键要点，字符串数组。")
                appendLine("3. chapters：章节划分数组，每章含 title（章节标题）、start_ms（开始毫秒）、end_ms（结束毫秒）、points（章内关键时间戳要点数组，每个含 timestamp_ms 和 text）。")
                appendLine("要求：章节按时间顺序覆盖主要主题转换；每章 1-4 个要点；时间戳用转录里对应的实际毫秒数；只输出 JSON，不要其他文字。")
            }
        }
    }
}
