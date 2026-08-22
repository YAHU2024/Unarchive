package com.unarchive.android.card

import com.unarchive.android.asr.TranscriptTimingAccuracy
import java.security.MessageDigest

/** Pure, repeatable conversion from the schema v1 compatibility card. */
object NoteDocumentMigration {
    fun fromKnowledgeCard(card: KnowledgeCard): NoteDocument {
        val title = card.title.trim().ifBlank { "未命名笔记" }
        val source = NoteSource(
            canonicalUrl = card.canonicalUrl,
            ownerName = card.ownerName,
            durationMs = card.videoDurationSeconds.coerceAtLeast(0L)
                .coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L,
            timingAccuracy = card.timingAccuracy,
        )
        val tags = card.tags.map(String::trim).filter(String::isNotEmpty).distinct()
        val blocks = buildList {
            val analysis = card.analysis
            if (analysis == null) {
                add(
                    NoteBlock(
                        id = stableId("summary-source", card.cardId.value),
                        type = NoteBlockType.SUMMARY,
                        origin = NoteBlockOrigin.SOURCE,
                        text = "原始转写待整理",
                        sourceRef = NoteSourceRef(
                            url = card.canonicalUrl,
                            startMs = 0L,
                            endMs = source.durationMs,
                            timingAccuracy = card.timingAccuracy,
                        ),
                    ),
                )
            } else {
                add(
                    NoteBlock(
                        id = stableId("summary-ai", analysis.summary),
                        type = NoteBlockType.SUMMARY,
                        origin = NoteBlockOrigin.AI,
                        text = analysis.summary,
                    ),
                )
                analysis.keyPoints.forEachIndexed { index, point ->
                    add(
                        NoteBlock(
                            id = stableId("key-point", "$index|$point"),
                            type = NoteBlockType.KEY_POINT,
                            origin = NoteBlockOrigin.AI,
                            text = point,
                        ),
                    )
                }
                analysis.chapters.forEachIndexed { index, chapter ->
                    val points = chapter.points.map { point ->
                        NotePoint(
                            id = stableId("chapter-point", "$index|${point.timestampMs}|${point.text}"),
                            timestampMs = point.timestampMs,
                            text = point.text,
                        )
                    }
                    add(
                        NoteBlock(
                            id = stableId("chapter", "$index|${chapter.startMs}|${chapter.title}"),
                            type = NoteBlockType.CHAPTER,
                            origin = NoteBlockOrigin.AI,
                            title = chapter.title,
                            startMs = chapter.startMs,
                            endMs = chapter.endMs,
                            points = points,
                            sourceRef = NoteSourceRef(
                                url = card.canonicalUrl,
                                startMs = chapter.startMs,
                                endMs = chapter.endMs,
                                timingAccuracy = card.timingAccuracy,
                            ),
                            assetRefs = card.assets
                                .filter { it.chapterIndex == index }
                                .map { it.assetId },
                        ),
                    )
                }
            }
            if (tags.isNotEmpty()) {
                add(
                    NoteBlock(
                        id = stableId("tags", tags.joinToString("|")),
                        type = NoteBlockType.TAG_LIST,
                        origin = NoteBlockOrigin.USER,
                        tags = tags,
                    ),
                )
            }
        }
        val draft = NoteDocument(
            cardId = card.cardId,
            title = title,
            source = source,
            sourceTranscript = card.transcript,
            blocks = blocks,
            tags = tags,
            relations = card.relations,
            assets = card.assets,
            generation = NoteGeneration(
                cardVersion = card.cardVersion,
                state = generationState(card),
                baseState = card.baseState,
                analysisState = card.analysisState,
                screenshotsState = card.screenshotsState,
                lastError = card.lastError,
            ),
            editing = NoteEditingState(
                contentRevision = 0L,
                dirty = false,
                lastSavedAtEpochMs = card.updatedAtEpochMs,
            ),
            publishing = NotePublishingState(updatedAtEpochMs = card.updatedAtEpochMs),
            createdAtEpochMs = card.createdAtEpochMs,
            updatedAtEpochMs = card.updatedAtEpochMs,
        )
        val markdown = NoteMarkdownProjection.render(draft)
        return draft.copy(
            publishing = draft.publishing.copy(
                markdownProjectionHash = sha256(markdown.toByteArray(Charsets.UTF_8)),
            ),
        )
    }

    private fun generationState(card: KnowledgeCard): NoteGenerationState {
        val states = listOf(card.baseState, card.analysisState, card.screenshotsState)
        return when {
            states.any { it == CardStageState.FAILED } -> NoteGenerationState.FAILED
            states.any { it == CardStageState.RUNNING } -> NoteGenerationState.RUNNING
            states.any { it == CardStageState.PARTIAL } -> NoteGenerationState.PARTIAL
            card.baseState == CardStageState.SUCCEEDED &&
                card.analysisState == CardStageState.SUCCEEDED -> NoteGenerationState.COMPLETE
            card.baseState == CardStageState.SUCCEEDED -> NoteGenerationState.BASE_ONLY
            states.all { it == CardStageState.QUEUED || it == CardStageState.SKIPPED } ->
                NoteGenerationState.NOT_STARTED
            else -> NoteGenerationState.PARTIAL
        }
    }

    private fun stableId(prefix: String, value: String): String =
        "$prefix-${sha256(value.toByteArray(Charsets.UTF_8)).take(16)}"

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

fun KnowledgeCard.toNoteDocument(): NoteDocument = NoteDocumentMigration.fromKnowledgeCard(this)
