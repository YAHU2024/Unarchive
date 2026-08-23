package com.unarchive.android.ui.state

import com.unarchive.android.UnarchiveViewModel
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.GraphRelationItem
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.toNoteDocument

enum class GraphRelationOperationState { IDLE, SAVING, SAVED, FAILED }

internal data class UnarchiveUiState(
    val create: CreateUiState,
    val notes: NotesUiState,
    val graph: GraphUiState,
    val me: MeUiState,
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
    data object ProcessVideo : CreateEvent
    data object CancelProcessing : CreateEvent
    data object ResumeBatch : CreateEvent
}

internal sealed interface NotesEvent {
    data object Refresh : NotesEvent
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

internal fun UnarchiveViewModel.toUnarchiveUiState(): UnarchiveUiState =
    UnarchiveUiState(
        create = CreateUiState(
            videoReference = videoReference,
            isProcessing = runningJob?.isActive == true,
            statusMessage = status,
            hasRecovery = batchRecoveryAvailable,
            storedResultCount = storedResults.size,
            latestNoteDocument = noteDocuments.firstOrNull {
                it.cardId == selectedKnowledgeCard?.cardId
            } ?: noteDocuments.firstOrNull(),
        ),
        notes = NotesUiState(
            noteCount = maxOf(knowledgeCards.size, noteDocuments.size),
            materialCount = storedResults.size,
            noteTitles = noteDocuments.map { it.title }.ifEmpty { knowledgeCards.map { it.title } }.take(6),
            noteCards = knowledgeCards,
            noteDocuments = noteDocuments.ifEmpty { knowledgeCards.map { it.toNoteDocument() } },
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
    )
