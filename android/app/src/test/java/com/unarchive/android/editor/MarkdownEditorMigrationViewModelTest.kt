package com.unarchive.android.editor

import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.FileNoteContentRepository
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteContentMigrationChoice
import com.unarchive.android.card.NoteContentSynchronizer
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteDocumentMigrationSnapshot
import com.unarchive.android.card.NoteDocumentRepository
import com.unarchive.android.card.NoteDocumentSaveResult
import com.unarchive.android.card.toNoteDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MarkdownEditorMigrationViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun conflictWaitsForExplicitChoiceThenOpensV3Content() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val document = card().toNoteDocument()
            val contentRepository = FileNoteContentRepository(temporaryFolder.newFolder("content"))
            val synchronizer = NoteContentSynchronizer(
                contentRepository,
                snapshotRepository(document, "# 用户 Markdown", "{\"legacy\":true}"),
            )
            val viewModel = MarkdownEditorMigrationViewModel(document, synchronizer, dispatcher)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value is MarkdownEditorMigrationUiState.Conflict)
            viewModel.onEvent(
                MarkdownEditorMigrationEvent.Resolve(NoteContentMigrationChoice.KEEP_LEGACY_MARKDOWN),
            )
            advanceUntilIdle()

            assertEquals(MarkdownEditorMigrationUiState.Ready, viewModel.uiState.value)
            assertEquals(
                "# 用户 Markdown",
                contentRepository.find(document.cardId, document.generation.cardVersion)?.markdown,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun snapshotRepository(
        document: NoteDocument,
        markdown: String,
        structuredJson: String,
    ): NoteDocumentRepository = object : NoteDocumentRepository {
        override fun list(): List<NoteDocument> = listOf(document)
        override fun listAllVersions(): List<NoteDocument> = listOf(document)
        override fun listVersions(cardId: KnowledgeCardId): List<NoteDocument> = listOf(document)
        override fun find(cardId: KnowledgeCardId, cardVersion: String?): NoteDocument? = document
        override fun save(document: NoteDocument): NoteDocumentSaveResult = error("not used")
        override fun migrationSnapshot(cardId: KnowledgeCardId, cardVersion: String) =
            NoteDocumentMigrationSnapshot(document, markdown, structuredJson)
    }

    private fun card() = KnowledgeCard(
        cardId = KnowledgeCardId("bilibili", "BV_migration"),
        cardVersion = "v1",
        canonicalUrl = "https://www.bilibili.com/video/BV_migration",
        title = "迁移测试",
        ownerName = "测试作者",
        videoDurationSeconds = 10,
        timingAccuracy = TranscriptTimingAccuracy.EXACT,
        transcript = emptyList(),
        analysisState = CardStageState.SKIPPED,
        screenshotsState = CardStageState.SKIPPED,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        markdown = "# 迁移测试",
    )
}
