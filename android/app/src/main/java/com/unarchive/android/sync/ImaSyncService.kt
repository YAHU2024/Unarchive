package com.unarchive.android.sync

import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeSyncKey
import com.unarchive.android.card.KnowledgeSyncRecord
import com.unarchive.android.card.KnowledgeSyncRepository
import com.unarchive.android.card.KnowledgeSyncState
import com.unarchive.android.card.CardAsset
import com.unarchive.android.card.MarkdownCardRenderer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException

data class ImaSyncResult(val state: KnowledgeSyncState, val noteId: String? = null, val message: String = "", val imagesSynced: Boolean = false)

/** Single-card, two-step sync with durable partial-success recovery. */
class ImaSyncService(
    private val client: ImaGateway,
    private val states: KnowledgeSyncRepository,
    private val readAsset: (CardAsset) -> ByteArray? = { null },
) {
    suspend fun sync(
        card: KnowledgeCard,
        kbId: String,
        folderId: String = "",
        targetName: String = "",
        folderName: String = "",
        operationId: String = UUID.randomUUID().toString(),
    ): ImaSyncResult {
        val startedAt = System.currentTimeMillis()
        val key = KnowledgeSyncKey(card.cardId, card.cardVersion, "ima", kbId, folderId)
        val prior = states.find(key)
        ImaSyncLog.event(operationId, card, key, targetName, folderName, "同步", "开始", startedAt)
        if (prior?.state == KnowledgeSyncState.SYNCED && prior.kbAdded) {
            if (prior.targetName != targetName || prior.folderName != folderName) {
                save(key, KnowledgeSyncState.SYNCED, prior.remoteNoteId, true, targetName, folderName)
            }
            ImaSyncLog.event(
                operationId, card, key, targetName, folderName, "同步", "跳过", startedAt,
                noteId = prior.remoteNoteId, metadata = mapOf("reason" to "already_synced"),
            )
            return ImaSyncResult(KnowledgeSyncState.SYNCED, prior.remoteNoteId, "已同步，跳过重复写入", true)
        }
        var noteId = prior?.remoteNoteId
        try {
            if (noteId == null) {
                val rendered = MarkdownCardRenderer.embedAssetsWithStats(card.markdown, card.assets, readAsset)
                val markdown = if (rendered.embeddedBytes <= MAX_EMBEDDED_BYTES) rendered.markdown
                else card.markdown.replace(IMAGE_LINK, "")
                val fallback = rendered.embeddedBytes > MAX_EMBEDDED_BYTES
                ImaSyncLog.event(
                    operationId, card, key, targetName, folderName, "查找笔记", "开始", startedAt,
                    metadata = mapOf(
                        "embeddedImages" to rendered.embeddedCount,
                        "embeddedBytes" to rendered.embeddedBytes,
                        "missingImages" to rendered.missingCount,
                        "textOnlyFallback" to fallback,
                    ),
                )
                noteId = client.findNote(card.cardId.videoId)
                if (noteId == null) {
                    ImaSyncLog.event(
                        operationId, card, key, targetName, folderName, "导入笔记", "开始", startedAt,
                        metadata = mapOf("textOnlyFallback" to fallback),
                    )
                    noteId = client.importDocument(markdown, folderId)
                    ImaSyncLog.event(
                        operationId, card, key, targetName, folderName, "导入笔记", "成功", startedAt,
                        noteId = noteId, metadata = mapOf("textOnlyFallback" to fallback),
                    )
                } else {
                    ImaSyncLog.event(operationId, card, key, targetName, folderName, "查找笔记", "命中", startedAt, noteId)
                }
                save(key, KnowledgeSyncState.CREATED, noteId, false, targetName, folderName)
            }
            if (prior?.kbAdded == true && prior.key.folderId == folderId) {
                ImaSyncLog.event(
                    operationId, card, key, targetName, folderName, "同步", "跳过", startedAt,
                    noteId = noteId, metadata = mapOf("reason" to "already_associated"),
                )
                return ImaSyncResult(KnowledgeSyncState.SYNCED, noteId, "已同步", true)
            }
            save(key, KnowledgeSyncState.ASSOCIATING, noteId, false, targetName, folderName)
            ImaSyncLog.event(operationId, card, key, targetName, folderName, "关联知识库", "开始", startedAt, noteId)
            return try {
                client.addToKnowledgeBase(noteId, "[${card.cardId.videoId}] ${card.title}", kbId, folderId)
                save(key, KnowledgeSyncState.SYNCED, noteId, true, targetName, folderName)
                ImaSyncLog.event(operationId, card, key, targetName, folderName, "同步", "成功", startedAt, noteId)
                ImaSyncResult(KnowledgeSyncState.SYNCED, noteId, "同步成功", true)
            } catch (e: ImaAlreadyAddedException) {
                save(key, KnowledgeSyncState.SYNCED, noteId, true, targetName, folderName)
                ImaSyncLog.event(operationId, card, key, targetName, folderName, "关联知识库", "幂等成功", startedAt, noteId)
                ImaSyncResult(KnowledgeSyncState.SYNCED, noteId, "已关联，按幂等成功", true)
            }
        } catch (e: ImaQuotaExceededException) {
            save(key, KnowledgeSyncState.BLOCKED, noteId, false, targetName, folderName, e.message)
            failure(operationId, card, key, targetName, folderName, startedAt, noteId, KnowledgeSyncState.BLOCKED, e)
            return ImaSyncResult(KnowledgeSyncState.BLOCKED, noteId, e.message.orEmpty())
        } catch (e: ImaRateLimitException) {
            save(key, KnowledgeSyncState.RETRYABLE_FAILURE, noteId, false, targetName, folderName, e.message)
            failure(operationId, card, key, targetName, folderName, startedAt, noteId, KnowledgeSyncState.RETRYABLE_FAILURE, e)
            return ImaSyncResult(KnowledgeSyncState.RETRYABLE_FAILURE, noteId, e.message.orEmpty())
        } catch (e: CancellationException) {
            ImaSyncLog.event(operationId, card, key, targetName, folderName, "同步", "取消", startedAt, noteId)
            throw e
        } catch (e: Exception) {
            save(key, KnowledgeSyncState.RETRYABLE_FAILURE, noteId, false, targetName, folderName, e.message)
            failure(operationId, card, key, targetName, folderName, startedAt, noteId, KnowledgeSyncState.RETRYABLE_FAILURE, e)
            return ImaSyncResult(KnowledgeSyncState.RETRYABLE_FAILURE, noteId, e.message ?: "同步失败")
        }
    }

    private fun save(
        key: KnowledgeSyncKey,
        state: KnowledgeSyncState,
        noteId: String?,
        kbAdded: Boolean,
        targetName: String,
        folderName: String,
        error: String? = null,
    ) {
        states.save(KnowledgeSyncRecord(
            key, state, noteId, kbAdded, error, System.currentTimeMillis(), targetName, folderName,
        ))
    }

    private fun failure(
        operationId: String,
        card: KnowledgeCard,
        key: KnowledgeSyncKey,
        targetName: String,
        folderName: String,
        startedAt: Long,
        noteId: String?,
        state: KnowledgeSyncState,
        error: Throwable,
    ) {
        ImaSyncLog.event(
            operationId, card, key, targetName, folderName, "同步", state.name, startedAt, noteId,
            metadata = mapOf("error" to ImaSyncLog.safeError(error)), warning = true,
        )
    }

    companion object {
        private const val MAX_EMBEDDED_BYTES = 4L * 1024L * 1024L
        private val IMAGE_LINK = Regex("!\\[[^]]*]\\([^)]*\\)\\r?\\n?")
    }
}
