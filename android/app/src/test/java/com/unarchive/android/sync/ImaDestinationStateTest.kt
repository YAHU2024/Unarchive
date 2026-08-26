package com.unarchive.android.sync

import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.KnowledgeSyncKey
import com.unarchive.android.card.KnowledgeSyncRecord
import com.unarchive.android.card.KnowledgeSyncState
import com.unarchive.android.card.KnowledgeSyncTarget
import com.unarchive.android.card.MarkdownCardRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImaDestinationStateTest {
    private val cardId = KnowledgeCardId("bilibili", "BV_DESTINATION")

    @Test
    fun syncPrerequisiteRejectsBlankKnowledgeBaseBeforeTargetConstruction() {
        assertEquals(
            "请先选择 ima 知识库。",
            imaDestinationSyncUnavailableReason("client", "api-key", ""),
        )
        assertEquals(null, imaDestinationSyncUnavailableReason("client", "api-key", "kb-1"))
    }

    @Test
    fun targetValidatorsAcceptOnlyKnownIdsAndTreatBlankFolderAsRoot() {
        val bases = listOf(ImaKnowledgeBase("kb-1", "课程库"))
        val folders = listOf(ImaFolder("folder-1", "第一章"))

        assertEquals("kb-1", validatedImaKnowledgeBaseId(" kb-1 ", bases))
        assertEquals(null, validatedImaKnowledgeBaseId("kb-unknown", bases))
        assertEquals("", validatedImaFolderId("  ", folders))
        assertEquals("folder-1", validatedImaFolderId("folder-1", folders))
        assertEquals(null, validatedImaFolderId("folder-unknown", folders))
    }

    @Test
    fun folderTraversalBudgetRejectsCyclesDepthOverflowAndItemOverflow() {
        val budget = ImaFolderTraversalBudget(maximumDepth = 2, maximumItems = 2)

        assertTrue(budget.canDescend(0))
        assertTrue(budget.claim("folder-1"))
        assertFalse(budget.claim("folder-1"))
        assertTrue(budget.canDescend(1))
        assertTrue(budget.claim("folder-2"))
        assertFalse(budget.canDescend(1))
        assertFalse(budget.canDescend(2))
        assertFalse(budget.claim("folder-3"))
    }

    @Test
    fun imageDeliveryDistinguishesEmbeddedPartialAndBudgetFallback() {
        val embedded = ImaImageDelivery.from(
            rendered = MarkdownCardRenderer.EmbeddedAssetsResult(
                markdown = "# note", requestedCount = 2, embeddedCount = 2,
                embeddedBytes = 12, missingCount = 0,
            ),
            textOnlyFallback = false,
        )
        assertEquals(ImaImageMode.EMBEDDED, embedded.mode)
        assertEquals("图片已随笔记同步", embedded.userMessage)

        val partial = ImaImageDelivery.from(
            rendered = MarkdownCardRenderer.EmbeddedAssetsResult(
                markdown = "# note", requestedCount = 2, embeddedCount = 1,
                embeddedBytes = 4, missingCount = 1,
            ),
            textOnlyFallback = false,
        )
        assertEquals(ImaImageMode.PARTIAL, partial.mode)
        assertEquals("部分图片不可用，已保留文字内容", partial.userMessage)

        val fallback = ImaImageDelivery.from(
            rendered = MarkdownCardRenderer.EmbeddedAssetsResult(
                markdown = "# note", requestedCount = 2, embeddedCount = 2,
                embeddedBytes = 12, missingCount = 0,
            ),
            textOnlyFallback = true,
        )
        assertEquals(ImaImageMode.TEXT_ONLY_FALLBACK, fallback.mode)
        assertEquals("图片过大，已降级为文字内容", fallback.userMessage)
    }

    @Test
    fun mapperKeepsTargetAndVersionIsolationAndDoesNotExposeInternalIds() {
        val keyA = key(cardVersion = "v1", targetId = "kb-secret-a", folderId = "folder-secret-a")
        val keyB = key(cardVersion = "v1", targetId = "kb-secret-b", folderId = "folder-secret-b")
        val otherVersion = key(cardVersion = "v2", targetId = "kb-secret-a", folderId = "folder-secret-a")
        val records = listOf(
            KnowledgeSyncRecord(
                keyA, KnowledgeSyncState.SYNCED, remoteNoteId = "note-secret-a", kbAdded = true,
                updatedAtEpochMs = 10, targetName = "课程库", folderName = "第一章",
            ),
            KnowledgeSyncRecord(
                keyB, KnowledgeSyncState.RETRYABLE_FAILURE, remoteNoteId = "note-secret-b", kbAdded = false,
                lastError = "server response includes note-secret-b", updatedAtEpochMs = 11,
            ),
            KnowledgeSyncRecord(otherVersion, KnowledgeSyncState.SYNCED, remoteNoteId = "note-v2", kbAdded = true,
                updatedAtEpochMs = 12, targetName = "旧版本", folderName = "旧目录"),
        )

        val states = DestinationTargetStateMapper.forCard(cardId, "v1", records)

        assertEquals(2, states.size)
        assertTrue(states.any { it.state == KnowledgeSyncState.SYNCED && it.targetName == "课程库" })
        val failed = states.single { it.state == KnowledgeSyncState.RETRYABLE_FAILURE }
        assertEquals("ima 知识库", failed.targetName)
        assertEquals("已选文件夹", failed.folderName)
        assertFalse(states.toString().contains("kb-secret"))
        assertFalse(states.toString().contains("folder-secret"))
        assertFalse(states.toString().contains("note-secret"))
        assertFalse(states.toString().contains("server response"))
    }

    @Test
    fun recoveryMatchesCompleteTargetKeyAndNeverReusesAnotherTarget() {
        val firstTarget = KnowledgeSyncTarget("ima", "kb-a", "课程库", "folder-a", "第一章")
        val secondTarget = KnowledgeSyncTarget("ima", "kb-b", "另一个库", "folder-b", "第二章")
        val records = listOf(
            KnowledgeSyncRecord(
                key(targetId = "kb-a", folderId = "folder-a"),
                state = KnowledgeSyncState.BLOCKED,
                remoteNoteId = "note-a",
                lastError = "配额耗尽",
                updatedAtEpochMs = 1,
            ),
        )

        val blocked = ImaDestinationRecovery.decide(cardId, "v1", firstTarget, records)
        assertEquals(ImaRecoveryAction.WAIT_FOR_CONFIGURATION, blocked.action)
        assertTrue(blocked.hasRemoteCopy)
        assertEquals(KnowledgeSyncState.BLOCKED, blocked.state)
        assertEquals("目标配额或凭据阻断；本地笔记不受影响", blocked.statusText)

        val switched = ImaDestinationRecovery.decide(cardId, "v1", secondTarget, records)
        assertEquals(ImaRecoveryAction.START_SYNC, switched.action)
        assertEquals(KnowledgeSyncState.NOT_SYNCED, switched.state)
        assertFalse(switched.hasRemoteCopy)
    }

    @Test
    fun processRecoveryResumesAssociationWhenNoteAlreadyExists() {
        val record = KnowledgeSyncRecord(
            key(), state = KnowledgeSyncState.ASSOCIATING, remoteNoteId = "note-existing", kbAdded = false,
            updatedAtEpochMs = 20,
        )

        val decision = ImaDestinationRecovery.decide(record)

        assertEquals(ImaRecoveryAction.RESUME_ASSOCIATION, decision.action)
        assertTrue(decision.hasRemoteCopy)
        assertEquals("本地已保存，关联未完成，可恢复", decision.statusText)
        assertFalse(decision.toString().contains("note-existing"))
    }

    @Test
    fun reducerSeparatesSelectionFromOperationAndRejectsInvalidIndex() {
        val first = DestinationTargetStateMapper.notSynced(
            KnowledgeSyncTarget("ima", "kb-a", "课程库", "", ""),
        )
        val second = DestinationTargetStateMapper.notSynced(
            KnowledgeSyncTarget("ima", "kb-b", "另一个库", "", ""),
        )
        val initial = ImaDestinationState(targets = listOf(first, second))

        val selected = ImaDestinationReducer.reduce(initial, ImaDestinationEvent.SelectTarget(1))
        assertEquals(1, selected.selectedTargetIndex)
        assertEquals(DestinationOperationState.IDLE, selected.operationState)

        val running = ImaDestinationReducer.reduce(
            selected,
            ImaDestinationEvent.SyncStarted(1, ImaImageDelivery(ImaImageMode.TEXT_ONLY_FALLBACK, 1, 0, 0)),
        )
        assertEquals(DestinationOperationState.SYNCING, running.operationState)
        assertEquals(KnowledgeSyncState.CREATING, running.targets[1].state)
        assertEquals("正在同步到目标", running.announcement)

        val completedTarget = running.targets[1].copy(
            state = KnowledgeSyncState.SYNCED,
            statusText = destinationStatusText(KnowledgeSyncState.SYNCED),
            action = DestinationAction.NONE,
            hasRemoteCopy = true,
        )
        val completed = ImaDestinationReducer.reduce(
            running,
            ImaDestinationEvent.SyncFinished(1, completedTarget),
        )
        assertEquals(DestinationOperationState.IDLE, completed.operationState)
        assertEquals(KnowledgeSyncState.SYNCED, completed.targets[1].state)
        assertEquals("已同步到目标", completed.announcement)

        assertEquals(completed, ImaDestinationReducer.reduce(completed, ImaDestinationEvent.SelectTarget(99)))
    }

    private fun key(
        cardVersion: String = "v1",
        targetId: String = "kb-a",
        folderId: String = "folder-a",
    ) = KnowledgeSyncKey(cardId, cardVersion, "ima", targetId, folderId)
}
