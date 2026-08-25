package com.unarchive.android.card

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Schema v3 content authority for the future Markdown-source editor. */
data class NoteContent(
    val cardId: KnowledgeCardId,
    val cardVersion: String,
    val markdown: String,
    val markdownRevision: Long,
    val structuredMetadata: NoteDocument,
    val structuredProjection: NoteStructuredProjection = NoteStructuredProjection(),
    val projectionStatus: NoteProjectionStatus = NoteProjectionStatus.CURRENT,
    val draftState: NoteDraftState? = null,
    val lastKnownGoodMarkdown: String = markdown,
    val lastSaveError: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    init {
        require(cardVersion.isNotBlank()) { "cardVersion cannot be blank" }
        require(markdownRevision >= 0L) { "markdownRevision cannot be negative" }
        require(lastKnownGoodMarkdown.isNotEmpty() || markdown.isEmpty()) {
            "lastKnownGoodMarkdown cannot be empty when markdown is non-empty"
        }
        require(createdAtEpochMs >= 0L) { "createdAtEpochMs cannot be negative" }
        require(updatedAtEpochMs >= createdAtEpochMs) {
            "updatedAtEpochMs cannot precede createdAtEpochMs"
        }
        require(structuredMetadata.cardId == cardId) { "structured metadata card id mismatch" }
        require(structuredMetadata.generation.cardVersion == cardVersion) {
            "structured metadata card version mismatch"
        }
    }

    companion object {
        const val SCHEMA_VERSION = 3

        fun markdownFingerprint(markdown: String): String = sha256(markdown.toByteArray(Charsets.UTF_8))

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}

enum class NoteProjectionStatus { CURRENT, PARTIAL, FAILED }

data class NoteStructuredProjection(
    val recognizedBlockIds: List<String> = emptyList(),
    val unknownMarkdown: String = "",
) {
    init {
        require(recognizedBlockIds.none { it.isBlank() }) {
            "recognized block ids cannot be blank"
        }
        require(recognizedBlockIds.distinct().size == recognizedBlockIds.size) {
            "recognized block ids must be unique"
        }
    }
}

data class NoteDraftState(
    val markdown: String,
    val baseMarkdownRevision: Long,
    val contentFingerprint: String,
    val updatedAtEpochMs: Long,
) {
    init {
        require(baseMarkdownRevision >= 0L) { "draft base revision cannot be negative" }
        require(contentFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "draft content fingerprint must be a SHA-256 hex digest"
        }
        require(updatedAtEpochMs >= 0L) { "draft updatedAtEpochMs cannot be negative" }
    }
}

internal fun NoteContent.toJson(): JSONObject = JSONObject()
    .put("schema_version", NoteContent.SCHEMA_VERSION)
    .put("platform", cardId.platform)
    .put("video_id", cardId.videoId)
    .put("card_version", cardVersion)
    .put("markdown", markdown)
    .put("markdown_revision", markdownRevision)
    .put("structured_metadata", structuredMetadata.toJson())
    .put("structured_projection", structuredProjection.toJson())
    .put("projection_status", projectionStatus.name)
    .put("draft_state", draftState?.toJson())
    .put("last_known_good_markdown", lastKnownGoodMarkdown)
    .put("last_save_error", lastSaveError)
    .put("created_at_epoch_ms", createdAtEpochMs)
    .put("updated_at_epoch_ms", updatedAtEpochMs)

internal fun JSONObject.toNoteContent(): NoteContent {
    require(getInt("schema_version") == NoteContent.SCHEMA_VERSION) {
        "Unsupported note content schema"
    }
    val metadata = getJSONObject("structured_metadata").toNoteDocument()
    val cardId = KnowledgeCardId(getString("platform"), getString("video_id"))
    return NoteContent(
        cardId = cardId,
        cardVersion = getString("card_version"),
        markdown = optString("markdown"),
        markdownRevision = optLong("markdown_revision", 0L),
        structuredMetadata = metadata,
        structuredProjection = optJSONObject("structured_projection")?.toNoteStructuredProjection()
            ?: NoteStructuredProjection(),
        projectionStatus = runCatching {
            NoteProjectionStatus.valueOf(optString("projection_status"))
        }.getOrDefault(NoteProjectionStatus.FAILED),
        draftState = optJSONObject("draft_state")?.toNoteDraftState(),
        lastKnownGoodMarkdown = optString("last_known_good_markdown", optString("markdown")),
        lastSaveError = nullableString("last_save_error"),
        createdAtEpochMs = getLong("created_at_epoch_ms"),
        updatedAtEpochMs = getLong("updated_at_epoch_ms"),
    )
}

private fun NoteStructuredProjection.toJson() = JSONObject()
    .put("recognized_block_ids", JSONArray(recognizedBlockIds))
    .put("unknown_markdown", unknownMarkdown)

private fun JSONObject.toNoteStructuredProjection() = NoteStructuredProjection(
    recognizedBlockIds = jsonStringList(optJSONArray("recognized_block_ids")),
    unknownMarkdown = optString("unknown_markdown"),
)

private fun NoteDraftState.toJson() = JSONObject()
    .put("markdown", markdown)
    .put("base_markdown_revision", baseMarkdownRevision)
    .put("content_fingerprint", contentFingerprint)
    .put("updated_at_epoch_ms", updatedAtEpochMs)

private fun JSONObject.toNoteDraftState() = NoteDraftState(
    markdown = optString("markdown"),
    baseMarkdownRevision = optLong("base_markdown_revision", 0L),
    contentFingerprint = optString("content_fingerprint"),
    updatedAtEpochMs = optLong("updated_at_epoch_ms", 0L),
)

private fun JSONObject.nullableString(name: String): String? =
    takeUnless { isNull(name) }?.optString(name)?.takeIf { it.isNotBlank() }

private fun jsonStringList(array: JSONArray?): List<String> = buildList {
    if (array == null) return@buildList
    repeat(array.length()) { array.optString(it).trim().takeIf(String::isNotEmpty)?.let(::add) }
}
