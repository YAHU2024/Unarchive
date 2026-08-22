package com.unarchive.android.editor

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDocumentProposalTest {
    @Test
    fun builderReportsAddedRemovedAndUpdatedBlocks() {
        val current = document()
        val proposed = current.copy(
            blocks = listOf(
                current.blocks.first { it.id == "summary" }.copy(text = "更新后的摘要"),
                current.blocks.first { it.id == "user-note" },
                NoteBlock("new-point", NoteBlockType.KEY_POINT, NoteBlockOrigin.AI, text = "新增要点"),
            ),
        )

        val proposal = NoteDocumentProposalBuilder.create(current, proposed, "proposal-1", 42L)

        assertEquals(
            setOf(NoteProposalChangeKind.UPDATED, NoteProposalChangeKind.REMOVED, NoteProposalChangeKind.ADDED),
            proposal.changes.map { it.kind }.toSet(),
        )
        assertEquals(42L, proposal.createdAtEpochMs)
        assertEquals(NoteProposalStatus.PENDING, proposal.status)
        assertTrue(proposal.baseContentFingerprint.isNotBlank())
    }

    @Test
    fun applierPreservesUserBlocksAndTagsEvenWhenCandidateContainsCollisions() {
        val current = document()
        val proposed = current.copy(
            tags = listOf("AI 标签"),
            blocks = listOf(
                NoteBlock("summary", NoteBlockType.SUMMARY, NoteBlockOrigin.AI, text = "AI 新摘要"),
                NoteBlock("user-note", NoteBlockType.USER_NOTE, NoteBlockOrigin.AI, text = "AI 不得覆盖"),
                NoteBlock("ai-tags", NoteBlockType.TAG_LIST, NoteBlockOrigin.AI, tags = listOf("AI 标签")),
            ),
        )
        val proposal = NoteDocumentProposalBuilder.create(current, proposed, "proposal-2", 43L)

        val applied = NoteDocumentProposalApplier.apply(current, proposal)

        assertEquals("AI 新摘要", applied.blocks.first { it.id == "summary" }.text)
        assertEquals("用户自己的想法", applied.blocks.first { it.id == "user-note" }.text)
        assertEquals(listOf("用户标签"), applied.tags)
        assertEquals(1, applied.blocks.count { it.type == NoteBlockType.TAG_LIST })
        assertEquals(NoteProposalStatus.PENDING, proposal.status)
        assertTrue(applied.editing.dirty)
    }

    @Test(expected = IllegalArgumentException::class)
    fun applierRejectsStaleContentFingerprintBeforeReplacement() {
        val current = document()
        val proposal = NoteDocumentProposalBuilder.create(
            current,
            current.copy(blocks = current.blocks.map { block ->
                if (block.id == "summary") block.copy(text = "候选摘要") else block
            }),
            "proposal-3",
            44L,
        )

        val edited = current.copy(
            blocks = current.blocks.map { block ->
                if (block.id == "summary") block.copy(text = "用户刚刚编辑") else block
            },
        )
        NoteDocumentProposalApplier.apply(edited, proposal)
    }

    private fun document(): NoteDocument {
        val source = NoteSource(
            canonicalUrl = "https://www.bilibili.com/video/BV1proposal",
            ownerName = "UP 主",
            durationMs = 10_000L,
            timingAccuracy = TranscriptTimingAccuracy.EXACT,
        )
        return NoteDocument(
            cardId = KnowledgeCardId("bilibili", "BV1proposal"),
            title = "提案测试",
            source = source,
            sourceTranscript = listOf(TranscriptSegment(0L, 1_000L, "转写")),
            blocks = listOf(
                NoteBlock("summary", NoteBlockType.SUMMARY, NoteBlockOrigin.AI, text = "旧摘要"),
                NoteBlock("old-point", NoteBlockType.KEY_POINT, NoteBlockOrigin.AI, text = "旧要点"),
                NoteBlock("user-note", NoteBlockType.USER_NOTE, NoteBlockOrigin.USER, text = "用户自己的想法"),
                NoteBlock("user-tags", NoteBlockType.TAG_LIST, NoteBlockOrigin.USER, tags = listOf("用户标签")),
            ),
            tags = listOf("用户标签"),
            generation = NoteGeneration(
                cardVersion = "proposal-version",
                state = NoteGenerationState.COMPLETE,
                baseState = CardStageState.SUCCEEDED,
                analysisState = CardStageState.SUCCEEDED,
                screenshotsState = CardStageState.SKIPPED,
            ),
            editing = NoteEditingState(),
            publishing = NotePublishingState(),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
    }
}
