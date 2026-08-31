package com.unarchive.android.ui.state

import com.unarchive.android.UnarchiveViewModel
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.GraphRelationItem
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.SingleCardRecoveryState
import com.unarchive.android.card.KnowledgeSyncState
import com.unarchive.android.card.toNoteDocument
import com.unarchive.android.result.StoredVideoResult
import com.unarchive.android.result.VideoResultKey
import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.storage.AppStorageSnapshot
import com.unarchive.android.sync.DestinationAction
import com.unarchive.android.sync.DestinationTargetStateMapper
import com.unarchive.android.sync.ImaFolder
import com.unarchive.android.sync.ImaKnowledgeBase

enum class GraphRelationOperationState { IDLE, SAVING, SAVED, FAILED }

enum class DestinationOperationState { IDLE, RUNNING, SUCCEEDED, FAILED }

/**
 * Stable, product-facing stages for the Create flow.  The underlying
 * pipeline may add or rename implementation stages without forcing a Compose
 * screen (or a future presentation) to understand those details.
 */
internal enum class CreateProcessingStage {
    IDLE,
    VALIDATING,
    DOWNLOADING,
    TRANSCRIBING,
    SAVING_CARD,
    GENERATING_AI,
    CAPTURING_SCREENSHOTS,
    COMPLETED,
    FAILED,
    CANCELLED,
    RECOVERABLE,
}

internal val CreateProcessingStage.displayLabel: String
    get() = when (this) {
        CreateProcessingStage.IDLE -> "等待开始"
        CreateProcessingStage.VALIDATING -> "准备中"
        CreateProcessingStage.DOWNLOADING -> "下载音频"
        CreateProcessingStage.TRANSCRIBING -> "转写中"
        CreateProcessingStage.SAVING_CARD -> "保存知识卡片"
        CreateProcessingStage.GENERATING_AI -> "AI 整理中"
        CreateProcessingStage.CAPTURING_SCREENSHOTS -> "获取章节截图"
        CreateProcessingStage.COMPLETED -> "已完成"
        CreateProcessingStage.FAILED -> "处理失败"
        CreateProcessingStage.CANCELLED -> "已取消"
        CreateProcessingStage.RECOVERABLE -> "可以恢复"
    }

internal data class CreateProcessingUiState(
    val stage: CreateProcessingStage = CreateProcessingStage.IDLE,
    val progress: Float = 0f,
    val checkpointSegmentCount: Int = 0,
    val isCancellable: Boolean = false,
)

internal enum class DestinationTargetLoadState { IDLE, LOADING, LOADED, EMPTY, FAILED }

internal data class DestinationKnowledgeBaseOption(
    val id: String,
    val name: String,
)

internal data class DestinationFolderOption(
    val id: String,
    val name: String,
    val displayPath: String,
    val depth: Int,
)

/** Stable internal retry handle; values are never rendered or announced. */
internal data class DestinationTargetRef(
    val targetType: String,
    val targetId: String,
    val folderId: String,
    val contentRevision: Long,
)

internal data class DestinationTargetUiState(
    val targetLabel: String,
    val folderLabel: String,
    val state: KnowledgeSyncState,
    val stateLabel: String,
    val detail: String? = null,
    val canRetry: Boolean = false,
    val isCurrent: Boolean = false,
    val retryRef: DestinationTargetRef? = null,
)

