package com.unarchive.android.editor

import com.unarchive.android.card.CardAnalysis
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.NoteBlock
import com.unarchive.android.card.NoteBlockOrigin
import com.unarchive.android.card.NoteBlockType
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteGeneration
import com.unarchive.android.card.NoteGenerationState
import com.unarchive.android.card.NotePoint
import com.unarchive.android.card.NoteSourceRef
import java.security.MessageDigest

/** Boundary between the analyzer's CardAnalysis DTO and the editable note model. */
fun interface NoteDocumentAiProposalGenerator {
    suspend fun generate(document: NoteDocument): NoteDocument
}

object NoteDocumentAiCandidateBuilder {
    fun fromAnalysis(
        current: NoteDocument,
        analysis: CardAnalysis,
        model: String,
        signature: String,
        updatedAtEpochMs: Long,
    ): NoteDocument {
        val existingAiBlocks = current.blocks.filter { it.origin == NoteBlockOrigin.AI }
        val summaryId = existingAiBlocks.firstOrNull { it.type == NoteBlockType.SUMMARY }?.id
            ?: stableId("summary-ai", analysis.summary)
        val keyPointIds = existingAiBlocks.filter { it.type == NoteBlockType.KEY_POINT }.map { it.id }
        val chapterIds = existingAiBlocks.filter { it.type == NoteBlockType.CHAPTER }.map { it.id }
        val generatedBlocks = buildList {
            add(NoteBlock(summaryId, NoteBlockType.SUMMARY, NoteBlockOrigin.AI, text = analysis.summary))
            analysis.keyPoints.forEachIndexed { index, point ->
                add(
                    NoteBlock(
                        id = keyPointIds.getOrNull(index) ?: stableId("key-point", "$index|$point"),
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
                        id = chapterIds.getOrNull(index) ?: stableId("chapter", "$index|${chapter.startMs}|${chapter.title}"),
                        type = NoteBlockType.CHAPTER,
                        origin = NoteBlockOrigin.AI,
                        title = chapter.title,
                        startMs = chapter.startMs,
                        endMs = chapter.endMs,
                        points = points,
                        sourceRef = NoteSourceRef(
                            url = current.source.canonicalUrl,
                            startMs = chapter.startMs,
                            endMs = chapter.endMs,
                            timingAccuracy = current.source.timingAccuracy,
                        ),
                        assetRefs = current.assets
                            .filter { it.chapterIndex == index }
                            .map { it.assetId },
                    ),
                )
            }
        }
        // Source-origin drafts and user-owned blocks are never replaced by an
        // analyzer candidate. Only existing AI blocks participate in ID reuse.
        val preservedBlocks = current.blocks.filter { it.origin != NoteBlockOrigin.AI }
        val cardVersion = KnowledgeCard.version(
            canonicalUrl = current.source.canonicalUrl,
            title = current.title,
            ownerName = current.source.ownerName,
            transcript = current.sourceTranscript,
            analysis = analysis,
            tags = current.tags,
            generationSignature = signature,
            assetHashes = current.assets.map { it.sha256 },
        )
        val screenshotsState = current.generation.screenshotsState
        val generationState = if (screenshotsState == CardStageState.PARTIAL || screenshotsState == CardStageState.FAILED) {
            NoteGenerationState.PARTIAL
        } else {
            NoteGenerationState.COMPLETE
        }
        return current.copy(
            blocks = generatedBlocks + preservedBlocks,
            generation = NoteGeneration(
                cardVersion = cardVersion,
                model = model,
                signature = signature,
                state = generationState,
                baseState = CardStageState.SUCCEEDED,
                analysisState = CardStageState.SUCCEEDED,
                screenshotsState = screenshotsState,
                lastError = null,
            ),
            editing = current.editing.copy(dirty = false, lastSaveError = null),
            updatedAtEpochMs = maxOf(updatedAtEpochMs, current.createdAtEpochMs),
        )
    }

    private fun stableId(prefix: String, value: String): String =
        "$prefix-${sha256(value.toByteArray(Charsets.UTF_8)).take(16)}"

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
