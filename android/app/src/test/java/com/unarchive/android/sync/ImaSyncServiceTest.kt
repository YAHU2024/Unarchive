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
import org.junit.Assert.assertTrue
import org.junit.Test

class ImaSyncServiceTest {
    private class FakeGateway : ImaGateway {
        var imports = 0
        var appends = 0
        var associations = 0
        var importedFolderId: String? = null
        var associatedFolderId: String? = null
        var importedMarkdown: String? = null
        var appendedMarkdown: String? = null
        var foundNoteId: String? = null
        var failAssociation = true
        var failNextAppend = false
        override suspend fun connect() = Unit
        override suspend fun findNote(videoId: String): String? = foundNoteId
        override suspend fun importDocument(markdown: String, folderId: String): String {
            importedFolderId = folderId
            importedMarkdown = markdown
            imports++
            return "note-1"
        }
        override suspend fun appendDocument(noteId: String, markdown: String) {
            appends++
            appendedMarkdown = markdown
            if (failNextAppend) {
                failNextAppend = false
                error("temporary append failure")
            }
            assertTrue(markdown.contains("Unarchive 内容修订"))
        }
        override suspend fun addToKnowledgeBase(noteId: String, title: String, kbId: String, folderId: String) {
            associatedFolderId = folderId
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
        val first = kotlinx.coroutines.runBlocking {
            service.sync(card, "kb-test", "kb-folder-1", targetName = "测试知识库", folderName = "课程")
        }
        assertEquals(KnowledgeSyncState.RETRYABLE_FAILURE, first.state)
        assertNotNull(repo.list().single().remoteNoteId)
        val second = kotlinx.coroutines.runBlocking {
            service.sync(card, "kb-test", "kb-folder-1", targetName = "测试知识库", folderName = "课程")
        }
        assertEquals(KnowledgeSyncState.SYNCED, second.state)
        assertEquals(1, gateway.imports)
        assertEquals(1, gateway.associations)
        assertEquals("", gateway.importedFolderId)
        assertEquals("kb-folder-1", gateway.associatedFolderId)
        assertTrue(gateway.importedMarkdown!!.startsWith("# [BV_TEST] 测试"))
        assertEquals("测试知识库", repo.list().single().targetName)
        assertEquals("课程", repo.list().single().folderName)
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

    @Test
    fun editedContentRevisionDoesNotReuseEarlierSyncedRecord() {
        val root = kotlin.io.path.createTempDirectory("ima-sync-revision-test").toFile()
        val gateway = FakeGateway().also { it.failAssociation = false }
        val repo = FileKnowledgeSyncRepository(File(root, "sync.json"))
        val card = KnowledgeCard(
            cardId = KnowledgeCardId("bilibili", "BV_REVISION"), cardVersion = "v1", canonicalUrl = "https://example.test",
            title = "可编辑", ownerName = "", videoDurationSeconds = 1, timingAccuracy = TranscriptTimingAccuracy.EXACT,
            transcript = listOf(TranscriptSegment(0, 1000, "内容")), createdAtEpochMs = 1, updatedAtEpochMs = 1,
            markdown = "# 可编辑",
        )

        val service = ImaSyncService(gateway, repo)
        kotlinx.coroutines.runBlocking { service.sync(card, "kb-test", contentRevision = 0L) }
        val edited = kotlinx.coroutines.runBlocking { service.sync(card, "kb-test", contentRevision = 1L) }

        assertEquals(KnowledgeSyncState.SYNCED, edited.state)
        assertEquals(1, gateway.imports)
        assertEquals(1, gateway.appends)
        assertTrue(gateway.appendedMarkdown!!.contains("# [BV_REVISION] 可编辑"))
        assertEquals(2, repo.list().size)
        assertTrue(repo.list().any { it.key.contentRevision == 1L })
        root.deleteRecursively()
    }

    @Test
    fun editedContentRevisionAppendsExistingNoteBeforeAssociation() {
        val root = kotlin.io.path.createTempDirectory("ima-sync-existing-revision-test").toFile()
        val gateway = FakeGateway().also {
            it.failAssociation = false
            it.foundNoteId = "note-existing"
        }
        val repo = FileKnowledgeSyncRepository(File(root, "sync.json"))
        val card = card("BV_EXISTING_REVISION")

        val result = kotlinx.coroutines.runBlocking {
            ImaSyncService(gateway, repo).sync(card, "kb-test", contentRevision = 3L)
        }

        assertEquals(KnowledgeSyncState.SYNCED, result.state)
        assertEquals("note-existing", result.noteId)
        assertEquals("修订已追加并同步", result.message)
        assertEquals(1, gateway.appends)
        assertEquals(0, gateway.imports)
        assertEquals(1, gateway.associations)
        root.deleteRecursively()
    }

    @Test
    fun regeneratedCardVersionReusesTargetRemoteNoteAndAppendsSnapshot() {
        val root = kotlin.io.path.createTempDirectory("ima-sync-card-version-test").toFile()
        val gateway = FakeGateway().also { it.failAssociation = false }
        val repo = FileKnowledgeSyncRepository(File(root, "sync.json"))
        val firstCard = card("BV_CARD_VERSION")
        val service = ImaSyncService(gateway, repo)

        val first = kotlinx.coroutines.runBlocking { service.sync(firstCard, "kb-test") }
        val regenerated = firstCard.copy(cardVersion = "v2", title = "重新生成")
        val second = kotlinx.coroutines.runBlocking { service.sync(regenerated, "kb-test") }

        assertEquals(KnowledgeSyncState.SYNCED, first.state)
        assertEquals(KnowledgeSyncState.SYNCED, second.state)
        assertEquals("修订已追加并同步", second.message)
        assertEquals(1, gateway.imports)
        assertEquals(1, gateway.appends)
        assertEquals(2, gateway.associations)
        assertEquals(2, repo.list().size)
        root.deleteRecursively()
    }

    @Test
    fun failedRevisionAppendRetriesInsteadOfSkippingContentStage() {
        val root = kotlin.io.path.createTempDirectory("ima-sync-append-retry-test").toFile()
        val gateway = FakeGateway().also {
            it.failAssociation = false
            it.failNextAppend = true
            it.foundNoteId = "note-existing"
        }
        val repo = FileKnowledgeSyncRepository(File(root, "sync.json"))
        val card = card("BV_APPEND_RETRY")
        val service = ImaSyncService(gateway, repo)

        val first = kotlinx.coroutines.runBlocking {
            service.sync(card, "kb-test", contentRevision = 4L)
        }
        assertEquals(KnowledgeSyncState.RETRYABLE_FAILURE, first.state)
        assertEquals(null, repo.list().single().remoteNoteId)

        val second = kotlinx.coroutines.runBlocking {
            service.sync(card, "kb-test", contentRevision = 4L)
        }
        assertEquals(KnowledgeSyncState.SYNCED, second.state)
        assertEquals(2, gateway.appends)
        assertEquals(0, gateway.imports)
        assertEquals(1, gateway.associations)
        root.deleteRecursively()
    }

    @Test
    fun providerErrorIsReducedToSafeBatchMessage() {
        assertEquals("目标配额已用尽", safeImaErrorMessage("HTTP 400 code=200005: private quota-id"))
        assertEquals("凭据或权限无效", safeImaErrorMessage("HTTP 401 secret-token"))
        assertEquals("同步请求失败", safeImaErrorMessage("provider stack trace and note-id"))
    }

    private fun card(videoId: String) = KnowledgeCard(
        cardId = KnowledgeCardId("bilibili", videoId),
        cardVersion = "v1",
        canonicalUrl = "https://example.test",
        title = "可编辑",
        ownerName = "",
        videoDurationSeconds = 1,
        timingAccuracy = TranscriptTimingAccuracy.EXACT,
        transcript = listOf(TranscriptSegment(0, 1000, "内容")),
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
        markdown = "# 可编辑",
    )
}
