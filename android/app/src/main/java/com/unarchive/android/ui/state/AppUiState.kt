package com.unarchive.android.ui.state

import com.unarchive.android.UnarchiveViewModel
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.toNoteDocument

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
        graph = GraphUiState(noteCount = knowledgeCards.size),
        me = MeUiState(),
    )
