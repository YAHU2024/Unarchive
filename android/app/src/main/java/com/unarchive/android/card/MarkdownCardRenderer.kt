package com.unarchive.android.card

import com.unarchive.android.result.StoredVideoResult
import com.unarchive.android.result.asTimestamp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders a [StoredVideoResult] into an Obsidian-friendly Markdown knowledge card.
 *
 * First version renders only the base layer: YAML frontmatter + title + the full
 * transcript with clickable Bilibili `?t=` timestamp links. Summary and story line
 * are added later by the LLM enhancement layer.
 */
object MarkdownCardRenderer {
    private const val MAX_FILE_NAME_LENGTH = 80
    private val ILLEGAL_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|]")

    fun render(
        result: StoredVideoResult,
        analysis: CardAnalysis? = null,
        screenshots: List<String>? = null,
        screenshotPaths: List<String>? = null,
    ): String = buildString {
        appendLine("---")
        appendLine("title: ${yamlString(result.title)}")
        appendLine("author: ${yamlString(result.ownerName)}")
        appendLine("source: ${result.canonicalUrl}")
        appendLine("transcribed: ${formatDate(result.updatedAtEpochMs)}")
        appendLine("timing: ${result.timingAccuracy.name.lowercase()}")
        appendLine("---")
        appendLine()
        appendLine("# ${result.title}")
        appendLine()
        if (analysis != null) {
            appendLine("## 摘要")
            appendLine(analysis.summary)
            appendLine()
            if (analysis.chapters.isNotEmpty()) {
                appendLine("## 故事线")
                analysis.chapters.forEachIndexed { index, chapter ->
                    appendLine("### ${chapter.startMs.asTimestamp()}–${chapter.endMs.asTimestamp()} ${chapter.title}")
                    screenshotPaths?.getOrNull(index)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { appendLine("![]($it)") }
                        ?: screenshots?.getOrNull(index)
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { appendLine("![](data:image/jpeg;base64,$it)") }
                    chapter.points.forEach { point ->
                        appendLine("- ${timestampLink(result.canonicalUrl, point.timestampMs)} ${point.text}")
                    }
                }
                appendLine()
            }
            if (analysis.keyPoints.isNotEmpty()) {
                appendLine("## 关键要点")
                analysis.keyPoints.forEach { appendLine("- $it") }
                appendLine()
            }
        }
        appendLine("## 转录全文")
        if (result.timingAccuracy == com.unarchive.android.asr.TranscriptTimingAccuracy.ESTIMATED) {
            appendLine("> 云端转写句子切分，时间戳为估算值。")
            appendLine()
        }
        result.segments.forEach { segment ->
            appendLine("${timestampLink(result.canonicalUrl, segment.startMs)} ${segment.text}")
        }
    }.trimEnd()

    /** Returns a safe `.md` file name derived from the video title. */
    fun fileName(result: StoredVideoResult): String = fileName(result.title)

    fun fileName(title: String): String = "${safeFileName(title)}.md"

    private fun timestampLink(canonicalUrl: String, startMs: Long): String =
        "[${startMs.asTimestamp()}](${timestampUrl(canonicalUrl, startMs)})"

    private fun timestampUrl(canonicalUrl: String, startMs: Long): String {
        val seconds = (startMs / 1_000).coerceAtLeast(0)
        val separator = if (canonicalUrl.contains('?')) "&" else "?"
        return "$canonicalUrl${separator}t=$seconds"
    }

    private fun yamlString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun formatDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun safeFileName(title: String): String {
        val sanitized = title
            .replace(ILLEGAL_FILE_NAME_CHARS, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_FILE_NAME_LENGTH)
        return sanitized.ifBlank { "untitled" }
    }
}