internal data class DestinationUiState(
    val card: KnowledgeCard? = null,
    val cardError: String? = null,
    val localSaved: Boolean = false,
    val exportState: DestinationOperationState = DestinationOperationState.IDLE,
    val exportMessage: String = "",
    val exportEmbeddedAssetCount: Int = 0,
    val exportMissingAssetCount: Int = 0,
    val imaConfigured: Boolean = false,
    val imaTargetSelected: Boolean = false,
    val imaSyncing: Boolean = false,
    val imaSyncEnabled: Boolean = false,
    val targetRefreshEnabled: Boolean = false,
    val currentTargetLabel: String = "",
    val imaStateWarning: String? = null,
    val knowledgeBaseLoadState: DestinationTargetLoadState = DestinationTargetLoadState.IDLE,
    val folderLoadState: DestinationTargetLoadState = DestinationTargetLoadState.IDLE,
    val knowledgeBaseOptions: List<DestinationKnowledgeBaseOption> = emptyList(),
    val folderOptions: List<DestinationFolderOption> = emptyList(),
    val selectedKnowledgeBaseName: String = "",
    val selectedFolderPath: String = "",
    val targetRecords: List<DestinationTargetUiState> = emptyList(),
)

internal data class UnarchiveUiState(
    val create: CreateUiState,
    val notes: NotesUiState,
    val graph: GraphUiState,
    val me: MeUiState,
    val destinations: DestinationUiState = DestinationUiState(),
    val results: ResultsUiState = ResultsUiState(),
    val settings: SettingsUiState = SettingsUiState(),
)

internal data class ResultsUiState(
    val results: List<StoredVideoResult> = emptyList(),
    val selected: StoredVideoResult? = null,
    val isBusy: Boolean = false,
    val statusMessage: String = "",
)

internal data class SettingsUiState(
    val availableEngines: List<AsrEngineKind> = emptyList(),
    val selectedEngine: AsrEngineKind = AsrEngineKind.SILICONFLOW_CLOUD,
    val siliconFlowModels: List<String> = emptyList(),
    val selectedSiliconFlowModel: String = "",
    val siliconFlowModelStatus: String = "",
    val selectedThreads: Int? = null,
    val selectedEnableVad: Boolean = false,
    val selectedVadMaxSeconds: Int = 10,
    val thinkingEnabled: Boolean = false,
    val isBusy: Boolean = false,
    val storageLoading: Boolean = false,
    val storageSnapshot: AppStorageSnapshot? = null,
    val storageError: String = "",
    val cacheClearing: Boolean = false,
    val cacheClearStatus: String = "",
    val effectiveCacheBudgetBytes: Long = 0L,
    val configuredCacheBudgetBytes: Long = 0L,
    val customCacheBudgetInput: String = "",
    val cacheBudgetStatus: String = "",
)

internal data class CreateUiState(
    val videoReference: String,
    val isProcessing: Boolean,
    val statusMessage: String,
    val hasRecovery: Boolean,
    val storedResultCount: Int,
    val latestNoteDocument: NoteDocument? = null,
    val recoveries: List<RecoveryUiItem> = emptyList(),
    val processing: CreateProcessingUiState = CreateProcessingUiState(),
)

internal data class RecoveryUiItem(
    val operationId: String,
    val cardId: String,
    val cardVersion: String,
    val title: String,
    val stageLabel: String,
    val state: SingleCardRecoveryState,
    val canResume: Boolean,
    val errorMessage: String? = null,
)

internal data class NotesUiState(
    val noteCount: Int,
    val materialCount: Int,
    val noteTitles: List<String>,
    val noteCards: List<KnowledgeCard> = emptyList(),
    val noteDocuments: List<NoteDocument> = emptyList(),
    val noteDocumentVersions: List<NoteDocument> = emptyList(),
    val libraryItems: List<NotesLibraryItem> = emptyList(),
    val canGenerateDraft: Boolean = true,
    val coverRetryInProgress: Boolean = false,
    val coverRetryKeys: Set<NoteVersionKey> = emptySet(),
)

internal data class GraphUiState(
    val noteCount: Int,
    val relationCount: Int = 0,
    val noteDocuments: List<NoteDocument> = emptyList(),
    val selectedCardId: String? = null,
    val selectedTitle: String? = null,
    val outgoing: List<GraphRelationItem> = emptyList(),
    val incoming: List<GraphRelationItem> = emptyList(),
    val isAddingRelation: Boolean = false,
    val targetCardIdInput: String = "",
    val relationLabelInput: String = "",
    val relationDescriptionInput: String = "",
    val operationState: GraphRelationOperationState = GraphRelationOperationState.IDLE,
    val errorMessage: String? = null,
)

