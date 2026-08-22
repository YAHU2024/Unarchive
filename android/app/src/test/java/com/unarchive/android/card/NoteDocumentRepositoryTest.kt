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

class NoteDocumentRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savesStructuredDocumentBeforeMarkdownAndReloadsAfterRepositoryRestart() {
        val directory = temporaryFolder.newFolder("notes")
        val document = document()
        val repository = FileNoteDocumentRepository(directory, nowEpochMs = { 5_000L })

        val result = repository.save(document)

        assertEquals(NoteDocumentSavePhase.COMPLETE, result.phase)
        assertTrue(result.isComplete)
        assertTrue(result.structuredPersisted)
        assertTrue(result.markdownPersisted)
        assertEquals(0L, result.document.editing.contentRevision)
        assertEquals(5_000L, result.document.updatedAtEpochMs)
        assertNotNull(result.markdown)

        val restarted = FileNoteDocumentRepository(directory, nowEpochMs = { 99_000L })
        assertEquals(result.document, restarted.find(document.cardId, document.generation.cardVersion))
        assertEquals(1, restarted.list().size)
        assertEquals(1, restarted.listVersions(document.cardId).size)
    }

    @Test
    fun incrementsRevisionOnlyWhenContentChanges() {
        val repository = FileNoteDocumentRepository(temporaryFolder.newFolder("notes"), nowEpochMs = { 7_000L })
        val first = repository.save(document()).document
        val same = repository.save(first.copy(
            editing = first.editing.copy(dirty = true),
            updatedAtEpochMs = 1_000L,
        )).document
        val changed = repository.save(same.copy(
            title = "已编辑标题",
            editing = same.editing.copy(dirty = true),
        )).document
        val repeated = repository.save(changed.copy(editing = changed.editing.copy(dirty = true))).document

        assertEquals(0L, first.editing.contentRevision)
        assertEquals(0L, same.editing.contentRevision)
        assertEquals(1L, changed.editing.contentRevision)
        assertEquals(1L, repeated.editing.contentRevision)
        assertFalse(repeated.editing.dirty)
        assertNull(repeated.editing.lastSaveError)
    }

    @Test
    fun corruptJsonIsIgnoredWithoutAffectingOtherVersions() {
        val directory = temporaryFolder.newFolder("notes")
        val repository = FileNoteDocumentRepository(directory)
        val saved = repository.save(document()).document
        val versionDirectory = directory.listFiles()!!.single().listFiles()!!.single()
        File(versionDirectory, "note.json").writeText("{broken", Charsets.UTF_8)
        File(versionDirectory, "note.json.tmp").writeText("partial", Charsets.UTF_8)

        assertNull(repository.find(saved.cardId, saved.generation.cardVersion))
        assertTrue(repository.list().isEmpty())
        assertTrue(File(versionDirectory, "note.json.tmp").isFile)
    }

    @Test
    fun projectionFailureRetainsStructuredDocumentAndRecordsError() {
        val directory = temporaryFolder.newFolder("notes")
        val repository = FileNoteDocumentRepository(
            directory = directory,
            projector = { throw IllegalStateException("projection failed") },
            nowEpochMs = { 8_000L },
        )

        val result = repository.save(document())
        val persisted = repository.find(document().cardId, document().generation.cardVersion)

        assertEquals(NoteDocumentSavePhase.MARKDOWN_PROJECTION, result.phase)
        assertTrue(result.structuredPersisted)
        assertFalse(result.markdownPersisted)
        assertEquals("projection failed", result.error)
        assertEquals("projection failed", persisted?.editing?.lastSaveError)
        assertEquals("测试笔记", persisted?.title)
        assertTrue(directory.walkTopDown().none { it.isFile && it.name == "note.md" })
    }

    @Test
    fun structuredWriteFailureIsExplicitAndDoesNotCreateNote() {
        val directory = temporaryFolder.newFolder("notes")
        val repository = FileNoteDocumentRepository(
            directory = directory,
            writer = NoteDocumentAtomicWriter { _, _ -> throw IOException("disk full") },
        )

        val result = repository.save(document())

        assertEquals(NoteDocumentSavePhase.STRUCTURED, result.phase)
        assertFalse(result.structuredPersisted)
        assertFalse(result.markdownPersisted)
        assertEquals("disk full", result.error)
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun markdownWriteFailureKeepsStructuredDocument() {
        val directory = temporaryFolder.newFolder("notes")
        val repository = FileNoteDocumentRepository(
            directory = directory,
            writer = NoteDocumentAtomicWriter { target, bytes ->
                if (target.name == "note.md") throw IOException("markdown disk full")
                target.parentFile!!.mkdirs()
                target.writeBytes(bytes)
            },
        )

        val result = repository.save(document())
        val persisted = repository.find(document().cardId, document().generation.cardVersion)

        assertEquals(NoteDocumentSavePhase.MARKDOWN, result.phase)
        assertTrue(result.structuredPersisted)
        assertFalse(result.markdownPersisted)
        assertEquals("markdown disk full", persisted?.editing?.lastSaveError)
        assertTrue(directory.walkTopDown().none { it.isFile && it.name == "note.md" })
    }

    @Test
    fun publishingMetadataFailureKeepsMarkdownAndStructuredContent() {
        val directory = temporaryFolder.newFolder("notes")
        var jsonWrites = 0
        val repository = FileNoteDocumentRepository(
            directory = directory,
            writer = NoteDocumentAtomicWriter { target, bytes ->
                if (target.name == "note.json") {
                    jsonWrites++
                    if (jsonWrites >= 2) throw IOException("metadata disk full")
                }
                target.parentFile!!.mkdirs()
                target.writeBytes(bytes)
            },
        )

        val result = repository.save(document())

        assertEquals(NoteDocumentSavePhase.PUBLISHING_METADATA, result.phase)
        assertTrue(result.structuredPersisted)
        assertTrue(result.markdownPersisted)
        assertTrue(directory.walkTopDown().any { it.isFile && it.name == "note.md" })
        assertNotNull(repository.find(document().cardId, document().generation.cardVersion))
    }

    private fun document(): NoteDocument {
        val card = KnowledgeCard(
            cardId = KnowledgeCardId("bilibili", "BV1persist"),
            cardVersion = "card-version-1",
            canonicalUrl = "https://www.bilibili.com/video/BV1persist",
            title = "测试笔记",
            ownerName = "UP 主",
            videoDurationSeconds = 20,
            timingAccuracy = TranscriptTimingAccuracy.EXACT,
            transcript = listOf(TranscriptSegment(0, 1_000, "第一段")),
            analysis = CardAnalysis("摘要", listOf("要点"), emptyList()),
            analysisState = CardStageState.SUCCEEDED,
            screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 2_000L,
            markdown = "# 旧卡片",
        )
        return card.toNoteDocument()
    }
}
