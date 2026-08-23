package com.unarchive.android.editor

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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteDocumentProposalRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun pendingProposalSurvivesRepositoryRecreationAndDelete() {
        val current = document()
        val proposal = NoteDocumentProposalBuilder.create(
            current = current,
            proposed = current.copy(
                blocks = listOf(current.blocks.single().copy(text = "AI 新摘要")),
            ),
            proposalId = "proposal-persisted",
            createdAtEpochMs = 42L,
        )
        val directory = temporaryFolder.newFolder("proposals")

        FileNoteDocumentProposalRepository(directory).save(proposal)
        val restored = FileNoteDocumentProposalRepository(directory)
            .find(current.cardId.value, current.generation.cardVersion)

        assertEquals(proposal, restored)

        val repository = FileNoteDocumentProposalRepository(directory)
        repository.delete(current.cardId.value, current.generation.cardVersion)
        assertNull(repository.find(current.cardId.value, current.generation.cardVersion))
    }

    @Test
    fun nonPendingProposalIsNotWritten() {
        val current = document()
        val proposal = NoteDocumentProposalBuilder.create(
            current = current,
            proposed = current,
            proposalId = "proposal-applied",
            createdAtEpochMs = 43L,
        ).copy(status = NoteProposalStatus.APPLIED)

        try {
            FileNoteDocumentProposalRepository(temporaryFolder.newFolder("proposals")).save(proposal)
        } catch (error: IllegalArgumentException) {
            assertEquals("Only pending proposals can be persisted", error.message)
            return
        }
        throw AssertionError("An applied proposal must not be persisted")
    }

    private fun document() = NoteDocument(
        cardId = KnowledgeCardId("bilibili", "BVproposal-recovery"),
        title = "恢复测试",
        source = NoteSource(
            canonicalUrl = "https://www.bilibili.com/video/BVproposal-recovery",
            ownerName = "UP 主",
            durationMs = 10_000L,
            timingAccuracy = TranscriptTimingAccuracy.EXACT,
        ),
        sourceTranscript = emptyList(),
        blocks = listOf(NoteBlock("summary", NoteBlockType.SUMMARY, NoteBlockOrigin.AI, text = "旧摘要")),
        generation = NoteGeneration(
            cardVersion = "recovery-version",
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