internal data class MeUiState(
    val developerToolsAvailable: Boolean = true,
)

internal sealed interface CreateEvent {
    data class VideoReferenceChanged(val value: String) : CreateEvent
    data object GenerateNoteDraft : CreateEvent
    data object CancelProcessing : CreateEvent
    data object ResumeBatch : CreateEvent
    data class ResumeSingleCard(val operationId: String) : CreateEvent
}

internal sealed interface NotesEvent {
    data object Refresh : NotesEvent
    data class GenerateDraft(val platform: String, val videoId: String) : NotesEvent
    data class RetryCover(val platform: String, val videoId: String, val cardVersion: String) : NotesEvent
}

internal sealed interface DestinationEvent {
    data class OpenCard(val cardId: String, val cardVersion: String? = null) : DestinationEvent
    data object ExportMarkdown : DestinationEvent
    data object SyncIma : DestinationEvent
    data class RetryIma(val ref: DestinationTargetRef) : DestinationEvent
    data object RefreshTargets : DestinationEvent
    data class SelectKnowledgeBase(val knowledgeBaseId: String) : DestinationEvent
    data class SelectFolder(val folderId: String) : DestinationEvent
    data object OpenSecuritySettings : DestinationEvent
}

internal sealed interface GraphEvent {
    data class SelectNote(val cardId: String) : GraphEvent
    data object StartAddRelation : GraphEvent
    data object CancelAddRelation : GraphEvent
    data class TargetChanged(val value: String) : GraphEvent
    data class LabelChanged(val value: String) : GraphEvent
    data class DescriptionChanged(val value: String) : GraphEvent
    data object CreateUserLink : GraphEvent
    data class RemoveRelation(val relationId: String) : GraphEvent
    data object ClearStatus : GraphEvent
}

internal sealed interface MeEvent {
    data object OpenDeveloperOptions : MeEvent
}

internal sealed interface ResultsEvent {
    data class Select(val key: VideoResultKey) : ResultsEvent
    data object CopyTranscript : ResultsEvent
    data object Share : ResultsEvent
    data object ExportCard : ResultsEvent
    data object ExportAudio : ResultsEvent
    data object GenerateCard : ResultsEvent
    data object Rerun : ResultsEvent
    data object RerunFresh : ResultsEvent
}

internal sealed interface SettingsEvent {
    data class SelectEngine(val engine: AsrEngineKind) : SettingsEvent
    data class SelectSiliconFlowModel(val model: String) : SettingsEvent
    data class AddSiliconFlowModel(val model: String) : SettingsEvent
    data class SetThreads(val threads: Int?) : SettingsEvent
    data class SetEnableVad(val enabled: Boolean) : SettingsEvent
    data class SetVadMaxSeconds(val seconds: Int) : SettingsEvent
    data class SetThinkingEnabled(val enabled: Boolean) : SettingsEvent
    data object RefreshStorage : SettingsEvent
    data object OpenSystemStorageSettings : SettingsEvent
    data object ClearRebuildableCache : SettingsEvent
    data object UseAutomaticCacheBudget : SettingsEvent
    data class UsePresetCacheBudget(val gibibytes: Long) : SettingsEvent
    data class CustomCacheBudgetChanged(val value: String) : SettingsEvent
    data object SaveCustomCacheBudget : SettingsEvent
}

