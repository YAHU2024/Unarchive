package com.unarchive.android.editor

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.CardAnalysis
import com.unarchive.android.card.CardChapter
import com.unarchive.android.card.CardPoint
import com.unarchive.android.card.FileNoteDocumentRepository
import com.unarchive.android.card.NoteBlock
import com.unarchive.android.card.NoteBlockOrigin
import com.unarchive.android.card.NoteBlockType
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteGeneration
import com.unarchive.android.card.NoteGenerationState
import com.unarchive.android.card.NoteSource
import com.unarchive.android.card.NoteSourceRef
import com.unarchive.android.card.KnowledgeCardId
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun editsBlocksTagsAndTitleThenPersistsWithoutChangingSourceOrigin() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FileNoteDocumentRepository(
                temporaryFolder.newFolder("notes"),
                nowEpochMs = { 9_000L },
            )
            val original = document()
            val keyPoint = original.blocks.first { it.type == NoteBlockType.KEY_POINT }
            val chapter = original.blocks.first { it.type == NoteBlockType.CHAPTER }
            val viewModel = NoteEditorViewModel(original, repository, dispatcher)

            viewModel.onEvent(NoteEditorEvent.TitleChanged(""))
            viewModel.onEvent(NoteEditorEvent.BlockTextChanged(keyPoint.id, ""))
            viewModel.onEvent(NoteEditorEvent.ChapterDescriptionChanged(chapter.id, "我补充的章节说明"))
            viewModel.onEvent(NoteEditorEvent.TagsChanged("AI, 复习, AI"))
            viewModel.onEvent(NoteEditorEvent.AddBlock(NoteBlockType.USER_NOTE))
            viewModel.onEvent(NoteEditorEvent.Save)
            advanceUntilIdle()

            val saved = requireNotNull(repository.find(original.cardId, original.generation.cardVersion))
            assertEquals(NoteEditorSaveState.SAVED, viewModel.uiState.value.saveState)
            assertEquals("未命名笔记", saved.title)
            assertFalse(saved.blocks.any { it.id == keyPoint.id })
            assertEquals(listOf("AI", "复习"), saved.tags)
            assertEquals("我补充的章节说明", saved.blocks.first { it.id == chapter.id }.text)
            assertTrue(saved.blocks.any { it.type == NoteBlockType.USER_NOTE })
            assertEquals(original.blocks.first { it.type == NoteBlockType.SUMMARY }.origin,
                saved.blocks.first { it.type == NoteBlockType.SUMMARY }.origin)
            assertEquals(original.blocks.first { it.type == NoteBlockType.CHAPTER }.sourceRef,
                saved.blocks.first { it.type == NoteBlockType.CHAPTER }.sourceRef)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedProjectionKeepsEditingStateAndRestoreReadsLastStructuredVersion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val directory = temporaryFolder.newFolder("notes")
            val repository = FileNoteDocumentRepository(
                directory = directory,
                projector = { throw IllegalStateException("投影失败") },
            )
            val viewModel = NoteEditorViewModel(document(), repository, dispatcher)

            viewModel.onEvent(NoteEditorEvent.TitleChanged("已编辑标题"))
            viewModel.onEvent(NoteEditorEvent.Save)
            advanceUntilIdle()

            assertEquals(NoteEditorSaveState.PROJECTION_PENDING, viewModel.uiState.value.saveState)
            assertEquals("投影失败", viewModel.uiState.value.errorMessage)
            assertEquals("已编辑标题", repository.find(document().cardId, document().generation.cardVersion)?.title)

            viewModel.onEvent(NoteEditorEvent.RestoreLastSaved)
            advanceUntilIdle()
            assertEquals("已编辑标题", viewModel.uiState.value.document.title)
            assertEquals(NoteEditorSaveState.FAILED, viewModel.uiState.value.saveState)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun structuredWriteFailureLeavesDirtyDraftAndReportsFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = FileNoteDocumentRepository(
                directory = temporaryFolder.newFolder("notes"),
                writer = com.unarchive.android.card.NoteDocumentAtomicWriter { _, _ ->
                    throw IOException("磁盘空间不足")
                },
            )
            val viewModel = NoteEditorViewModel(document(), repository, dispatcher)

            viewModel.onEvent(NoteEditorEvent.TitleChanged("未保存标题"))
            viewModel.onEvent(NoteEditorEvent.Save)
            advanceUntilIdle()

            assertEquals(NoteEditorSaveState.FAILED, viewModel.uiState.value.saveState)
            assertTrue(viewModel.uiState.value.isDirty)
            assertEquals("磁盘空间不足", viewModel.uiState.value.errorMessage)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aiProposalRequiresExplicitApplyAndSavePreservesUserBlocks() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val original = document().copy(
                blocks = document().blocks + NoteBlock(
                    "user-note", NoteBlockType.USER_NOTE, NoteBlockOrigin.USER, text = "我的补充",
                ),
            )
            val repository = FileNoteDocumentRepository(temporaryFolder.newFolder("notes"))
            val viewModel = NoteEditorViewModel(original, repository, dispatcher)
            advanceUntilIdle()
            val candidate = original.copy(
                blocks = original.blocks.map { block ->
                    if (block.type == NoteBlockType.SUMMARY) block.copy(text = "AI 新摘要") else block
                },
            )

            viewModel.setAiProposal(candidate, "proposal-vm", 10L)
            assertEquals(NoteProposalStatus.PENDING, viewModel.uiState.value.aiProposal?.status)
            assertEquals("AI 摘要", viewModel.uiState.value.document.blocks.first { it.type == NoteBlockType.SUMMARY }.text)

            viewModel.onEvent(NoteEditorEvent.ApplyAiProposal)

            val stateAfterApply = viewModel.uiState.value
            assertEquals(NoteEditorSaveState.DIRTY, stateAfterApply.saveState)
            assertEquals("AI 新摘要", stateAfterApply.document.blocks.first { it.type == NoteBlockType.SUMMARY }.text)
            assertEquals("我的补充", stateAfterApply.document.blocks.first { it.id == "user-note" }.text)
            assertTrue(stateAfterApply.document.editing.dirty)

            viewModel.onEvent(NoteEditorEvent.Save)
            advanceUntilIdle()
            assertEquals(NoteEditorSaveState.SAVED, viewModel.uiState.value.saveState)
            assertEquals("AI 新摘要", repository.find(original.cardId, original.generation.cardVersion)
                ?.blocks?.first { it.type == NoteBlockType.SUMMARY }?.text)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleAiProposalDoesNotOverwriteAnEditMadeAfterProposal() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val original = document()
            val repository = FileNoteDocumentRepository(temporaryFolder.newFolder("notes"))
            val viewModel = NoteEditorViewModel(original, repository, dispatcher)
            advanceUntilIdle()
            val candidate = original.copy(title = "AI 标题")

            viewModel.setAiProposal(candidate, "proposal-stale", 11L)
            viewModel.onEvent(NoteEditorEvent.TitleChanged("用户刚刚编辑的标题"))
            viewModel.onEvent(NoteEditorEvent.ApplyAiProposal)

            val state = viewModel.uiState.value
            assertEquals("用户刚刚编辑的标题", state.document.title)
            assertEquals(NoteEditorSaveState.FAILED, state.saveState)
            assertTrue(state.errorMessage?.contains("过期") == true)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun rejectAiProposalLeavesDocumentUntouchedAndMarksProposalRejected() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val original = document()
            val repository = FileNoteDocumentRepository(temporaryFolder.newFolder("notes"))
            val viewModel = NoteEditorViewModel(original, repository, dispatcher)
            advanceUntilIdle()
            viewModel.setAiProposal(original.copy(title = "AI 标题"), "proposal-reject", 12L)

            viewModel.onEvent(NoteEditorEvent.RejectAiProposal)

            assertEquals("编辑器测试", viewModel.uiState.value.document.title)
            assertEquals(NoteProposalStatus.REJECTED, viewModel.uiState.value.aiProposal?.status)
            assertEquals(NoteEditorSaveState.CLEAN, viewModel.uiState.value.saveState)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun requestAiProposalKeepsCurrentDocumentUntilUserAppliesIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val original = document()
            val repository = FileNoteDocumentRepository(temporaryFolder.newFolder("notes"))
            val generator = NoteDocumentAiProposalGenerator { current ->
                current.copy(
                    blocks = current.blocks.map { block ->
                        if (block.type == NoteBlockType.SUMMARY) block.copy(text = "AI 新摘要") else block
                    },
                )
            }
            val viewModel = NoteEditorViewModel(original, repository, dispatcher, generator)
            advanceUntilIdle()

            viewModel.onEvent(NoteEditorEvent.RequestAiProposal)
            assertEquals(NoteAiProposalState.RUNNING, viewModel.uiState.value.aiProposalState)
            advanceUntilIdle()

            assertEquals(NoteAiProposalState.IDLE, viewModel.uiState.value.aiProposalState)
            assertEquals(NoteProposalStatus.PENDING, viewModel.uiState.value.aiProposal?.status)
            assertEquals(
                "AI 摘要",
                viewModel.uiState.value.document.blocks.first { it.type == NoteBlockType.SUMMARY }.text,
            )
            assertTrue(viewModel.uiState.value.aiProposal?.changes?.isNotEmpty() == true)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun requestAiProposalRejectsChangesMadeWhileAnalyzerRuns() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val original = document()
            val repository = FileNoteDocumentRepository(temporaryFolder.newFolder("notes"))
            val gate = CompletableDeferred<Unit>()
            val generator = NoteDocumentAiProposalGenerator { current ->
                gate.await()
                current.copy(title = "AI 标题")
            }
            val viewModel = NoteEditorViewModel(original, repository, dispatcher, generator)
            advanceUntilIdle()

            viewModel.onEvent(NoteEditorEvent.RequestAiProposal)
            runCurrent()
            assertEquals(NoteAiProposalState.RUNNING, viewModel.uiState.value.aiProposalState)
            viewModel.onEvent(NoteEditorEvent.TitleChanged("用户标题"))
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(NoteAiProposalState.FAILED, viewModel.uiState.value.aiProposalState)
            assertTrue(viewModel.uiState.value.aiProposalError?.contains("变化") == true)
            assertEquals("用户标题", viewModel.uiState.value.document.title)
            assertTrue(viewModel.uiState.value.aiProposal == null)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun requestAiProposalReportsMissingGeneratorWithoutChangingDocument() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val original = document()
            val repository = FileNoteDocumentRepository(temporaryFolder.newFolder("notes"))
            val viewModel = NoteEditorViewModel(original, repository, dispatcher)
            advanceUntilIdle()

            viewModel.onEvent(NoteEditorEvent.RequestAiProposal)

            assertEquals(NoteAiProposalState.FAILED, viewModel.uiState.value.aiProposalState)
            assertTrue(viewModel.uiState.value.aiProposalError?.contains("未配置") == true)
            assertEquals(original, viewModel.uiState.value.document)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun document(): NoteDocument {
        val source = NoteSource(
            canonicalUrl = "https://www.bilibili.com/video/BV1editor",
            ownerName = "UP 主",
            durationMs = 20_000L,
            timingAccuracy = TranscriptTimingAccuracy.EXACT,
        )
        val chapter = NoteBlock(
            id = "chapter-1",
            type = NoteBlockType.CHAPTER,
            origin = NoteBlockOrigin.AI,
            title = "第一章",
            text = "原章节说明",
            startMs = 0L,
            endMs = 10_000L,
            sourceRef = NoteSourceRef(source.canonicalUrl, 0L, 10_000L),
        )
        return NoteDocument(
            cardId = KnowledgeCardId("bilibili", "BV1editor"),
            title = "编辑器测试",
            source = source,
            sourceTranscript = listOf(TranscriptSegment(0, 1_000, "原始转写")),
            blocks = listOf(
                NoteBlock("summary-1", NoteBlockType.SUMMARY, NoteBlockOrigin.AI, text = "AI 摘要"),
                NoteBlock("point-1", NoteBlockType.KEY_POINT, NoteBlockOrigin.AI, text = "AI 要点"),
                chapter,
                NoteBlock(
                    "tags-1", NoteBlockType.TAG_LIST, NoteBlockOrigin.USER,
                    tags = listOf("旧标签"),
                ),
            ),
            tags = listOf("旧标签"),
            generation = NoteGeneration(
                cardVersion = "editor-version",
                state = NoteGenerationState.COMPLETE,
                baseState = CardStageState.SUCCEEDED,
                analysisState = CardStageState.SUCCEEDED,
                screenshotsState = CardStageState.SKIPPED,
            ),
            editing = com.unarchive.android.card.NoteEditingState(),
            publishing = com.unarchive.android.card.NotePublishingState(),
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
        )
    }
}
