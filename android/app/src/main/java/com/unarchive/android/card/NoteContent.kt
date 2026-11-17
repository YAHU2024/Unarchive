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
        // The previous complete formal revision may legitimately be empty.
        // A later nonempty edit must still be able to carry that exact base.
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
    val baseContentFingerprint: String? = null,
    /** null means a legacy draft inherits its base revision's assets. */
    val assets: List<CardAsset>? = null,
    val selectionAnchor: Int? = null,
    val selectionFocus: Int? = null,
    val viewportAnchor: Int? = null,
    val pendingSourceEdit: PendingNoteSourceEdit? = null,
    val sessionId: String? = null,
    val documentVersion: Long = 0L,
    val generation: Long = 0L,
) {
    init {
        require(baseMarkdownRevision >= 0L) { "draft base revision cannot be negative" }
        require(contentFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "draft content fingerprint must be a SHA-256 hex digest"
        }
        require(updatedAtEpochMs >= 0L) { "draft updatedAtEpochMs cannot be negative" }
        require(baseContentFingerprint == null || baseContentFingerprint.matches(SHA256_PATTERN))
        require(selectionAnchor == null || selectionAnchor in 0..markdown.length)
        require(selectionFocus == null || selectionFocus in 0..markdown.length)
        require(viewportAnchor == null || viewportAnchor in 0..markdown.length)
        require(listOfNotNull(selectionAnchor, selectionFocus, viewportAnchor).all { offset ->
            offset <= 0 || offset >= markdown.length || !markdown[offset - 1].isHighSurrogate() || !markdown[offset].isLowSurrogate()
        })
        require(documentVersion >= 0L && generation >= 0L)
        require(sessionId == null || sessionId.isNotBlank())
        require(assets == null || assets.map { it.assetId }.distinct().size == assets.size)
    }
}

/** Unapplied source-panel text is recovery data, never a second formal note. */
data class PendingNoteSourceEdit(
    val sourceStart: Int,
    val sourceEnd: Int,
    val originalRangeFingerprint: String,
    val baseContentFingerprint: String,
    val text: String,
) {
    init {
        require(sourceStart >= 0 && sourceEnd >= sourceStart)
        require(originalRangeFingerprint.matches(SHA256_PATTERN))
        require(baseContentFingerprint.matches(SHA256_PATTERN))
    }
}

private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

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
    .put("draft_state", draftState?.toDraftJson())
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

internal fun NoteDraftState.toDraftJson() = JSONObject()
    .put("markdown", markdown)
    .put("base_markdown_revision", baseMarkdownRevision)
    .put("content_fingerprint", contentFingerprint)
    .put("updated_at_epoch_ms", updatedAtEpochMs)
    .put("base_content_fingerprint", baseContentFingerprint)
    .apply {
        // A missing key and an explicit empty array have different meanings.
        assets?.let { values -> put("assets", JSONArray().apply { values.forEach { put(it.toDraftAssetJson()) } }) }
    }
    .put("selection_anchor", selectionAnchor)
    .put("selection_focus", selectionFocus)
    .put("viewport_anchor", viewportAnchor)
    .put("pending_source_edit", pendingSourceEdit?.let {
        JSONObject().put("source_start", it.sourceStart).put("source_end", it.sourceEnd)
            .put("original_range_fingerprint", it.originalRangeFingerprint)
            .put("base_content_fingerprint", it.baseContentFingerprint).put("text", it.text)
    })
    .put("session_id", sessionId)
    .put("document_version", documentVersion)
    .put("generation", generation)

internal fun JSONObject.toNoteDraftState() = NoteDraftState(
    markdown = getString("markdown"),
    baseMarkdownRevision = getLong("base_markdown_revision"),
    contentFingerprint = getString("content_fingerprint"),
    updatedAtEpochMs = getLong("updated_at_epoch_ms"),
    baseContentFingerprint = nullableString("base_content_fingerprint"),
    assets = optJSONArray("assets")?.let { values ->
        List(values.length()) { values.getJSONObject(it).toDraftAsset() }
    },
    selectionAnchor = nullableInt("selection_anchor"),
    selectionFocus = nullableInt("selection_focus"),
    viewportAnchor = nullableInt("viewport_anchor"),
    pendingSourceEdit = optJSONObject("pending_source_edit")?.let {
        PendingNoteSourceEdit(
            it.getInt("source_start"), it.getInt("source_end"),
            it.getString("original_range_fingerprint"), it.getString("base_content_fingerprint"),
            it.getString("text"),
        )
    },
    sessionId = nullableString("session_id"),
    documentVersion = optLong("document_version", 0L),
    generation = optLong("generation", 0L),
)

private fun CardAsset.toDraftAssetJson() = JSONObject()
    .put("asset_id", assetId).put("kind", kind.name).put("mime_type", mimeType)
    .put("relative_path", relativePath).put("byte_count", byteCount).put("sha256", sha256)
    .put("chapter_index", chapterIndex).put("timestamp_ms", timestampMs)

private fun JSONObject.toDraftAsset() = CardAsset(
    assetId = getString("asset_id"), kind = CardAssetKind.valueOf(getString("kind")),
    mimeType = getString("mime_type"), relativePath = getString("relative_path"),
    byteCount = getLong("byte_count"), sha256 = getString("sha256"),
    chapterIndex = nullableInt("chapter_index"),
    timestampMs = takeUnless { isNull("timestamp_ms") }?.getLong("timestamp_ms"),
)

private fun JSONObject.nullableInt(name: String): Int? = takeUnless { isNull(name) }?.getInt(name)

private fun JSONObject.nullableString(name: String): String? =
    takeUnless { isNull(name) }?.optString(name)?.takeIf { it.isNotBlank() }

private fun jsonStringList(array: JSONArray?): List<String> = buildList {
    if (array == null) return@buildList
    repeat(array.length()) { array.optString(it).trim().takeIf(String::isNotEmpty)?.let(::add) }
}