internal fun UnarchiveViewModel.toUnarchiveUiState(): UnarchiveUiState {
    val latestDocuments = noteDocuments
        .groupBy(NoteDocument::cardId)
        .values
        .mapNotNull { versions -> versions.maxByOrNull(NoteDocument::updatedAtEpochMs) }
    val documentedIds = latestDocuments.mapTo(mutableSetOf(), NoteDocument::cardId)
    val effectiveDocuments = (
        latestDocuments + knowledgeCards
            .filterNot { it.cardId in documentedIds }
            .map { it.toNoteDocument() }
        ).sortedByDescending(NoteDocument::updatedAtEpochMs)
    val syncRecords = knowledgeSyncRecordsForCards(knowledgeCardVersions)
    val thumbnailCandidates = knowledgeCardVersions.associate { card ->
        val coverAssetId = card.cover.assetId
        val orderedAssets = buildList {
            card.assets.firstOrNull {
                it.kind == com.unarchive.android.card.CardAssetKind.COVER && it.assetId == coverAssetId
            }?.let(::add)
            card.assets.firstOrNull {
                it.kind == com.unarchive.android.card.CardAssetKind.CHAPTER_SCREENSHOT
            }?.let(::add)
        }
        val candidates = orderedAssets.mapNotNull { asset ->
            val path = knowledgeCardRepository.assetFile(card, asset)
                ?.takeIf { it.isFile && it.length() == asset.byteCount }
                ?.absolutePath
                ?: return@mapNotNull null
            NotesThumbnailCandidate(
                path = path,
                kind = if (asset.kind == com.unarchive.android.card.CardAssetKind.COVER) {
                    NotesThumbnailKind.COVER
                } else {
                    NotesThumbnailKind.CHAPTER_SCREENSHOT
                },
            )
        }
        (card.cardId to card.cardVersion) to candidates
    }
    val libraryItems = buildNotesLibraryItems(
        noteDocuments = effectiveDocuments,
        noteCards = knowledgeCardVersions,
        storedResults = storedResults,
        syncRecords = syncRecords,
        thumbnailCandidates = thumbnailCandidates,
    )
    return UnarchiveUiState(
        create = CreateUiState(
            videoReference = videoReference,
            isProcessing = runningJob?.isActive == true || generateJob?.isActive == true,
            statusMessage = status,
            hasRecovery = batchRecoveryAvailable,
            storedResultCount = storedResults.size,
            latestNoteDocument = selectedKnowledgeCard?.let { selected ->
                noteDocuments.firstOrNull {
                    it.cardId == selected.cardId &&
                        it.generation.cardVersion == selected.cardVersion
                }
            } ?: noteDocuments.firstOrNull(),
            recoveries = singleCardRecoveries.mapNotNull { record ->
                if (record.state !in setOf(
                        SingleCardRecoveryState.RECOVERABLE,
                        SingleCardRecoveryState.RECOVERING,
                        SingleCardRecoveryState.FAILED,
                    )) return@mapNotNull null
                val stored = storedResults.firstOrNull { it.key == record.resultKey }
                val card = knowledgeCardVersions.firstOrNull {
                    it.cardId == record.cardId && it.cardVersion == record.cardVersion
                }
                if (stored == null || card == null) return@mapNotNull null
                RecoveryUiItem(
                    operationId = record.operationId,
                    cardId = record.cardId.value,
                    cardVersion = record.cardVersion,
                    title = card.title,
                    stageLabel = when (record.stage) {
                        com.unarchive.android.card.SingleCardRecoveryStage.BASE -> "基础卡片"
                        com.unarchive.android.card.SingleCardRecoveryStage.ANALYSIS -> "AI 分析"
                        com.unarchive.android.card.SingleCardRecoveryStage.SCREENSHOTS -> "章节截图"
                        com.unarchive.android.card.SingleCardRecoveryStage.PUBLISH -> "发布笔记"
                    },
                    state = record.state,
                    canResume = record.state == SingleCardRecoveryState.RECOVERABLE ||
                        record.state == SingleCardRecoveryState.FAILED,
                    errorMessage = record.lastError,
                )
            },
            processing = CreateProcessingUiState(
                stage = createProcessingStage,
                progress = progress.coerceIn(0f, 1f),
                checkpointSegmentCount = checkpointSegmentCount,
                isCancellable = runningJob?.isActive == true || generateJob?.isActive == true,
            ),
        ),
        notes = NotesUiState(
            noteCount = libraryItems.count { it.kind == NotesLibraryItemKind.SAVED_NOTE },
            materialCount = libraryItems.count { it.kind == NotesLibraryItemKind.MATERIAL },
            noteTitles = effectiveDocuments.map { it.title }.take(6),
            noteCards = knowledgeCardVersions,
            noteDocuments = effectiveDocuments,
            noteDocumentVersions = noteDocuments,
            libraryItems = libraryItems,
            canGenerateDraft = runningJob == null && generateJob == null && exportJob == null,
            coverRetryInProgress = coverRetryInProgress,
            coverRetryKeys = coverRetryKeys,
        ),
        graph = run {
            val documents = effectiveDocuments.ifEmpty { knowledgeCards.map { it.toNoteDocument() } }
            val selectedCardId = graphSelectedCardId
                ?.takeIf { id -> documents.any { it.cardId.value == id } }
                ?: documents.firstOrNull()?.cardId?.value
            val snapshot = selectedCardId?.let { id ->
                noteRelationRepository.snapshot(
                    documents,
                    com.unarchive.android.card.KnowledgeCardId(
                        platform = id.substringBefore(":"),
                        videoId = id.substringAfter(":", missingDelimiterValue = ""),
                    ),
                )
            }
            GraphUiState(
                noteCount = documents.size,
                relationCount = snapshot?.all?.size ?: 0,
                noteDocuments = documents,
                selectedCardId = selectedCardId,
                selectedTitle = documents.firstOrNull { it.cardId.value == selectedCardId }?.title,
                outgoing = snapshot?.outgoing.orEmpty(),
                incoming = snapshot?.incoming.orEmpty(),
                isAddingRelation = graphIsAddingRelation,
                targetCardIdInput = graphTargetCardIdInput,
                relationLabelInput = graphRelationLabelInput,
                relationDescriptionInput = graphRelationDescriptionInput,
                operationState = graphRelationOperationState,
                errorMessage = graphRelationError,
            )
        },
        me = MeUiState(),
        destinations = run {
            val card = destinationCardForUi()
            val targetRecords = card?.let(::knowledgeSyncRecords).orEmpty()
            val targetStates = targetRecords.map(DestinationTargetStateMapper::fromRecord)
            val knowledgeBaseOptions = destinationKnowledgeBaseOptions(imaKnowledgeBases)
            val folderOptions = if (imaFolderKnowledgeBaseId == imaKnowledgeBaseId) {
                destinationFolderOptions(imaFolders)
            } else {
                emptyList()
            }
            val selectedKnowledgeBaseName = knowledgeBaseOptions
                .firstOrNull { it.id == imaKnowledgeBaseId }
                ?.name
                .orEmpty()
            val selectedFolderPath = if (imaFolderId.isBlank()) {
                "根目录"
            } else {
                folderOptions.firstOrNull { it.id == imaFolderId }?.displayPath.orEmpty()
            }
            DestinationUiState(
                card = card,
                cardError = destinationCardError,
                localSaved = card != null,
                exportState = when {
                    exportJob?.isActive == true -> DestinationOperationState.RUNNING
                    destinationExportFailed -> DestinationOperationState.FAILED
                    destinationExportSucceeded -> DestinationOperationState.SUCCEEDED
                    else -> DestinationOperationState.IDLE
                },
                exportMessage = destinationExportMessage,
                exportEmbeddedAssetCount = destinationExportEmbeddedAssetCount,
                exportMissingAssetCount = destinationExportMissingAssetCount,
                imaConfigured = imaCredentialsConfigured,
                imaTargetSelected = imaKnowledgeBaseId.isNotBlank(),
                imaSyncing = imaSyncing,
                imaSyncEnabled = imaCredentialsConfigured && imaKnowledgeBaseId.isNotBlank() &&
                    imaKnowledgeBaseLoadState != DestinationTargetLoadState.LOADING &&
                    imaFolderLoadState != DestinationTargetLoadState.LOADING &&
                    !imaSyncing && generateJob == null && runningJob == null && exportJob == null,
                targetRefreshEnabled = imaCredentialsConfigured && !imaTargetsLoading && !imaSyncing,
                currentTargetLabel = currentImaTargetDisplayName,
                imaStateWarning = imaSyncStateWarning,
                knowledgeBaseLoadState = imaKnowledgeBaseLoadState,
                folderLoadState = imaFolderLoadState,
                knowledgeBaseOptions = knowledgeBaseOptions,
                folderOptions = folderOptions,
                selectedKnowledgeBaseName = selectedKnowledgeBaseName,
                selectedFolderPath = selectedFolderPath,
                targetRecords = targetStates.mapIndexed { index, target ->
                    val record = targetRecords[index]
                    DestinationTargetUiState(
                        targetLabel = target.targetName,
                        folderLabel = target.folderName ?: "根目录",
                        state = target.state,
                        stateLabel = target.statusText,
                        detail = target.imageDelivery.userMessage,
                        canRetry = !imaSyncing && (target.action == DestinationAction.RETRY ||
                            target.action == DestinationAction.REPAIR_CONFIGURATION),
                        isCurrent = false,
                        retryRef = DestinationTargetRef(
                            targetType = record.key.targetType,
                            targetId = record.key.targetId,
                            folderId = record.key.folderId,
                            contentRevision = record.key.contentRevision,
                        ),
                    )
                },
            )
        },
        results = ResultsUiState(
            results = storedResults,
            selected = selectedStoredResult,
            isBusy = runningJob != null || generateJob != null || exportJob != null,
            statusMessage = status,
        ),
        settings = SettingsUiState(
            availableEngines = AsrEngineKind.entries.filter { it.selectable && it.available },
            selectedEngine = selectedEngine,
            siliconFlowModels = siliconFlowModels,
            selectedSiliconFlowModel = selectedSiliconFlowModel,
            siliconFlowModelStatus = siliconFlowModelStatus,
            selectedThreads = selectedThreads,
            selectedEnableVad = selectedEnableVad,
            selectedVadMaxSeconds = selectedVadMaxSeconds,
            thinkingEnabled = thinkingEnabled,
            isBusy = runningJob != null || generateJob != null || exportJob != null || imaSyncing,
            storageLoading = storageLoading,
            storageSnapshot = storageSnapshot,
            storageError = storageError,
            cacheClearing = cacheClearing,
            cacheClearStatus = cacheClearStatus,
            effectiveCacheBudgetBytes = effectiveCacheBudgetBytes,
            configuredCacheBudgetBytes = configuredCacheBudgetBytes,
            customCacheBudgetInput = customCacheBudgetInput,
            cacheBudgetStatus = cacheBudgetStatus,
        ),
    )
}

internal fun destinationKnowledgeBaseOptions(
    bases: List<ImaKnowledgeBase>,
): List<DestinationKnowledgeBaseOption> = bases
    .filter { it.id.isNotBlank() }
    .distinctBy(ImaKnowledgeBase::id)
    .map { base ->
        DestinationKnowledgeBaseOption(
            id = base.id,
            name = base.name.ifBlank { "未命名知识库" },
        )
    }

internal fun destinationFolderOptions(
    folders: List<ImaFolder>,
): List<DestinationFolderOption> = folders
    .filter { it.id.isNotBlank() }
    .distinctBy(ImaFolder::id)
    .map { folder ->
        DestinationFolderOption(
            id = folder.id,
            name = folder.name.ifBlank { "未命名文件夹" },
            displayPath = folder.displayPath.ifBlank { folder.name.ifBlank { "未命名文件夹" } },
            depth = folder.depth.coerceAtLeast(0),
        )
    }
