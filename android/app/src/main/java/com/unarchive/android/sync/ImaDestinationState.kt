package com.unarchive.android.sync

import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.KnowledgeSyncKey
import com.unarchive.android.card.KnowledgeSyncRecord
import com.unarchive.android.card.KnowledgeSyncState
import com.unarchive.android.card.KnowledgeSyncTarget
import com.unarchive.android.card.MarkdownCardRenderer

/** How image assets were represented in the remote note. */
enum class ImaImageMode {
    UNKNOWN,
    NOT_REQUESTED,
    EMBEDDED,
    PARTIAL,
    TEXT_ONLY_FALLBACK,
}

/**
 * Image delivery is deliberately separate from sync state. A note can be
 * synced successfully while its images were omitted because of a missing
 * local asset or the ima request-size guard.
 */
data class ImaImageDelivery(
    val mode: ImaImageMode = ImaImageMode.UNKNOWN,
    val requestedCount: Int = 0,
    val embeddedCount: Int = 0,
    val missingCount: Int = 0,
    val embeddedBytes: Long = 0L,
) {
    /** Sanitizes legacy/corrupt persisted counters before UI projection. */
    fun normalized(): ImaImageDelivery {
        val requested = requestedCount.coerceAtLeast(0)
        return copy(
            requestedCount = requested,
            embeddedCount = embeddedCount.coerceIn(0, requested),
            missingCount = missingCount.coerceIn(0, requested),
            embeddedBytes = embeddedBytes.coerceAtLeast(0L),
        )
    }

    init {
        require(requestedCount >= 0) { "requestedCount cannot be negative" }
        require(embeddedCount >= 0) { "embeddedCount cannot be negative" }
        require(missingCount >= 0) { "missingCount cannot be negative" }
        require(embeddedBytes >= 0) { "embeddedBytes cannot be negative" }
    }

    /** Short user-facing explanation; it never includes a path or remote ID. */
    val userMessage: String?
        get() = when (mode) {
            ImaImageMode.UNKNOWN, ImaImageMode.NOT_REQUESTED -> null
            ImaImageMode.EMBEDDED -> "图片已随笔记同步"
            ImaImageMode.PARTIAL -> "部分图片不可用，已保留文字内容"
            ImaImageMode.TEXT_ONLY_FALLBACK -> "图片过大，已降级为文字内容"
        }

    companion object {
        fun from(
            rendered: MarkdownCardRenderer.EmbeddedAssetsResult,
            textOnlyFallback: Boolean,
        ): ImaImageDelivery {
            val mode = when {
                rendered.requestedCount == 0 -> ImaImageMode.NOT_REQUESTED
                textOnlyFallback -> ImaImageMode.TEXT_ONLY_FALLBACK
                rendered.missingCount > 0 -> ImaImageMode.PARTIAL
                rendered.embeddedCount == rendered.requestedCount -> ImaImageMode.EMBEDDED
                else -> ImaImageMode.PARTIAL
            }
            return ImaImageDelivery(
                mode = mode,
                requestedCount = rendered.requestedCount,
                embeddedCount = rendered.embeddedCount,
                missingCount = rendered.missingCount,
                embeddedBytes = rendered.embeddedBytes,
            ).normalized()
        }
    }
}

/** The next explicit user operation for one external destination. */
enum class DestinationAction {
    NONE,
    SYNC,
    RETRY,
    REPAIR_CONFIGURATION,
    CHANGE_TARGET,
}

/**
 * UI-safe projection of one target-scoped sync record.
 *
 * Deliberately excludes target IDs, folder IDs, remote note IDs, and raw error
 * text. Callers that need those values keep the durable record at the service
 * boundary; Compose receives this projection only.
 */
