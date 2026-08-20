package com.unarchive.android.sync

import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeSyncKey
import com.unarchive.android.card.KnowledgeSyncRecord
import com.unarchive.android.card.KnowledgeSyncRepository
import com.unarchive.android.card.KnowledgeSyncState
import com.unarchive.android.card.CardAsset
import com.unarchive.android.card.MarkdownCardRenderer
import java.io.File
import kotlinx.coroutines.CancellationException

data class ImaSyncResult(val state: KnowledgeSyncState, val noteId: String? = null, val message: String = "", val imagesSynced: Boolean = false)

/** Single-card, two-step sync with durable partial-success recovery. */
class ImaSyncService(
    private val client: ImaGateway,
    private val states: KnowledgeSyncRepository,
    private val readAsset: (CardAsset) -> ByteArray? = { null },
) {
    suspend fun sync(card: KnowledgeCard, kbId: String, folderId: String = ""): ImaSyncResult {
        val key = KnowledgeSyncKey(card.cardId, card.cardVersion, "ima", kbId, folderId)
        val prior = states.find(key)
        if (prior?.state == KnowledgeSyncState.SYNCED && prior.kbAdded) return ImaSyncResult(KnowledgeSyncState.SYNCED, prior.remoteNoteId, "已同步，跳过重复写入", true)
        var noteId = prior?.remoteNoteId
        try {
            if (noteId == null) {
                val rendered = MarkdownCardRenderer.embedAssetsWithStats(card.markdown, card.assets, readAsset)
                val markdown = if (rendered.embeddedBytes <= MAX_EMBEDDED_BYTES) rendered.markdown
                else card.markdown.replace(IMAGE_LINK, "")
                noteId = client.findNote(card.cardId.videoId) ?: client.importDocument(markdown, folderId)
                states.save(KnowledgeSyncRecord(key, KnowledgeSyncState.CREATED, noteId, false, updatedAtEpochMs = System.currentTimeMillis()))
            }
            if (prior?.kbAdded == true && prior.key.folderId == folderId) return ImaSyncResult(KnowledgeSyncState.SYNCED, noteId, "已同步", true)
            states.save(KnowledgeSyncRecord(key, KnowledgeSyncState.ASSOCIATING, noteId, false, updatedAtEpochMs = System.currentTimeMillis()))
            return try {
                client.addToKnowledgeBase(noteId, "[${card.cardId.videoId}] ${card.title}", kbId, folderId)
                states.save(KnowledgeSyncRecord(key, KnowledgeSyncState.SYNCED, noteId, true, updatedAtEpochMs = System.currentTimeMillis()))
                ImaSyncResult(KnowledgeSyncState.SYNCED, noteId, "同步成功", true)
            } catch (e: ImaAlreadyAddedException) {
                states.save(KnowledgeSyncRecord(key, KnowledgeSyncState.SYNCED, noteId, true, updatedAtEpochMs = System.currentTimeMillis()))
                ImaSyncResult(KnowledgeSyncState.SYNCED, noteId, "已关联，按幂等成功", true)
            }
        } catch (e: ImaQuotaExceededException) {
            states.save(KnowledgeSyncRecord(key, KnowledgeSyncState.BLOCKED, noteId, false, e.message, System.currentTimeMillis()))
            return ImaSyncResult(KnowledgeSyncState.BLOCKED, noteId, e.message.orEmpty())
        } catch (e: ImaRateLimitException) {
            states.save(KnowledgeSyncRecord(key, KnowledgeSyncState.RETRYABLE_FAILURE, noteId, false, e.message, System.currentTimeMillis()))
            return ImaSyncResult(KnowledgeSyncState.RETRYABLE_FAILURE, noteId, e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            states.save(KnowledgeSyncRecord(key, KnowledgeSyncState.RETRYABLE_FAILURE, noteId, false, e.message, System.currentTimeMillis()))
            return ImaSyncResult(KnowledgeSyncState.RETRYABLE_FAILURE, noteId, e.message ?: "同步失败")
        }
    }

    companion object {
        private const val MAX_EMBEDDED_BYTES = 4L * 1024L * 1024L
        private val IMAGE_LINK = Regex("!\\[[^]]*]\\([^)]*\\)\\r?\\n?")
    }
}
