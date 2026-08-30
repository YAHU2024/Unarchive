package com.unarchive.android.editor

import com.unarchive.android.card.NoteContent
import java.security.MessageDigest

/** Result returned by a Markdown-aware AI adapter. */
data class MarkdownAiGenerationResult(
    val markdown: String,
    val model: String,
)

fun interface MarkdownAiProposalGenerator {
    suspend fun generate(content: NoteContent): MarkdownAiGenerationResult
}

enum class MarkdownAiProposalStatus { PENDING, APPLIED, REJECTED, EXPIRED }

/** A reviewable Markdown replacement tied to one exact formal revision. */
data class MarkdownAiProposal(
    val proposalId: String,
    val baseCardId: String,
    val baseCardVersion: String,
    val baseMarkdownRevision: Long,
    val baseContentFingerprint: String,
    val proposedMarkdown: String,
    val model: String,
    val createdAtEpochMs: Long,
    val status: MarkdownAiProposalStatus = MarkdownAiProposalStatus.PENDING,
) {
    init {
        require(proposalId.isNotBlank()) { "proposalId cannot be blank" }
        require(baseCardId.isNotBlank()) { "baseCardId cannot be blank" }
        require(baseCardVersion.isNotBlank()) { "baseCardVersion cannot be blank" }
        require(baseMarkdownRevision >= 0L) { "baseMarkdownRevision cannot be negative" }
        require(baseContentFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "baseContentFingerprint must be a SHA-256 hex digest"
        }
        require(model.isNotBlank()) { "model cannot be blank" }
        require(createdAtEpochMs >= 0L) { "createdAtEpochMs cannot be negative" }
    }

    fun diffAgainst(markdown: String): MarkdownAiDiff = MarkdownAiDiff.between(markdown, proposedMarkdown)
}

/** Compact, deterministic line diff suitable for a mobile review card. */
data class MarkdownAiDiff(
    val addedLines: Int,
    val removedLines: Int,
    val changedLines: Int,
    val removedPreview: List<String>,
    val addedPreview: List<String>,
) {
    val isEmpty: Boolean get() = addedLines == 0 && removedLines == 0

    companion object {
        fun between(current: String, proposed: String, previewLimit: Int = 8): MarkdownAiDiff {
            require(previewLimit > 0) { "previewLimit must be positive" }
            val old = current.split("\n", limit = Int.MAX_VALUE)
            val new = proposed.split("\n", limit = Int.MAX_VALUE)
            var prefix = 0
            while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
            var suffix = 0
            while (
                suffix < old.size - prefix && suffix < new.size - prefix &&
                old[old.lastIndex - suffix] == new[new.lastIndex - suffix]
            ) suffix++
            val removed = (old.size - prefix - suffix).coerceAtLeast(0)
            val added = (new.size - prefix - suffix).coerceAtLeast(0)
            return MarkdownAiDiff(
                addedLines = added,
                removedLines = removed,
                changedLines = minOf(added, removed),
                removedPreview = old.drop(prefix).dropLast(suffix).take(previewLimit),
                addedPreview = new.drop(prefix).dropLast(suffix).take(previewLimit),
            )
        }
    }
}

object MarkdownAiProposalBuilder {
    fun create(
        content: NoteContent,
        proposedMarkdown: String,
        proposalId: String,
        model: String,
        createdAtEpochMs: Long,
    ): MarkdownAiProposal {
        return MarkdownAiProposal(
            proposalId = proposalId,
            baseCardId = content.cardId.value,
            baseCardVersion = content.cardVersion,
            baseMarkdownRevision = content.markdownRevision,
            baseContentFingerprint = NoteContent.markdownFingerprint(content.markdown),
            proposedMarkdown = proposedMarkdown,
            model = model,
            createdAtEpochMs = createdAtEpochMs,
        )
    }
}

/** Re-checks untrusted AI output before it can enter the editor draft. */
object MarkdownAiSafetyValidator {
    data class Result(val errors: List<String>) {
        val isSafe: Boolean get() = errors.isEmpty()
    }

    fun validate(markdown: String): Result {
        val errors = mutableListOf<String>()
        var inFence = false
        markdown.lineSequence().forEach { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                return@forEach
            }
            if (inFence) return@forEach
            LINK_PATTERN.findAll(line).forEach { match ->
                val rawDestination = match.groupValues[2].trim().removeSurrounding("<", ">")
                // Ignore an optional Markdown link title after the destination.
                val destination = rawDestination.split(Regex("""\s+[\"']"""), limit = 2)
                    .first().trim().lowercase()
                if (match.groupValues[1] == "!") {
                    if (!isSafeLocalImage(destination)) errors += "图片引用不受支持：${match.groupValues[2]}"
                } else if (!isSafeLink(destination)) {
                    errors += "链接地址不受支持：${match.groupValues[2]}"
                }
            }
            if (HTML_PATTERN.containsMatchIn(line)) {
                errors += "候选包含原始 HTML，请在 Markdown 视图中确认后再保存"
            }
        }
        return Result(errors.distinct().take(MAX_ERRORS))
    }

    private fun isSafeLink(value: String): Boolean {
        if (value.startsWith("javascript:") || value.startsWith("data:") || value.startsWith("file:")) {
            return false
        }
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            host in setOf("bilibili.com", "www.bilibili.com", "b23.tv")
    }

    private fun isSafeLocalImage(value: String): Boolean {
        if (value.startsWith("http:") || value.startsWith("https:") ||
            value.startsWith("javascript:") || value.startsWith("data:")
        ) return false
        if (value.startsWith("file:", ignoreCase = true)) return false
        val path = value.substringBefore('#').substringBefore('?')
        return path.isNotBlank() &&
            !path.startsWith('/') && !path.startsWith('\\') &&
            !path.matches(Regex("^[A-Za-z]:.*")) &&
            path.split('/', '\\').none { it == ".." }
    }

    private val LINK_PATTERN = Regex("(!?)\\[[^]]*]\\(([^)]+)\\)")
    private val HTML_PATTERN = Regex("<\\s*/?\\s*[A-Za-z][^>]*>")
    private const val MAX_ERRORS = 6
}

internal fun sha256MarkdownProposalKey(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
