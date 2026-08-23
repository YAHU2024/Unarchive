package com.unarchive.android.card

import com.unarchive.android.asr.TranscriptTimingAccuracy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteRelationRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun saveAddsDurableSourceAndTagRelationsAndRepeatedSaveIsIdempotent() {
        val repository = FileNoteDocumentRepository(temporaryFolder.newFolder("notes"), nowEpochMs = { 5_000L })
        val document = card("source", tags = listOf("跑步", "训练")).toNoteDocument()

        val first = repository.save(document).document
        val second = repository.save(first.copy(editing = first.editing.copy(dirty = true))).document

        assertEquals(3, second.relations.size)
        assertEquals(1, second.relations.count { it.type == CardRelationType.SOURCE_VIDEO })
        assertEquals(2, second.relations.count { it.type == CardRelationType.TAG })
        assertEquals(0L, first.editing.contentRevision)
        assertEquals(0L, second.editing.contentRevision)
        assertTrue(second.relations.all { it.sourceCardId == second.cardId.value })
    }

    @Test
    fun userLinkIsIdempotentUpdatesExplanationAndProducesReverseLink() {
        val repository = FileNoteDocumentRepository(temporaryFolder.newFolder("notes"), nowEpochMs = { 5_000L })
        val first = repository.save(card("one").toNoteDocument()).document
        val second = repository.save(card("two").toNoteDocument()).document
        val relationRepository = NoteRelationRepository(repository, nowEpochMs = { 7_000L })

        val created = relationRepository.addUserLink(
            first.cardId,
            second.cardId,
            label = "延伸阅读",
            description = "同一训练主题",
        )
        assertTrue(created.isSuccess)
        val repeated = relationRepository.addUserLink(
            first.cardId,
            second.cardId,
            label = "延伸阅读",
            description = "补充说明",
        )
        assertTrue(repeated.isSuccess)

        val persisted = repository.find(first.cardId, first.generation.cardVersion)
        assertNotNull(persisted)
        assertEquals(1, persisted!!.relations.count { it.type == CardRelationType.USER_LINK })
        assertEquals("补充说明", persisted.relations.single { it.type == CardRelationType.USER_LINK }.description)
        val snapshot = relationRepository.snapshot(repository.list(), second.cardId)
        assertEquals(1, snapshot.incoming.size)
        assertEquals(first.cardId.value, snapshot.incoming.single().sourceCardId)
    }

    @Test
    fun removalNeverDeletesSourceRelationAndPersistsAcrossRestart() {
        val directory = temporaryFolder.newFolder("notes")
        val repository = FileNoteDocumentRepository(directory, nowEpochMs = { 5_000L })
        val first = repository.save(card("one").toNoteDocument()).document
        val second = repository.save(card("two").toNoteDocument()).document
        val relationRepository = NoteRelationRepository(repository, nowEpochMs = { 7_000L })
        val linked = relationRepository.addUserLink(first.cardId, second.cardId, "第二篇", "")
        val relationId = linked.document!!.relations.single { it.type == CardRelationType.USER_LINK }.relationId

        val removed = relationRepository.removeRelation(first.cardId, relationId)
        assertTrue(removed.isSuccess)
        val after = repository.find(first.cardId, first.generation.cardVersion)!!
        assertEquals(1, after.relations.count { it.type == CardRelationType.SOURCE_VIDEO })
        assertEquals(0, after.relations.count { it.type == CardRelationType.USER_LINK })

        val sourceRelation = after.relations.single { it.type == CardRelationType.SOURCE_VIDEO }.relationId
        val rejected = relationRepository.removeRelation(first.cardId, sourceRelation)
        assertEquals("来源视频关系是笔记事实，不能删除。", rejected.error)
        val restarted = FileNoteDocumentRepository(directory, nowEpochMs = { 99_000L })
        assertNotNull(restarted.find(first.cardId, first.generation.cardVersion))
    }

    @Test
    fun removingTagRelationAlsoRemovesTagFromDocument() {
        val repository = FileNoteDocumentRepository(temporaryFolder.newFolder("notes"), nowEpochMs = { 5_000L })
        val document = repository.save(card("tagged", tags = listOf("跑步")).toNoteDocument()).document
        val relationRepository = NoteRelationRepository(repository, nowEpochMs = { 7_000L })
        val tagRelation = document.relations.single { it.type == CardRelationType.TAG }

        val removed = relationRepository.removeRelation(document.cardId, tagRelation.relationId)
        assertTrue(removed.isSuccess)
        val persisted = repository.find(document.cardId, document.generation.cardVersion)!!
        assertTrue(persisted.tags.isEmpty())
        assertTrue(persisted.relations.none { it.type == CardRelationType.TAG })
        assertTrue(persisted.blocks.none { it.type == NoteBlockType.TAG_LIST })
    }

    @Test
    fun selfLinkAndMissingTargetAreRejectedWithoutWriting() {
        val repository = FileNoteDocumentRepository(temporaryFolder.newFolder("notes"))
        val first = repository.save(card("one").toNoteDocument()).document
        val relationRepository = NoteRelationRepository(repository)

        assertEquals("不能把笔记关联到自身。", relationRepository.addUserLink(first.cardId, first.cardId, "", "").error)
        assertEquals(
            "找不到要关联的笔记。",
            relationRepository.addUserLink(first.cardId, KnowledgeCardId("bilibili", "missing"), "", "").error,
        )
        assertEquals(1, repository.find(first.cardId, first.generation.cardVersion)!!.relations.size)
    }

    private fun card(videoId: String, tags: List<String> = emptyList()): KnowledgeCard = KnowledgeCard(
        cardId = KnowledgeCardId("bilibili", "BV$videoId"),
        cardVersion = "version-$videoId",
        canonicalUrl = "https://www.bilibili.com/video/BV$videoId",
        title = "笔记 $videoId",
        ownerName = "UP 主",
        videoDurationSeconds = 20,
        timingAccuracy = TranscriptTimingAccuracy.EXACT,
        transcript = emptyList(),
        analysis = CardAnalysis("摘要", listOf("要点"), emptyList()),
        tags = tags,
        analysisState = CardStageState.SUCCEEDED,
        screenshotsState = CardStageState.SKIPPED,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 2_000L,
        markdown = "# 笔记",
    )
}
