package com.unarchive.android.editor

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardAnalysis
import com.unarchive.android.card.CardChapter
import com.unarchive.android.card.CardPoint
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteBlock
import com.unarchive.android.card.NoteBlockOrigin
import com.unarchive.android.card.NoteBlockType
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteEditingState
import com.unarchive.android.card.NoteGeneration
import com.unarchive.android.card.NoteGenerationState
import com.unarchive.android.card.NotePublishingState
import com.unarchive.android.card.NoteSource
import com.unarchive.android.card.NoteSourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDocumentAiProposalTest {
    @Test
    fun candidateReusesAiBlockIdsAndPreservesUserContent() {
        val current = document()
        val analysis = CardAnalysis(
            summary = "新的摘要",
            keyPoints = listOf("新的要点"),
            chapters = listOf(
                CardChapter(
                    title = "新的章节",
                    startMs = 0L,
                    endMs = 5_000L,
                    points = listOf(CardPoint(1_000L, "章节要点")),
                ),
            ),
        )

        val candidate = NoteDocumentAiCandidateBuilder.fromAnalysis(
            current = current,
            analysis = analysis,
            model = "deepseek-v4-flash",
            signature = "test-signature",
            updatedAtEpochMs = 9_000L,
        )

        assertEquals("新的摘要", candidate.blocks.first { it.type == NoteBlockType.SUMMARY }.text)
        assertEquals("新的要点", candidate.blocks.first { it.type == NoteBlockType.KEY_POINT }.text)
        assertEquals("新的章节", candidate.blocks.first { it.type == NoteBlockType.CHAPTER }.title)
        assertEquals("用户补充", candidate.blocks.first { it.type == NoteBlockType.USER_NOTE }.text)
        assertEquals(listOf("用户标签"), candidate.tags)
        assertEquals(
            current.blocks.first { it.type == NoteBlockType.SUMMARY }.id,
            candidate.blocks.first { it.type == NoteBlockType.SUMMARY }.id,
        )
        assertEquals(
            current.blocks.first { it.type == NoteBlockType.KEY_POINT }.id,
            candidate.blocks.first { it.type == NoteBlockType.KEY_POINT }.id,
        )
        assertEquals(
            current.blocks.first { it.type == NoteBlockType.CHAPTER }.id,
            candidate.blocks.first { it.type == NoteBlockType.CHAPTER }.id,
        )
        assertNotEquals(current.generation.cardVersion, candidate.generation.cardVersion)
        assertEquals(NoteGenerationState.COMPLETE, candidate.generation.state)
        assertTrue(candidate.updatedAtEpochMs >= current.createdAtEpochMs)
    }

    @Test
    fun candidatePreservesSourceOriginBlocksAndReferences() {
        val current = document()
        val sourceDraft = NoteBlock(
            id = "source-draft",
            type = NoteBlockType.SUMMARY,
            origin = NoteBlockOrigin.SOURCE,
            text = "原始来源摘要",
            sourceRef = NoteSourceRef(current.source.canonicalUrl, 0L, 1_000L),
        )
        val analysis = CardAnalysis(
            summary = "新的摘要",
            keyPoints = emptyList(),
            chapters = emptyList(),
        )

        val candidate = NoteDocumentAiCandidateBuilder.fromAnalysis(
            current = current.copy(blocks = current.blocks + sourceDraft),
            analysis = analysis,
            model = "deepseek-v4-flash",
            signature = "test-signature",
            updatedAtEpochMs = 9_000L,
        )

        assertEquals(sourceDraft, candidate.blocks.first { it.id == sourceDraft.id })
    }

    private fun document(): NoteDocument {
        val source = NoteSource(
            canonicalUrl = "https://www.bilibili.com/video/BV1proposal-ai",
            ownerName = "UP 主",
            durationMs = 10_000L,
            timingAccuracy = TranscriptTimingAccuracy.EXACT,
        )
        return NoteDocument(
            cardId = KnowledgeCardId("bilibili", "BV1proposal-ai"),
            title = "AI 提案测试",
            source = source,
            sourceTranscript = listOf(TranscriptSegment(0L, 1_000L, "转写")),
            blocks = listOf(
                NoteBlock("summary-1", NoteBlockType.SUMMARY, NoteBlockOrigin.AI, text = "旧摘要"),
                NoteBlock("point-1", NoteBlockType.KEY_POINT, NoteBlockOrigin.AI, text = "旧要点"),
                NoteBlock(
                    id = "chapter-1",
                    type = NoteBlockType.CHAPTER,
                    origin = NoteBlockOrigin.AI,
                    title = "旧章节",
                    startMs = 0L,
                    endMs = 5_000L,
                    sourceRef = NoteSourceRef(source.canonicalUrl, 0L, 5_000L),
                ),
                NoteBlock("user-note", NoteBlockType.USER_NOTE, NoteBlockOrigin.USER, text = "用户补充"),
                NoteBlock("tags", NoteBlockType.TAG_LIST, NoteBlockOrigin.USER, tags = listOf("用户标签")),
            ),
            tags = listOf("用户标签"),
            generation = NoteGeneration(
                cardVersion = "old-version",
                state = NoteGenerationState.COMPLETE,
                baseState = CardStageState.SUCCEEDED,
                analysisState = CardStageState.SUCCEEDED,
                screenshotsState = CardStageState.SKIPPED,
            ),
            editing = NoteEditingState(),
            publishing = NotePublishingState(),
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 2_000L,
        )
    }
}