data class DestinationTargetState(
    val targetType: String,
    val targetName: String,
    val folderName: String?,
    val state: KnowledgeSyncState,
    val statusText: String,
    val action: DestinationAction,
    val imageDelivery: ImaImageDelivery = ImaImageDelivery(),
    val hasRemoteCopy: Boolean = false,
    val updatedAtEpochMs: Long = 0L,
) {
    init {
        require(targetType.isNotBlank()) { "targetType cannot be blank" }
        require(targetName.isNotBlank()) { "targetName cannot be blank" }
    }
}

/** In-memory operation status for the destination surface. */
enum class DestinationOperationState { IDLE, SYNCING }

data class ImaDestinationState(
    val targets: List<DestinationTargetState> = emptyList(),
    val selectedTargetIndex: Int? = null,
    val operationState: DestinationOperationState = DestinationOperationState.IDLE,
    val announcement: String? = null,
) {
    init {
        require(selectedTargetIndex == null || selectedTargetIndex in targets.indices) {
            "selectedTargetIndex must point to a target"
        }
    }
}

/** Events consumed by [ImaDestinationReducer]. All target selection is index based. */
sealed interface ImaDestinationEvent {
    data class SelectTarget(val index: Int) : ImaDestinationEvent
    data class SyncStarted(
        val index: Int,
        val imageDelivery: ImaImageDelivery = ImaImageDelivery(),
    ) : ImaDestinationEvent
    data class SyncFinished(
        val index: Int,
        val target: DestinationTargetState,
    ) : ImaDestinationEvent
    data class SyncFailed(
        val index: Int,
        val target: DestinationTargetState,
    ) : ImaDestinationEvent
    data object ProcessRestored : ImaDestinationEvent
}

/** Pure state reducer for destination selection and a single foreground sync. */
object ImaDestinationReducer {
    fun reduce(state: ImaDestinationState, event: ImaDestinationEvent): ImaDestinationState = when (event) {
        is ImaDestinationEvent.SelectTarget -> {
            if (event.index !in state.targets.indices) state
            else state.copy(selectedTargetIndex = event.index, operationState = DestinationOperationState.IDLE, announcement = null)
        }

        is ImaDestinationEvent.SyncStarted -> {
            val current = state.targets.getOrNull(event.index)
            if (current == null) {
                state
            } else {
                val inProgressState = if (current.hasRemoteCopy) {
                    KnowledgeSyncState.ASSOCIATING
                } else {
                    KnowledgeSyncState.CREATING
                }
                val inProgress = current.copy(
                    state = inProgressState,
                    statusText = "正在同步到目标",
                    action = DestinationAction.NONE,
                    imageDelivery = event.imageDelivery,
                )
                state.copy(
                    targets = state.targets.updated(event.index, inProgress),
                    selectedTargetIndex = event.index,
                    operationState = DestinationOperationState.SYNCING,
                    announcement = "正在同步到目标",
                )
            }
        }

        is ImaDestinationEvent.SyncFinished -> {
            if (event.index !in state.targets.indices) state
            else state.copy(
                targets = state.targets.updated(event.index, event.target),
                selectedTargetIndex = event.index,
                operationState = DestinationOperationState.IDLE,
                announcement = event.target.statusText,
            )
        }

        is ImaDestinationEvent.SyncFailed -> {
            if (event.index !in state.targets.indices) state
            else state.copy(
                targets = state.targets.updated(event.index, event.target),
                selectedTargetIndex = event.index,
                operationState = DestinationOperationState.IDLE,
                announcement = event.target.statusText,
            )
        }

        ImaDestinationEvent.ProcessRestored -> state.copy(
            operationState = DestinationOperationState.IDLE,
            announcement = state.targets
                .firstOrNull { it.action == DestinationAction.RETRY }
                ?.statusText,
        )
    }

    private fun <T> List<T>.updated(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }
}

/**
 * Maps durable target-scoped records into the destination UI projection.
 * Filtering includes card identity and version, so changing a card version or
 * target cannot accidentally reuse an older remote copy.
 */
