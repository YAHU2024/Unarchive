package com.unarchive.android.sync

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.FileKnowledgeSyncRepository
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.KnowledgeSyncState
import com.unarchive.android.card.KnowledgeSyncKey
import com.unarchive.android.card.KnowledgeSyncRecord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ImaSyncServiceTest {
    private class FakeGateway : ImaGateway {
        var imports = 0
        var associations = 0
        var failAssociation = true
        override suspend fun connect() = Unit
        override suspend fun findNote(videoId: String): String? = null
        override suspend fun importDocument(markdown: String, folderId: String): String { imports++; return "note-1" }
        override suspend fun addToKnowledgeBase(noteId: String, title: String, kbId: String, folderId: String) {
            if (failAssociation) { failAssociation = false; error("temporary failure") }
            associations++
        }
    }

    @Test
    fun associationFailureKeepsNoteAndSecondRunOnlyAssociates() {
        val root = kotlin.io.path.createTempDirectory("ima-sync-test").toFile()
        val gateway = FakeGateway()
        val repo = FileKnowledgeSyncRepository(File(root, "sync.json"))
        val card = KnowledgeCard(
            cardId = KnowledgeCardId("bilibili", "BV_TEST"), cardVersion = "v1", canonicalUrl = "https://example.test",
            title = "测试", ownerName = "", videoDurationSeconds = 1, timingAccuracy = TranscriptTimingAccuracy.EXACT,
            transcript = listOf(TranscriptSegment(0, 1000, "内容")), createdAtEpochMs = 1, updatedAtEpochMs = 1,
            markdown = "# 测试",
        )
        val service = ImaSyncService(gateway, repo)
        val first = kotlinx.coroutines.runBlocking { service.sync(card, "kb-test") }
        assertEquals(KnowledgeSyncState.RETRYABLE_FAILURE, first.state)
        assertNotNull(repo.list().single().remoteNoteId)
        val second = kotlinx.coroutines.runBlocking { service.sync(card, "kb-test") }
        assertEquals(KnowledgeSyncState.SYNCED, second.state)
        assertEquals(1, gateway.imports)
        assertEquals(1, gateway.associations)
        root.deleteRecursively()
    }

    @Test
    fun alreadySyncedCardSkipsWithoutCallingGateway() {
        val root = kotlin.io.path.createTempDirectory("ima-sync-skip-test").toFile()
        val gateway = FakeGateway()
        val repo = FileKnowledgeSyncRepository(File(root, "sync.json"))
        val card = KnowledgeCard(
            cardId = KnowledgeCardId("bilibili", "BV_SKIP"), cardVersion = "v1", canonicalUrl = "https://example.test",
            title = "已同步", ownerName = "", videoDurationSeconds = 1, timingAccuracy = TranscriptTimingAccuracy.EXACT,
            transcript = listOf(TranscriptSegment(0, 1000, "内容")), createdAtEpochMs = 1, updatedAtEpochMs = 1,
            markdown = "# 已同步",
        )
        val key = KnowledgeSyncKey(card.cardId, card.cardVersion, "ima", "kb-test", "")
        repo.save(KnowledgeSyncRecord(key, KnowledgeSyncState.SYNCED, "note-existing", true, updatedAtEpochMs = 1))
        val result = kotlinx.coroutines.runBlocking { ImaSyncService(gateway, repo).sync(card, "kb-test") }
        assertEquals(KnowledgeSyncState.SYNCED, result.state)
        assertEquals("已同步，跳过重复写入", result.message)
        assertEquals(0, gateway.imports)
        assertEquals(0, gateway.associations)
        root.deleteRecursively()
    }
}
