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
import com.unarchive.android.storage.ImaRequestBudget

data class ImaSyncResult(
    val state: KnowledgeSyncState,
    val noteId: String? = null,
    val message: String = "",
    val imagesSynced: Boolean = false,
    val imageDelivery: ImaImageDelivery = ImaImageDelivery(),
)

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
        contentRevision: Long = 0L,
        operationId: String = UUID.randomUUID().toString(),
    ): ImaSyncResult {
        val startedAt = System.currentTimeMillis()
        val key = KnowledgeSyncKey(card.cardId, card.cardVersion, "ima", kbId, folderId, contentRevision)
        val prior = states.find(key)
        ImaSyncLog.event(operationId, card, key, targetName, folderName, "同步", "开始", startedAt)
        if (prior?.state == KnowledgeSyncState.SYNCED && prior.kbAdded) {
            val priorImages = imageDeliveryFromRecord(prior)
            if (prior.targetName != targetName || prior.folderName != folderName) {
                save(key, KnowledgeSyncState.SYNCED, prior.remoteNoteId, true, targetName, folderName, imageDelivery = priorImages)
            }
            ImaSyncLog.event(
                operationId, card, key, targetName, folderName, "同步", "跳过", startedAt,
                noteId = prior.remoteNoteId, metadata = mapOf("reason" to "already_synced"),
            )
            return ImaSyncResult(
                KnowledgeSyncState.SYNCED,
                prior.remoteNoteId,
                "已同步，跳过重复写入",
                imagesSynced = priorImages.mode == ImaImageMode.EMBEDDED,
                imageDelivery = priorImages,
            )
        }
        var noteId = prior?.remoteNoteId
        var imageDelivery = prior?.let(::imageDeliveryFromRecord) ?: ImaImageDelivery()
        try {
            if (noteId == null) {
                val rendered = MarkdownCardRenderer.embedAssetsWithStats(card.markdown, card.assets, readAsset)
                val imageMarkdown = rendered.missingPaths.fold(rendered.markdown) { current, path ->
                    current.replace("![]($path)", "")
                }
                // A private relative path is not usable by ima. Remove any
                // unresolved non-data image marker from the remote payload.
                val textOnlyMarkdown = imageMarkdown.replace(RELATIVE_IMAGE_LINK, "")
                val exceedsBudget = !ImaRequestBudget.fitsMarkdown(imageMarkdown)
                val markdown = if (exceedsBudget) textOnlyMarkdown else imageMarkdown
                val fallback = exceedsBudget
                imageDelivery = ImaImageDelivery.from(rendered, textOnlyFallback = fallback)
                if (!ImaRequestBudget.fitsMarkdown(markdown)) {
                    throw java.io.IOException("ima 知识卡片请求体超过 5 MiB 安全上限")
                }
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
                    // The selected folder belongs to the knowledge-base tree and
                    // is only valid for add_knowledge. import_doc creates the
                    // note in ima's note root unless a separate note folder is
                    // explicitly configured.
                    noteId = client.importDocument(markdown)
                    ImaSyncLog.event(
                        operationId, card, key, targetName, folderName, "导入笔记", "成功", startedAt,
                        noteId = noteId, metadata = mapOf("textOnlyFallback" to fallback),
                    )
                } else {
                    ImaSyncLog.event(operationId, card, key, targetName, folderName, "查找笔记", "命中", startedAt, noteId)
                }
                save(key, KnowledgeSyncState.CREATED, noteId, false, targetName, folderName, imageDelivery = imageDelivery)
            }
            if (prior?.kbAdded == true && prior.key.folderId == folderId) {
                ImaSyncLog.event(
                    operationId, card, key, targetName, folderName, "同步", "跳过", startedAt,
                    noteId = noteId, metadata = mapOf("reason" to "already_associated"),
                )
                return ImaSyncResult(KnowledgeSyncState.SYNCED, noteId, "已同步", imageDelivery.mode == ImaImageMode.EMBEDDED, imageDelivery)
            }
            save(key, KnowledgeSyncState.ASSOCIATING, noteId, false, targetName, folderName, imageDelivery = imageDelivery)
            ImaSyncLog.event(operationId, card, key, targetName, folderName, "关联知识库", "开始", startedAt, noteId)
            return try {
                client.addToKnowledgeBase(noteId, "[${card.cardId.videoId}] ${card.title}", kbId, folderId)
                save(key, KnowledgeSyncState.SYNCED, noteId, true, targetName, folderName, imageDelivery = imageDelivery)
                ImaSyncLog.event(operationId, card, key, targetName, folderName, "同步", "成功", startedAt, noteId)
                ImaSyncResult(KnowledgeSyncState.SYNCED, noteId, "同步成功", imageDelivery.mode == ImaImageMode.EMBEDDED, imageDelivery)
            } catch (e: ImaAlreadyAddedException) {
                save(key, KnowledgeSyncState.SYNCED, noteId, true, targetName, folderName, imageDelivery = imageDelivery)
                ImaSyncLog.event(operationId, card, key, targetName, folderName, "关联知识库", "幂等成功", startedAt, noteId)
                ImaSyncResult(KnowledgeSyncState.SYNCED, noteId, "已关联，按幂等成功", imageDelivery.mode == ImaImageMode.EMBEDDED, imageDelivery)
            }
        } catch (e: ImaQuotaExceededException) {
            save(key, KnowledgeSyncState.BLOCKED, noteId, false, targetName, folderName, e.message, imageDelivery)
            failure(operationId, card, key, targetName, folderName, startedAt, noteId, KnowledgeSyncState.BLOCKED, e)
            return ImaSyncResult(KnowledgeSyncState.BLOCKED, noteId, "目标配置阻断", imageDelivery = imageDelivery)
        } catch (e: ImaCredentialException) {
            save(key, KnowledgeSyncState.BLOCKED, noteId, false, targetName, folderName, e.message, imageDelivery)
            failure(operationId, card, key, targetName, folderName, startedAt, noteId, KnowledgeSyncState.BLOCKED, e)
            return ImaSyncResult(KnowledgeSyncState.BLOCKED, noteId, "凭据或权限无效", imageDelivery = imageDelivery)
        } catch (e: ImaRateLimitException) {
            save(key, KnowledgeSyncState.RETRYABLE_FAILURE, noteId, false, targetName, folderName, e.message, imageDelivery)
            failure(operationId, card, key, targetName, folderName, startedAt, noteId, KnowledgeSyncState.RETRYABLE_FAILURE, e)
            return ImaSyncResult(KnowledgeSyncState.RETRYABLE_FAILURE, noteId, "同步请求失败", imageDelivery = imageDelivery)
        } catch (e: CancellationException) {
            ImaSyncLog.event(operationId, card, key, targetName, folderName, "同步", "取消", startedAt, noteId)
            throw e
        } catch (e: Exception) {
            save(key, KnowledgeSyncState.RETRYABLE_FAILURE, noteId, false, targetName, folderName, e.message, imageDelivery)
            failure(operationId, card, key, targetName, folderName, startedAt, noteId, KnowledgeSyncState.RETRYABLE_FAILURE, e)
            return ImaSyncResult(KnowledgeSyncState.RETRYABLE_FAILURE, noteId, "同步请求失败", imageDelivery = imageDelivery)
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
        imageDelivery: ImaImageDelivery = ImaImageDelivery(),
    ) {
        states.save(KnowledgeSyncRecord(
            key, state, noteId, kbAdded, error?.let(::safePersistedError), System.currentTimeMillis(), targetName, folderName,
            imageMode = imageDelivery.mode.name,
            imageRequestedCount = imageDelivery.requestedCount,
            imageEmbeddedCount = imageDelivery.embeddedCount,
            imageMissingCount = imageDelivery.missingCount,
            imageEmbeddedBytes = imageDelivery.embeddedBytes,
        ))
    }

    private fun safePersistedError(error: String): String = safeImaErrorMessage(error).orEmpty()

    private fun imageDeliveryFromRecord(record: KnowledgeSyncRecord): ImaImageDelivery = ImaImageDelivery(
        mode = runCatching { ImaImageMode.valueOf(record.imageMode) }.getOrDefault(ImaImageMode.UNKNOWN),
        requestedCount = record.imageRequestedCount,
        embeddedCount = record.imageEmbeddedCount,
        missingCount = record.imageMissingCount,
        embeddedBytes = record.imageEmbeddedBytes,
    ).normalized()

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
        private val RELATIVE_IMAGE_LINK = Regex("!\\[[^]]*]\\((?!data:)[^)]*\\)\\r?\\n?")
    }
}

/** Provider errors are reduced to fixed categories before durable batch/card state. */
fun safeImaErrorMessage(error: String?): String? {
    val text = error?.trim().orEmpty()
    if (text.isBlank()) return null
    return when {
        text.contains("配额", ignoreCase = true) || text.contains("200005") -> "目标配额已用尽"
        text.contains("频率", ignoreCase = true) || text.contains("200001") -> "请求频率受限"
        text.contains("200002") || text.contains("401") || text.contains("403") ||
            text.contains("凭据", ignoreCase = true) -> "凭据或权限无效"
        else -> "同步请求失败"
    }
}