object DestinationTargetStateMapper {
    fun fromRecord(
        record: KnowledgeSyncRecord,
        imageDelivery: ImaImageDelivery = ImaImageDelivery(),
    ): DestinationTargetState = DestinationTargetState(
        targetType = record.key.targetType,
        targetName = displayTargetName(record.key.targetType, record.targetName),
        folderName = displayFolderName(record.key.folderId, record.folderName),
        state = record.state,
        statusText = destinationStatusText(record.state),
        action = destinationAction(record.state),
        imageDelivery = if (imageDelivery.mode == ImaImageMode.UNKNOWN && record.imageMode.isNotBlank()) {
            ImaImageDelivery(
                mode = runCatching { ImaImageMode.valueOf(record.imageMode) }.getOrDefault(ImaImageMode.UNKNOWN),
                requestedCount = record.imageRequestedCount,
                embeddedCount = record.imageEmbeddedCount,
                missingCount = record.imageMissingCount,
                embeddedBytes = record.imageEmbeddedBytes,
            ).normalized()
        } else imageDelivery,
        hasRemoteCopy = !record.remoteNoteId.isNullOrBlank(),
        updatedAtEpochMs = record.updatedAtEpochMs,
    )

    fun notSynced(target: KnowledgeSyncTarget): DestinationTargetState = DestinationTargetState(
        targetType = target.type,
        targetName = displayTargetName(target.type, target.name),
        folderName = displayFolderName(target.folderId, target.folderName),
        state = KnowledgeSyncState.NOT_SYNCED,
        statusText = destinationStatusText(KnowledgeSyncState.NOT_SYNCED),
        action = DestinationAction.SYNC,
    )

    fun forCard(
        cardId: KnowledgeCardId,
        cardVersion: String,
        records: Iterable<KnowledgeSyncRecord>,
        imageDeliveries: Map<KnowledgeSyncKey, ImaImageDelivery> = emptyMap(),
    ): List<DestinationTargetState> = records
        .asSequence()
        .filter { record ->
            record.key.cardId == cardId && record.key.cardVersion == cardVersion
        }
        .sortedWith(
            compareBy<KnowledgeSyncRecord> { displayTargetName(it.key.targetType, it.targetName) }
                .thenBy { displayFolderName(it.key.folderId, it.folderName).orEmpty() }
                .thenBy { it.key.targetType },
        )
        .map { record -> fromRecord(record, imageDeliveries[record.key] ?: ImaImageDelivery()) }
        .toList()

    private fun displayTargetName(type: String, name: String): String {
        val normalized = name.trim().replace(Regex("\\s+"), " ")
        return normalized.takeIf(String::isNotBlank)
            ?: if (type.equals("ima", ignoreCase = true)) "ima 知识库" else "同步目标"
    }

    private fun displayFolderName(folderId: String, name: String): String? {
        val normalized = name.trim().replace(Regex("\\s+"), " ")
        return normalized.takeIf(String::isNotBlank)
            ?: if (folderId.isBlank()) "根目录" else "已选文件夹"
    }
}

/** Recovery operation after a process restart or a previous failed attempt. */
enum class ImaRecoveryAction {
    START_SYNC,
    RESUME_ASSOCIATION,
    RETRY_SYNC,
    WAIT_FOR_CONFIGURATION,
    ALREADY_SYNCED,
    CHANGE_TARGET,
}

data class ImaRecoveryDecision(
    val action: ImaRecoveryAction,
    val state: KnowledgeSyncState,
    val statusText: String,
    val hasRemoteCopy: Boolean,
)

/**
 * Decides recovery using an exact target-scoped key. The result is safe to
 * expose to UI: it contains no target ID, folder ID, remote note ID, or raw
 * server error.
 */
