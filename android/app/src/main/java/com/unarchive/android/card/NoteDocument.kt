package com.unarchive.android.card

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy

/** Structured editing source for schema v2 knowledge notes. */
data class NoteDocument(
    val cardId: KnowledgeCardId,
    val title: String,
    val source: NoteSource,
    val sourceTranscript: List<TranscriptSegment>,
    val blocks: List<NoteBlock>,
    val tags: List<String> = emptyList(),
    val relations: List<CardRelation> = emptyList(),
    val assets: List<CardAsset> = emptyList(),
    val generation: NoteGeneration,
    val editing: NoteEditingState,
    val publishing: NotePublishingState,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    init {
        require(title.isNotBlank()) { "title cannot be blank" }
        require(createdAtEpochMs >= 0L) { "createdAtEpochMs cannot be negative" }
        require(updatedAtEpochMs >= createdAtEpochMs) { "updatedAtEpochMs cannot precede createdAtEpochMs" }
        require(blocks.map { it.id }.distinct().size == blocks.size) { "block ids must be unique" }
        val normalizedTags = tags.map(String::trim).filter(String::isNotEmpty).distinct()
        require(normalizedTags == tags) { "tags must be trimmed and unique" }
        val tagBlocks = blocks.filter { it.type == NoteBlockType.TAG_LIST }
        require(tagBlocks.size <= 1) { "a note can have at most one tag block" }
        if (tagBlocks.isNotEmpty()) {
            require(tagBlocks.single().tags == tags) { "tag block and document tags must match" }
        }
    }

    companion object {
        const val SCHEMA_VERSION = 2
    }
}

data class NoteSource(
    val canonicalUrl: String,
    val ownerName: String,
    val durationMs: Long,
    val timingAccuracy: TranscriptTimingAccuracy,
) {
    init {
        require(canonicalUrl.isNotBlank()) { "canonicalUrl cannot be blank" }
        require(durationMs >= 0L) { "durationMs cannot be negative" }
    }
}

enum class NoteBlockType { SUMMARY, KEY_POINT, CHAPTER, USER_NOTE, TAG_LIST }

enum class NoteBlockOrigin { AI, USER, SOURCE }

data class NoteSourceRef(
    val url: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val timingAccuracy: TranscriptTimingAccuracy = TranscriptTimingAccuracy.EXACT,
) {
    init {
        require(url.isNotBlank()) { "source reference URL cannot be blank" }
        require((startMs == null) == (endMs == null)) {
            "source reference must provide both startMs and endMs"
        }
        if (startMs != null && endMs != null) {
            require(startMs >= 0L) { "source reference startMs cannot be negative" }
            require(endMs >= startMs) { "source reference endMs cannot precede startMs" }
        }
    }
}

data class NotePoint(
    val id: String,
    val timestampMs: Long,
    val text: String,
) {
    init {
        require(id.isNotBlank()) { "point id cannot be blank" }
        require(timestampMs >= 0L) { "point timestampMs cannot be negative" }
        require(text.isNotBlank()) { "point text cannot be blank" }
    }
}

/** A finite, editable block. Unused fields stay empty for the selected type. */
data class NoteBlock(
    val id: String,
    val type: NoteBlockType,
    val origin: NoteBlockOrigin,
    val text: String = "",
    val title: String = "",
    val startMs: Long? = null,
    val endMs: Long? = null,
    val points: List<NotePoint> = emptyList(),
    val sourceRef: NoteSourceRef? = null,
    val assetRefs: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "block id cannot be blank" }
        require(assetRefs.none { it.isBlank() }) { "asset references cannot be blank" }
        require(assetRefs.distinct().size == assetRefs.size) { "asset references must be unique" }
        require((startMs == null) == (endMs == null)) {
            "block must provide both startMs and endMs"
        }
        if (startMs != null && endMs != null) {
            require(startMs >= 0L) { "block startMs cannot be negative" }
            require(endMs >= startMs) { "block endMs cannot precede startMs" }
            require(points.all { it.timestampMs in startMs..endMs }) {
                "chapter point must stay within the chapter range"
            }
        }
        require(tags.map(String::trim).filter(String::isNotEmpty).distinct() == tags) {
            "block tags must be trimmed and unique"
        }
        when (type) {
            NoteBlockType.SUMMARY, NoteBlockType.KEY_POINT, NoteBlockType.USER_NOTE -> {
                require(text.isNotBlank()) { "$type block text cannot be blank" }
                require(title.isBlank()) { "$type block cannot have a title" }
                require(points.isEmpty()) { "$type block cannot have points" }
                require(startMs == null && endMs == null) { "$type block cannot have a time range" }
                require(tags.isEmpty()) { "$type block cannot have tags" }
            }
            NoteBlockType.CHAPTER -> {
                require(title.isNotBlank()) { "chapter title cannot be blank" }
                require(startMs != null && endMs != null) { "chapter must have a time range" }
                require(sourceRef != null) { "chapter must retain a source reference" }
                require(tags.isEmpty()) { "chapter cannot have tags" }
            }
            NoteBlockType.TAG_LIST -> {
                require(tags.isNotEmpty()) { "tag block cannot be empty" }
                require(text.isBlank() && title.isBlank()) { "tag block only stores tags" }
                require(points.isEmpty() && sourceRef == null) { "tag block cannot have source content" }
                require(startMs == null && endMs == null) { "tag block cannot have a time range" }
            }
        }
        if (type == NoteBlockType.USER_NOTE) {
            require(sourceRef == null) { "user notes cannot masquerade as source citations" }
        }
    }
}

enum class NoteGenerationState { NOT_STARTED, BASE_ONLY, RUNNING, COMPLETE, PARTIAL, FAILED }

data class NoteGeneration(
    val cardVersion: String,
    val model: String? = null,
    val signature: String? = null,
    val state: NoteGenerationState,
    val baseState: CardStageState,
    val analysisState: CardStageState,
    val screenshotsState: CardStageState,
    val lastError: String? = null,
) {
    init {
        require(cardVersion.isNotBlank()) { "cardVersion cannot be blank" }
    }
}

data class NoteEditingState(
    val contentRevision: Long = 0L,
    val dirty: Boolean = false,
    val lastSavedAtEpochMs: Long? = null,
    val lastSaveError: String? = null,
) {
    init {
        require(contentRevision >= 0L) { "contentRevision cannot be negative" }
        require(lastSavedAtEpochMs == null || lastSavedAtEpochMs >= 0L) {
            "lastSavedAtEpochMs cannot be negative"
        }
    }
}

data class NotePublishingState(
    val markdownProjectionHash: String = "",
    val updatedAtEpochMs: Long = 0L,
) {
    init {
        require(updatedAtEpochMs >= 0L) { "publishing updatedAtEpochMs cannot be negative" }
        require(markdownProjectionHash.isEmpty() || markdownProjectionHash.matches(Regex("[0-9a-f]{64}"))) {
            "markdownProjectionHash must be a SHA-256 hex digest"
        }
    }
}
