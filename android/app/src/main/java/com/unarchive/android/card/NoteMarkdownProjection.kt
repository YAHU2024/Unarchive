package com.unarchive.android.card

import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.result.asTimestamp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Deterministic Markdown projection used for migration/export and controlled writeback. */
object NoteMarkdownProjection {
    fun render(document: NoteDocument): String = buildString {
        appendLine("---")
        appendLine("title: ${yamlString(document.title)}")
        appendLine("author: ${yamlString(document.source.ownerName)}")
        appendLine("source: ${document.source.canonicalUrl}")
        appendLine("timing: ${document.source.timingAccuracy.name.lowercase()}")
        appendLine("content_revision: ${document.editing.contentRevision}")
        appendLine("updated: ${formatDate(document.updatedAtEpochMs)}")
        if (document.tags.isEmpty()) {
            appendLine("tags: []")
        } else {
            appendLine("tags: [${document.tags.joinToString(", ") { yamlString(it) }}]")
        }
        appendLine("---")
        appendLine()
        appendLine("# ${document.title}")
        appendLine()

        var keyPointHeadingWritten = false
        var chapterHeadingWritten = false
        var tagBlockWritten = false
        document.blocks.forEach { block ->
            when (block.type) {
                NoteBlockType.SUMMARY -> {
                    appendLine("## 摘要")
                    appendLine(block.text)
                    appendLine()
                }
                NoteBlockType.KEY_POINT -> {
                    if (!keyPointHeadingWritten) {
                        appendLine("## 关键要点")
                        keyPointHeadingWritten = true
                    }
                    appendLine("- ${block.text}")
                }
                NoteBlockType.CHAPTER -> {
                    if (!chapterHeadingWritten) {
                        appendLine("## 故事线")
                        chapterHeadingWritten = true
                    }
                    appendLine("### ${block.startMs!!.asTimestamp()}–${block.endMs!!.asTimestamp()} ${block.title}")
                    block.assetRefs.mapNotNull { assetId ->
                        document.assets.firstOrNull { it.assetId == assetId }
                    }.forEach { asset -> appendLine("![](${asset.relativePath})") }
                    if (block.text.isNotBlank()) {
                        appendLine(block.text)
                        appendLine()
                    }
                    block.points.forEach { point ->
                        appendLine("- ${timestampLink(document.source.canonicalUrl, point.timestampMs)} ${point.text}")
                    }
                    appendLine()
                }
                NoteBlockType.USER_NOTE -> {
                    appendLine("## 我的想法")
                    appendLine(block.text)
                    appendLine()
                }
                NoteBlockType.TAG_LIST -> {
                    if (!tagBlockWritten) {
                        appendLine("## 标签")
                        tagBlockWritten = true
                    }
                    block.tags.forEach { tag -> appendLine("- $tag") }
                    appendLine()
                }
            }
        }
        if (keyPointHeadingWritten) appendLine()
        if (!tagBlockWritten && document.tags.isNotEmpty()) {
            appendLine("## 标签")
            document.tags.forEach { appendLine("- $it") }
            appendLine()
        }
        appendLine("## 转录全文")
        if (document.source.timingAccuracy == TranscriptTimingAccuracy.ESTIMATED) {
            appendLine("> 云端转写句子切分，时间戳为估算值。")
            appendLine()
        }
        document.sourceTranscript.forEach { segment ->
            appendLine("${timestampLink(document.source.canonicalUrl, segment.startMs)} ${segment.text}")
        }
    }.trimEnd()

    private fun timestampLink(canonicalUrl: String, startMs: Long): String {
        val seconds = (startMs / 1_000L).coerceAtLeast(0L)
        val separator = if (canonicalUrl.contains('?')) "&" else "?"
        return "[${startMs.asTimestamp()}]($canonicalUrl${separator}t=$seconds)"
    }

    private fun yamlString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun formatDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
}
