package com.unarchive.android.editor

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.FileNoteContentRepository
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteContent
import com.unarchive.android.card.NoteContentMigration
import com.unarchive.android.card.NoteProjectionStatus
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MarkdownAiProposalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun proposalStoresFormalRevisionFingerprintAndDiffSummary() {
        val content = content()
        val proposal = MarkdownAiProposalBuilder.create(
            content = content,
            proposedMarkdown = "# 新标题\n\n新增一行",
            proposalId = "proposal-1",
            model = "test-model",
            createdAtEpochMs = 10L,
        )

        assertEquals(content.cardId.value, proposal.baseCardId)
        assertEquals(content.cardVersion, proposal.baseCardVersion)
        assertEquals(content.markdownRevision, proposal.baseMarkdownRevision)
        assertEquals(NoteContent.markdownFingerprint(content.markdown), proposal.baseContentFingerprint)
        val diff = proposal.diffAgainst(content.markdown)
        assertTrue(diff.addedLines > 0)
        assertTrue(diff.removedLines > 0)
        assertEquals(minOf(diff.addedLines, diff.removedLines), diff.changedLines)
        assertEquals("# 新标题", diff.addedPreview.first())
    }

    @Test
    fun pendingProposalRoundTripsAndTerminalStatusIsNotPersisted() {
        val content = content()
        val proposal = MarkdownAiProposalBuilder.create(content, "# AI", "proposal-persist", "model", 11L)
        val directory = temporaryFolder.newFolder("proposals")
        val repository = FileMarkdownAiProposalRepository(directory)

        repository.save(proposal)
        assertEquals(proposal, repository.find(content.cardId.value, content.cardVersion))
        repository.delete(content.cardId.value, content.cardVersion)
        assertNull(repository.find(content.cardId.value, content.cardVersion))
        try {
            repository.save(proposal.copy(status = MarkdownAiProposalStatus.REJECTED))
        } catch (error: IllegalArgumentException) {
            assertEquals("Only pending Markdown proposals can be persisted", error.message)
            return
        }
        throw AssertionError("terminal proposal must not be persisted")
    }

    @Test
    fun safetyValidatorRejectsDangerousLinksAndRemoteImagesButIgnoresCode() {
        val result = MarkdownAiSafetyValidator.validate(
            """
            ```text
            [code](javascript:alert(1))
            ![](https://evil.example/x.png)
            ```
            [bad](javascript:alert(1))
            ![](https://evil.example/x.png)
            <script>alert(1)</script>
            """.trimIndent(),
        )

        assertFalse(result.isSafe)
        assertTrue(result.errors.any { it.contains("链接") })
        assertTrue(result.errors.any { it.contains("图片") })
        assertTrue(result.errors.any { it.contains("HTML") })
    }

    @Test
    fun safetyValidatorAcceptsAllowlistedLinkTitlesAndRelativeAssets() {
        val result = MarkdownAiSafetyValidator.validate(
            "[视频](https://www.bilibili.com/video/BV123 \"来源\")\n![](assets/chapter-000.jpg)",
        )
        assertTrue(result.isSafe)
        assertFalse(MarkdownAiSafetyValidator.validate("![](/tmp/secret.png)").isSafe)
        assertFalse(MarkdownAiSafetyValidator.validate("![](C:/secret.png)").isSafe)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun candidateStaysPendingUntilApplyAndFormalSave() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val directory = temporaryFolder.newFolder("content")
            val contentRepository = FileNoteContentRepository(directory)
            val saved = contentRepository.save(content()).content
            val proposalRepository = FileMarkdownAiProposalRepository(temporaryFolder.newFolder("pending"))
            val viewModel = MarkdownEditorViewModel(
                saved.cardId,
                saved.cardVersion,
                contentRepository,
                dispatcher,
                draftDebounceMs = 1L,
                aiProposalGenerator = MarkdownAiProposalGenerator {
                    MarkdownAiGenerationResult("# AI 标题\n\nAI 摘要", "test-model")
                },
                aiProposalRepository = proposalRepository,
                nowEpochMs = { 20L },
            )

            viewModel.onEvent(MarkdownEditorEvent.RequestAiProposal)
            advanceUntilIdle()
            assertEquals(saved.markdown, viewModel.uiState.value.content.markdown)
            assertEquals(MarkdownAiProposalStatus.PENDING, viewModel.uiState.value.aiProposal?.status)
            assertEquals(saved.markdown, viewModel.uiState.value.draftMarkdown)

            viewModel.onEvent(MarkdownEditorEvent.ApplyAiProposal)
            assertEquals("# AI 标题\n\nAI 摘要", viewModel.uiState.value.draftMarkdown)
            assertEquals(saved.markdown, viewModel.uiState.value.content.markdown)
            assertTrue(viewModel.uiState.value.aiProposalApplied)
            advanceUntilIdle()

            viewModel.onEvent(MarkdownEditorEvent.Save)
            advanceUntilIdle()
            assertEquals(MarkdownEditorSaveState.SAVED, viewModel.uiState.value.saveState)
            assertEquals(1L, viewModel.uiState.value.content.markdownRevision)
            assertEquals("# AI 标题\n\nAI 摘要", contentRepository.find(saved.cardId, saved.cardVersion)?.markdown)
            assertNull(viewModel.uiState.value.aiProposal)
            assertNull(proposalRepository.find(saved.cardId.value, saved.cardVersion))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun candidateExpiresWhenUserEditsDuringGeneration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val directory = temporaryFolder.newFolder("stale")
            val contentRepository = FileNoteContentRepository(directory)
            val saved = contentRepository.save(content()).content
            val gate = CompletableDeferred<Unit>()
            val proposalRepository = FileMarkdownAiProposalRepository(temporaryFolder.newFolder("stale-proposals"))
            val viewModel = MarkdownEditorViewModel(
                saved.cardId, saved.cardVersion, contentRepository, dispatcher,
                draftDebounceMs = Long.MAX_VALUE,
                aiProposalGenerator = MarkdownAiProposalGenerator {
                    gate.await()
                    MarkdownAiGenerationResult("# 旧候选", "test-model")
                },
                aiProposalRepository = proposalRepository,
            )

            viewModel.onEvent(MarkdownEditorEvent.RequestAiProposal)
            runCurrent()
            viewModel.onEvent(MarkdownEditorEvent.MarkdownChanged("# 用户新编辑"))
            gate.complete(Unit)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.aiProposal)
            assertEquals(MarkdownAiProposalState.FAILED, viewModel.uiState.value.aiProposalState)
            assertTrue(viewModel.uiState.value.aiProposalError?.contains("变化") == true)
            assertNull(proposalRepository.find(saved.cardId.value, saved.cardVersion))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun restartRestoresOnlyMatchingPendingCandidateAndExplainsStaleDiscard() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val directory = temporaryFolder.newFolder("restart")
            val contentRepository = FileNoteContentRepository(directory)
            val saved = contentRepository.save(content()).content
            val proposalDirectory = temporaryFolder.newFolder("restart-proposals")
            val proposalRepository = FileMarkdownAiProposalRepository(proposalDirectory)
            val matching = MarkdownAiProposalBuilder.create(saved, "# 匹配候选", "matching", "model", 30L)
            proposalRepository.save(matching)
            val restored = MarkdownEditorViewModel(
                saved.cardId, saved.cardVersion, FileNoteContentRepository(directory), dispatcher,
                aiProposalRepository = FileMarkdownAiProposalRepository(proposalDirectory),
            )
            assertEquals(matching, restored.uiState.value.aiProposal)

            proposalRepository.save(matching)
            // A new formal revision makes the previous pending candidate stale.
            contentRepository.save(saved.copy(markdown = "# 正式新版本"))
            val discarded = MarkdownEditorViewModel(
                saved.cardId, saved.cardVersion, FileNoteContentRepository(directory), dispatcher,
                aiProposalRepository = FileMarkdownAiProposalRepository(proposalDirectory),
            )
            assertNull(discarded.uiState.value.aiProposal)
            assertEquals(MarkdownAiProposalState.FAILED, discarded.uiState.value.aiProposalState)
            assertTrue(discarded.uiState.value.aiProposalError?.contains("自动废弃") == true)
            assertNull(proposalRepository.find(saved.cardId.value, saved.cardVersion))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun unsafeCandidateIsNotCopiedIntoDraft() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val directory = temporaryFolder.newFolder("unsafe")
            val contentRepository = FileNoteContentRepository(directory)
            val saved = contentRepository.save(content()).content
            val proposalRepository = FileMarkdownAiProposalRepository(temporaryFolder.newFolder("unsafe-proposals"))
            val viewModel = MarkdownEditorViewModel(
                saved.cardId, saved.cardVersion, contentRepository, dispatcher,
                aiProposalGenerator = MarkdownAiProposalGenerator {
                    MarkdownAiGenerationResult("# AI\n[危险](javascript:alert(1))", "test-model")
                },
                aiProposalRepository = proposalRepository,
            )
            viewModel.onEvent(MarkdownEditorEvent.RequestAiProposal)
            advanceUntilIdle()
            viewModel.onEvent(MarkdownEditorEvent.ApplyAiProposal)

            assertEquals(saved.markdown, viewModel.uiState.value.draftMarkdown)
            assertEquals(MarkdownAiProposalState.FAILED, viewModel.uiState.value.aiProposalState)
            assertTrue(viewModel.uiState.value.aiProposalError?.contains("安全") == true)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun content(): NoteContent {
        val card = KnowledgeCard(
            cardId = KnowledgeCardId("bilibili", "BV_markdown-ai"),
            cardVersion = "v1",
            canonicalUrl = "https://www.bilibili.com/video/BV_markdown-ai",
            title = "Markdown AI 测试",
            ownerName = "作者",
            videoDurationSeconds = 10,
            timingAccuracy = TranscriptTimingAccuracy.EXACT,
            transcript = listOf(TranscriptSegment(0L, 1_000L, "内容")),
            analysisState = CardStageState.SKIPPED,
            screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
            markdown = "# Markdown AI 测试\n\n旧正文",
        )
        return when (val migrated = NoteContentMigration.fromKnowledgeCard(card)) {
            is com.unarchive.android.card.NoteContentMigrationDecision.Ready -> migrated.content
            is com.unarchive.android.card.NoteContentMigrationDecision.Conflict ->
                NoteContentMigration.resolveConflict(migrated, migrated.generatedMarkdown).content
        }
    }
}
