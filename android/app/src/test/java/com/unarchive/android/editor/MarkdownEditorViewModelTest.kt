package com.unarchive.android.editor

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.FileNoteContentRepository
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteContent
import com.unarchive.android.card.NoteContentAtomicWriter
import com.unarchive.android.card.NoteContentMigration
import com.unarchive.android.card.NoteMarkdownProjection
import com.unarchive.android.card.NoteProjectionStatus
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MarkdownEditorViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun restartExposesDraftWithoutReplacingFormalMarkdown() {
        val directory = temporaryFolder.newFolder("content")
        val repository = FileNoteContentRepository(directory, nowEpochMs = { 2_000L })
        val saved = repository.save(content()).content
        repository.saveDraft(saved, draft("# 未提交"))

        val viewModel = MarkdownEditorViewModel(saved.cardId, saved.cardVersion, FileNoteContentRepository(directory))

        assertEquals(saved.markdown, viewModel.uiState.value.draftMarkdown)
        assertEquals(saved.markdown, viewModel.uiState.value.content.markdown)
        assertEquals("# 未提交", viewModel.uiState.value.recoveryDraft?.markdown)
    }

    @Test
    fun applyingRecoveryRequiresExplicitEventAndDiscardRemovesPrompt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val directory = temporaryFolder.newFolder("content")
            val repository = FileNoteContentRepository(directory)
            val saved = repository.save(content()).content
            repository.saveDraft(saved, draft("# 恢复"))
            val viewModel = MarkdownEditorViewModel(saved.cardId, saved.cardVersion, FileNoteContentRepository(directory), dispatcher)

            viewModel.onEvent(MarkdownEditorEvent.ApplyRecoveredDraft)
            assertEquals("# 恢复", viewModel.uiState.value.draftMarkdown)
            assertNull(viewModel.uiState.value.recoveryDraft)

            val recreated = MarkdownEditorViewModel(saved.cardId, saved.cardVersion, FileNoteContentRepository(directory), dispatcher)
            recreated.onEvent(MarkdownEditorEvent.DiscardRecoveredDraft)
            advanceUntilIdle()

            val afterDiscard = MarkdownEditorViewModel(saved.cardId, saved.cardVersion, FileNoteContentRepository(directory), dispatcher)
            assertNull(afterDiscard.uiState.value.recoveryDraft)
            assertEquals(saved.markdown, afterDiscard.uiState.value.draftMarkdown)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun markdownChangesAutosaveDraftWithoutIncrementingFormalRevision() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val directory = temporaryFolder.newFolder("content")
            val repository = FileNoteContentRepository(directory, nowEpochMs = { 8_000L })
            val saved = repository.save(content()).content
            val viewModel = MarkdownEditorViewModel(saved.cardId, saved.cardVersion, repository, dispatcher, draftDebounceMs = 500L)

            viewModel.onEvent(MarkdownEditorEvent.MarkdownChanged("# 自动草稿"))
            advanceTimeBy(499L)
            runCurrent()
            assertNull(FileNoteContentRepository(directory).find(saved.cardId, saved.cardVersion)?.draftState)
            advanceTimeBy(1L)
            advanceUntilIdle()

            val restored = FileNoteContentRepository(directory).find(saved.cardId, saved.cardVersion)
            assertEquals(0L, restored?.markdownRevision)
            assertEquals("# 自动草稿", restored?.draftState?.markdown)
            assertEquals(MarkdownEditorSaveState.DIRTY, viewModel.uiState.value.saveState)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun activeSessionAutosavesDoNotCreateRecoveryPrompts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val directory = temporaryFolder.newFolder("active-session-draft")
            val repository = FileNoteContentRepository(directory)
            val saved = repository.save(content()).content
            val viewModel = MarkdownEditorViewModel(
                saved.cardId,
                saved.cardVersion,
                repository,
                dispatcher,
                draftDebounceMs = 100L,
            )

            viewModel.onEvent(MarkdownEditorEvent.MarkdownChanged("# 第一次输入"))
            advanceTimeBy(101L)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.recoveryDraft)

            viewModel.onEvent(MarkdownEditorEvent.MarkdownChanged("# 第二次输入"))
            advanceTimeBy(101L)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.recoveryDraft)
            assertEquals("# 第二次输入", repository.find(saved.cardId, saved.cardVersion)?.draftState?.markdown)

            val recreated = MarkdownEditorViewModel(
                saved.cardId,
                saved.cardVersion,
                FileNoteContentRepository(directory),
                dispatcher,
            )
            assertEquals("# 第二次输入", recreated.uiState.value.recoveryDraft?.markdown)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun formalSaveIncrementsRevisionAndMarksProjectionCurrent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val directory = temporaryFolder.newFolder("content")
            val repository = FileNoteContentRepository(directory)
            val saved = repository.save(content()).content
            val viewModel = MarkdownEditorViewModel(saved.cardId, saved.cardVersion, repository, dispatcher)

            viewModel.onEvent(MarkdownEditorEvent.MarkdownChanged("# 正式正文"))
            viewModel.onEvent(MarkdownEditorEvent.Save)
            advanceUntilIdle()

            assertEquals(MarkdownEditorSaveState.SAVED, viewModel.uiState.value.saveState)
            assertEquals(1L, viewModel.uiState.value.content.markdownRevision)
            assertEquals("# 正式正文", viewModel.uiState.value.content.markdown)
            assertEquals(com.unarchive.android.card.NoteProjectionStatus.CURRENT,
                viewModel.uiState.value.content.projectionStatus)
            assertNull(FileNoteContentRepository(directory).find(saved.cardId, saved.cardVersion)?.draftState)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun formalSaveWritesBackSupportedFieldsAndRepeatedSaveKeepsRevision() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val directory = temporaryFolder.newFolder("writeback")
            val repository = FileNoteContentRepository(directory)
            val saved = repository.save(content()).content
            val viewModel = MarkdownEditorViewModel(saved.cardId, saved.cardVersion, repository, dispatcher)
            val edited = saved.markdown
                .replace("# 编辑器测试", "# 新标题")
                .replace("原始转写待整理", "新的摘要")

            viewModel.onEvent(MarkdownEditorEvent.MarkdownChanged(edited))
            viewModel.onEvent(MarkdownEditorEvent.Save)
            advanceUntilIdle()

            assertEquals(MarkdownEditorSaveState.SAVED, viewModel.uiState.value.saveState)
            assertEquals(NoteProjectionStatus.CURRENT, viewModel.uiState.value.content.projectionStatus)
            assertEquals("新标题", viewModel.uiState.value.content.structuredMetadata.title)
            assertEquals(
                "新的摘要",
                viewModel.uiState.value.content.structuredMetadata.blocks
                    .first { it.type == com.unarchive.android.card.NoteBlockType.SUMMARY }.text,
            )
            assertEquals(1L, viewModel.uiState.value.content.markdownRevision)

            viewModel.onEvent(MarkdownEditorEvent.Save)
            advanceUntilIdle()
            assertEquals(1L, viewModel.uiState.value.content.markdownRevision)
            assertEquals(2, repository.listVersions(saved.cardId).size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun partialWritebackStillPublishesMarkdownAndKeepsProjectionDiagnosticSeparate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val directory = temporaryFolder.newFolder("partial-writeback")
            val repository = FileNoteContentRepository(directory)
            val saved = repository.save(content()).content
            val viewModel = MarkdownEditorViewModel(saved.cardId, saved.cardVersion, repository, dispatcher)
            val edited = saved.markdown + "\n## 自定义\n| A | B |\n| - | - |\n"

            viewModel.onEvent(MarkdownEditorEvent.MarkdownChanged(edited))
            viewModel.onEvent(MarkdownEditorEvent.Save)
            advanceUntilIdle()

            assertEquals(MarkdownEditorSaveState.SAVED, viewModel.uiState.value.saveState)
            assertEquals(NoteProjectionStatus.PARTIAL, viewModel.uiState.value.content.projectionStatus)
            assertNull(viewModel.uiState.value.content.lastSaveError)
            assertEquals(edited, repository.find(saved.cardId, saved.cardVersion)?.markdown)
            assertTrue(
                repository.find(saved.cardId, saved.cardVersion)?.structuredProjection?.unknownMarkdown
                    ?.contains("## 自定义") == true,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun formalSaveFailureKeepsLastKnownGoodFormalContent() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val directory = temporaryFolder.newFolder("content")
            val initialRepository = FileNoteContentRepository(directory)
            val saved = initialRepository.save(content()).content
            val failingRepository = FileNoteContentRepository(directory, writer = NoteContentAtomicWriter { _, _ -> throw IOException("磁盘空间不足") })
            val viewModel = MarkdownEditorViewModel(saved.cardId, saved.cardVersion, failingRepository, dispatcher)

            viewModel.onEvent(MarkdownEditorEvent.MarkdownChanged("# 失败正文"))
            viewModel.onEvent(MarkdownEditorEvent.Save)
            advanceUntilIdle()

            assertEquals(MarkdownEditorSaveState.FAILED, viewModel.uiState.value.saveState)
            assertEquals("# 失败正文", viewModel.uiState.value.draftMarkdown)
            assertEquals(saved.markdown, viewModel.uiState.value.content.markdown)
            assertEquals(saved.markdown, FileNoteContentRepository(directory).find(saved.cardId, saved.cardVersion)?.markdown)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleSaveResultCannotReplaceNewerEdit() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(3)
        try {
            val directory = temporaryFolder.newFolder("content")
            val normal = FileNoteContentRepository(directory)
            val saved = normal.save(content()).content
            val blocking = FileNoteContentRepository(directory, writer = NoteContentAtomicWriter { target, bytes ->
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS)) { "test writer was not released" }
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                completed.countDown()
            })
            val viewModel = MarkdownEditorViewModel(
                saved.cardId, saved.cardVersion, blocking, Dispatchers.IO,
                draftDebounceMs = Long.MAX_VALUE,
            )

            viewModel.onEvent(MarkdownEditorEvent.MarkdownChanged("# 旧保存"))
            viewModel.onEvent(MarkdownEditorEvent.Save)
            runCurrent()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            viewModel.onEvent(MarkdownEditorEvent.MarkdownChanged("# 新编辑"))
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            runCurrent()

            assertEquals("# 新编辑", viewModel.uiState.value.draftMarkdown)
            assertEquals(MarkdownEditorSaveState.DIRTY, viewModel.uiState.value.saveState)
        } finally {
            release.countDown()
            Dispatchers.resetMain()
        }
    }

    private fun draft(markdown: String) = com.unarchive.android.card.NoteDraftState(
        markdown = markdown,
        baseMarkdownRevision = 0L,
        contentFingerprint = NoteContent.markdownFingerprint(markdown),
        updatedAtEpochMs = 3_000L,
    )

    private fun content(): NoteContent {
        val card = KnowledgeCard(
            cardId = KnowledgeCardId("bilibili", "BV_vm"),
            cardVersion = "v1",
            canonicalUrl = "https://www.bilibili.com/video/BV_vm",
            title = "编辑器测试",
            ownerName = "作者",
            videoDurationSeconds = 10,
            timingAccuracy = TranscriptTimingAccuracy.EXACT,
            transcript = listOf(TranscriptSegment(0, 1_000, "内容")),
            analysisState = CardStageState.SKIPPED,
            screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
            markdown = "# 编辑器测试",
        )
        return when (val migrated = NoteContentMigration.fromKnowledgeCard(card)) {
            is com.unarchive.android.card.NoteContentMigrationDecision.Ready -> migrated.content
            is com.unarchive.android.card.NoteContentMigrationDecision.Conflict ->
                NoteContentMigration.resolveConflict(migrated, migrated.generatedMarkdown).content
        }
    }
}
