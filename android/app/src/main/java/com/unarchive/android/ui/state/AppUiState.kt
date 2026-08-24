package com.unarchive.android.ui.state

import com.unarchive.android.UnarchiveViewModel
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.GraphRelationItem
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.KnowledgeSyncState
import com.unarchive.android.card.toNoteDocument
import com.unarchive.android.sync.DestinationAction
import com.unarchive.android.sync.DestinationTargetStateMapper

enum class GraphRelationOperationState { IDLE, SAVING, SAVED, FAILED }

enum class DestinationOperationState { IDLE, RUNNING, SUCCEEDED, FAILED }

internal data class DestinationTargetOption(
    val knowledgeBaseId: String,
    val knowledgeBaseName: String,
    val folderId: String = "",
    val folderName: String = "根目录",
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
    val localSaved: Boolean = false,
    val exportState: DestinationOperationState = DestinationOperationState.IDLE,
    val exportMessage: String = "",
    val exportEmbeddedAssetCount: Int = 0,
    val exportMissingAssetCount: Int = 0,
    val imaConfigured: Boolean = false,
    val imaSyncing: Boolean = false,
    val currentTargetLabel: String = "",
    val imaStateWarning: String? = null,
    val targetOptions: List<DestinationTargetOption> = emptyList(),
    val targetRecords: List<DestinationTargetUiState> = emptyList(),
)

internal data class UnarchiveUiState(
    val create: CreateUiState,
    val notes: NotesUiState,
    val graph: GraphUiState,
    val me: MeUiState,
    val destinations: DestinationUiState = DestinationUiState(),
)

internal data class CreateUiState(
    val videoReference: String,
    val isProcessing: Boolean,
    val statusMessage: String,
    val hasRecovery: Boolean,
    val storedResultCount: Int,
    val latestNoteDocument: NoteDocument? = null,
)

internal data class NotesUiState(
    val noteCount: Int,
    val materialCount: Int,
    val noteTitles: List<String>,
    val noteCards: List<KnowledgeCard> = emptyList(),
    val noteDocuments: List<NoteDocument> = emptyList(),
    val libraryItems: List<NotesLibraryItem> = emptyList(),
    val canGenerateDraft: Boolean = true,
    val coverRetryInProgress: Boolean = false,
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
    data class SelectTarget(val knowledgeBaseId: String, val folderId: String) : DestinationEvent
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

internal fun UnarchiveViewModel.toUnarchiveUiState(): UnarchiveUiState {
    val documentedIds = noteDocuments.mapTo(mutableSetOf(), NoteDocument::cardId)
    val effectiveDocuments = (
        noteDocuments + knowledgeCards
            .filterNot { it.cardId in documentedIds }
            .map { it.toNoteDocument() }
        ).sortedByDescending(NoteDocument::updatedAtEpochMs)
    val syncRecords = knowledgeSyncRecordsForCards(knowledgeCards)
    val thumbnailCandidates = knowledgeCards.associate { card ->
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
        noteCards = knowledgeCards,
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
        ),
        notes = NotesUiState(
            noteCount = libraryItems.count { it.kind == NotesLibraryItemKind.SAVED_NOTE },
            materialCount = libraryItems.count { it.kind == NotesLibraryItemKind.MATERIAL },
            noteTitles = effectiveDocuments.map { it.title }.take(6),
            noteCards = knowledgeCards,
            noteDocuments = effectiveDocuments,
            libraryItems = libraryItems,
            canGenerateDraft = runningJob == null && generateJob == null && exportJob == null,
            coverRetryInProgress = coverRetryInProgress,
        ),
        graph = run {
            val documents = noteDocuments.ifEmpty { knowledgeCards.map { it.toNoteDocument() } }
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
            // Only the selected KB's folders are known. Do not attach one
            // KB's folder IDs to every other target.
            val options = imaKnowledgeBases.flatMap { base ->
                val baseName = base.name.ifBlank { "ima 知识库" }
                val root = listOf(DestinationTargetOption(base.id, baseName))
                if (base.id != imaKnowledgeBaseId) root
                else root + imaFolders.filter { it.id.isNotBlank() }.map { folder ->
                    DestinationTargetOption(base.id, baseName, folder.id, folder.name.ifBlank { "文件夹" })
                }
            }
            DestinationUiState(
                card = card,
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
                imaSyncing = imaSyncing,
                currentTargetLabel = currentImaTargetDisplayName,
                imaStateWarning = imaSyncStateWarning,
                targetOptions = options,
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
    )
}