object ImaDestinationRecovery {
    fun decide(
        cardId: KnowledgeCardId,
        cardVersion: String,
        target: KnowledgeSyncTarget,
        records: Iterable<KnowledgeSyncRecord>,
    ): ImaRecoveryDecision {
        val record = records.firstOrNull { candidate ->
            candidate.key.cardId == cardId &&
                candidate.key.cardVersion == cardVersion &&
                candidate.key.targetType == target.type &&
                candidate.key.targetId == target.id &&
                candidate.key.folderId == target.folderId
        }
        return decide(record)
    }

    fun decide(record: KnowledgeSyncRecord?): ImaRecoveryDecision {
        if (record == null) {
            return ImaRecoveryDecision(
                action = ImaRecoveryAction.START_SYNC,
                state = KnowledgeSyncState.NOT_SYNCED,
                statusText = destinationStatusText(KnowledgeSyncState.NOT_SYNCED),
                hasRemoteCopy = false,
            )
        }
        val hasRemoteCopy = !record.remoteNoteId.isNullOrBlank()
        val action = when (record.state) {
            KnowledgeSyncState.SYNCED -> if (record.kbAdded && hasRemoteCopy) {
                ImaRecoveryAction.ALREADY_SYNCED
            } else if (hasRemoteCopy) {
                ImaRecoveryAction.RESUME_ASSOCIATION
            } else {
                ImaRecoveryAction.RETRY_SYNC
            }

            KnowledgeSyncState.CREATED,
            KnowledgeSyncState.ASSOCIATING ->
                if (hasRemoteCopy) ImaRecoveryAction.RESUME_ASSOCIATION else ImaRecoveryAction.RETRY_SYNC

            KnowledgeSyncState.RETRYABLE_FAILURE ->
                if (hasRemoteCopy) ImaRecoveryAction.RESUME_ASSOCIATION else ImaRecoveryAction.RETRY_SYNC

            KnowledgeSyncState.BLOCKED -> ImaRecoveryAction.WAIT_FOR_CONFIGURATION
            KnowledgeSyncState.PERMANENT_FAILURE -> ImaRecoveryAction.CHANGE_TARGET
            KnowledgeSyncState.NOT_SYNCED,
            KnowledgeSyncState.CREATING ->
                if (hasRemoteCopy) ImaRecoveryAction.RESUME_ASSOCIATION else ImaRecoveryAction.RETRY_SYNC
        }
        return ImaRecoveryDecision(
            action = action,
            state = record.state,
            statusText = destinationStatusText(record.state),
            hasRemoteCopy = hasRemoteCopy,
        )
    }
}

fun destinationStatusText(state: KnowledgeSyncState): String = when (state) {
    KnowledgeSyncState.NOT_SYNCED -> "尚未同步到目标"
    KnowledgeSyncState.CREATING -> "同步未完成，可恢复"
    KnowledgeSyncState.CREATED -> "本地已保存，同步未完成，可恢复"
    KnowledgeSyncState.ASSOCIATING -> "本地已保存，关联未完成，可恢复"
    KnowledgeSyncState.SYNCED -> "已同步到目标"
    KnowledgeSyncState.RETRYABLE_FAILURE -> "本地已保存；同步失败，可重试"
    KnowledgeSyncState.PERMANENT_FAILURE -> "本地已保存；同步失败，可更换目标"
    KnowledgeSyncState.BLOCKED -> "目标配额或凭据阻断；本地笔记不受影响"
}

private fun destinationAction(state: KnowledgeSyncState): DestinationAction = when (state) {
    KnowledgeSyncState.NOT_SYNCED -> DestinationAction.SYNC
    KnowledgeSyncState.CREATING,
    KnowledgeSyncState.CREATED,
    KnowledgeSyncState.ASSOCIATING,
    KnowledgeSyncState.RETRYABLE_FAILURE -> DestinationAction.RETRY
    KnowledgeSyncState.SYNCED -> DestinationAction.NONE
    KnowledgeSyncState.PERMANENT_FAILURE -> DestinationAction.CHANGE_TARGET
    KnowledgeSyncState.BLOCKED -> DestinationAction.REPAIR_CONFIGURATION
}
