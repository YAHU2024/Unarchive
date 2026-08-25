package com.unarchive.android.card

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteContentTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun v3JsonRoundTripKeepsMarkdownDraftAndProjectionState() {
        val content = content().copy(
            structuredProjection = NoteStructuredProjection(listOf("summary"), "<unknown>\n"),
            projectionStatus = NoteProjectionStatus.PARTIAL,
            draftState = NoteDraftState(
                markdown = "# 草稿",
                baseMarkdownRevision = 0L,
                contentFingerprint = NoteContent.markdownFingerprint("# 草稿"),
                updatedAtEpochMs = 3_000L,
            ),
            lastSaveError = "projection failed",
        )

        val restored = content.toJson().toNoteContent()

        assertEquals(content, restored)
        assertEquals(NoteContent.SCHEMA_VERSION, content.toJson().getInt("schema_version"))
    }

    @Test
    fun legacyMigrationIsRepeatableAndConflictPreservesBothMarkdownValues() {
        val document = content().structuredMetadata
        val generated = NoteMarkdownProjection.render(document)
        val ready = NoteContentMigration.fromNoteDocument(document, generated, "legacy-json")
        val repeated = NoteContentMigration.fromNoteDocument(document, generated, "legacy-json")

        assertEquals(ready, repeated)
        assertTrue(ready is NoteContentMigrationDecision.Ready)
        val conflict = NoteContentMigration.fromNoteDocument(document, "# 用户旧正文", "legacy-json")
        assertTrue(conflict is NoteContentMigrationDecision.Conflict)
        conflict as NoteContentMigrationDecision.Conflict
        assertEquals("# 用户旧正文", conflict.backup.markdown)
        assertEquals(generated, conflict.generatedMarkdown)
        assertEquals(
            NoteProjectionStatus.PARTIAL,
            NoteContentMigration.resolveConflict(conflict, "# 用户旧正文").content.projectionStatus,
        )
    }

    @Test
    fun repositoryPublishesOnlyCommittedRevisionAndKeepsDraftSeparate() {
        val directory = temporaryFolder.newFolder("content")
        val repository = FileNoteContentRepository(directory, nowEpochMs = { 5_000L })
        val first = repository.save(content()).content
        val draft = NoteDraftState(
            markdown = "# 草稿",
            baseMarkdownRevision = first.markdownRevision,
            contentFingerprint = NoteContent.markdownFingerprint("# 草稿"),
            updatedAtEpochMs = 6_000L,
        )
        repository.saveDraft(first, draft)

        val restored = FileNoteContentRepository(directory).find(first.cardId, first.cardVersion)
        assertEquals(first.markdown, restored?.markdown)
        assertEquals(draft, restored?.draftState)

        val changed = repository.save(first.copy(markdown = "# 正式新正文")).content
        assertEquals(1L, changed.markdownRevision)
        assertEquals(2, repository.listVersions(first.cardId).size)
    }

    @Test
    fun incompleteCommitIsIgnoredAndOlderRevisionRemainsReadable() {
        val directory = temporaryFolder.newFolder("content")
        val repository = FileNoteContentRepository(directory)
        val saved = repository.save(content()).content
        val revisionDirectory = directory.listFiles()!!.single()
            .listFiles()!!.single()
            .listFiles()!!.single()
        File(revisionDirectory, "commit.json").delete()

        assertNull(repository.find(saved.cardId, saved.cardVersion))
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun contentWriteFailureDoesNotPublishCommit() {
        val repository = FileNoteContentRepository(
            temporaryFolder.newFolder("content"),
            writer = NoteContentAtomicWriter { _, _ -> throw IOException("disk full") },
        )

        val result = repository.save(content())

        assertEquals(NoteContentSavePhase.CONTENT, result.phase)
        assertFalse(result.isComplete)
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun synchronizerPersistsRawLegacyBackupBeforePublishingV3Content() {
        val contentRepository = FileNoteContentRepository(temporaryFolder.newFolder("content"))
        val legacy = content().structuredMetadata
        val legacyMarkdown = NoteMarkdownProjection.render(legacy)
        val snapshotRepository = snapshotRepository(legacy, legacyMarkdown, "{\"legacy\":true}")

        val outcome = NoteContentSynchronizer(contentRepository, snapshotRepository)
            .prepare(legacy.cardId, legacy.generation.cardVersion)

        assertTrue(outcome is NoteContentMigrationOutcome.Ready)
        assertEquals(legacyMarkdown, (outcome as NoteContentMigrationOutcome.Ready).content.markdown)
        assertEquals(
            LegacyNoteBackup("{\"legacy\":true}", legacyMarkdown),
            contentRepository.migrationBackup(legacy.cardId, legacy.generation.cardVersion),
        )
    }

    @Test
    fun synchronizerRequiresChoiceForConflictAndKeepsSelectedMarkdown() {
        val contentRepository = FileNoteContentRepository(temporaryFolder.newFolder("content"))
        val legacy = content().structuredMetadata
        val snapshotRepository = snapshotRepository(legacy, "# 用户旧正文", "{\"legacy\":true}")
        val synchronizer = NoteContentSynchronizer(contentRepository, snapshotRepository)

        val conflict = synchronizer.prepare(legacy.cardId, legacy.generation.cardVersion)
        assertTrue(conflict is NoteContentMigrationOutcome.Conflict)
        assertNull(contentRepository.find(legacy.cardId, legacy.generation.cardVersion))

        val resolved = synchronizer.resolve(
            (conflict as NoteContentMigrationOutcome.Conflict).decision,
            NoteContentMigrationChoice.KEEP_LEGACY_MARKDOWN,
        )

        assertEquals("# 用户旧正文", (resolved as NoteContentMigrationOutcome.Ready).content.markdown)
        assertEquals(NoteProjectionStatus.PARTIAL, resolved.content.projectionStatus)
        assertEquals("# 用户旧正文", contentRepository.migrationBackup(legacy.cardId, legacy.generation.cardVersion)?.markdown)
    }

    @Test
    fun backupWriteFailureDoesNotPublishMigratedContent() {
        val legacy = content().structuredMetadata
        val repository = FileNoteContentRepository(
            temporaryFolder.newFolder("content"),
            writer = NoteContentAtomicWriter { _, _ -> throw IOException("backup disk full") },
        )
        val result = repository.saveMigrated(
            content(),
            LegacyNoteBackup("{\"legacy\":true}", content().markdown),
        )

        assertEquals(NoteContentSavePhase.BACKUP, result.phase)
        assertNull(repository.find(legacy.cardId, legacy.generation.cardVersion))
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

    private fun content(): NoteContent {
        val card = KnowledgeCard(
            cardId = KnowledgeCardId("bilibili", "BV_v3"),
            cardVersion = "v1",
            canonicalUrl = "https://www.bilibili.com/video/BV_v3",
            title = "v3",
            ownerName = "作者",
            videoDurationSeconds = 10,
            timingAccuracy = TranscriptTimingAccuracy.EXACT,
            transcript = listOf(TranscriptSegment(0, 1_000, "内容")),
            analysisState = CardStageState.SKIPPED,
            screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 2_000L,
            markdown = "# v3",
        )
        val document = card.toNoteDocument()
        val markdown = NoteMarkdownProjection.render(document)
        return NoteContent(
            cardId = document.cardId,
            cardVersion = document.generation.cardVersion,
            markdown = markdown,
            markdownRevision = 0L,
            structuredMetadata = document,
            createdAtEpochMs = document.createdAtEpochMs,
            updatedAtEpochMs = document.updatedAtEpochMs,
        )
    }
}
