package com.unarchive.android.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unarchive.android.card.NoteContentMigrationChoice
import com.unarchive.android.card.NoteContentMigrationOutcome
import com.unarchive.android.card.NoteContentSynchronizer
import com.unarchive.android.card.NoteDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MarkdownEditorMigrationUiState {
    data object Loading : MarkdownEditorMigrationUiState
    data object Ready : MarkdownEditorMigrationUiState
    data class Conflict(
        val legacyMarkdown: String,
        val structuredMarkdown: String,
    ) : MarkdownEditorMigrationUiState
    data class Failed(val message: String, val legacyMarkdown: String?) : MarkdownEditorMigrationUiState
}

sealed interface MarkdownEditorMigrationEvent {
    data object Retry : MarkdownEditorMigrationEvent
    data class Resolve(val choice: NoteContentMigrationChoice) : MarkdownEditorMigrationEvent
}

/** Loads the v3 editor only after legacy data is safely migrated or explicitly resolved. */
class MarkdownEditorMigrationViewModel(
    private val document: NoteDocument,
    private val synchronizer: NoteContentSynchronizer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow<MarkdownEditorMigrationUiState>(
        MarkdownEditorMigrationUiState.Loading,
    )
    val uiState: StateFlow<MarkdownEditorMigrationUiState> = _uiState.asStateFlow()
    private var pendingConflict: NoteContentMigrationOutcome.Conflict? = null

    init {
        prepare()
    }

    fun onEvent(event: MarkdownEditorMigrationEvent) {
        when (event) {
            MarkdownEditorMigrationEvent.Retry -> prepare()
            is MarkdownEditorMigrationEvent.Resolve -> resolve(event.choice)
        }
    }

    private fun prepare() {
        pendingConflict = null
        _uiState.value = MarkdownEditorMigrationUiState.Loading
        viewModelScope.launch {
            publish(withContext(ioDispatcher) {
                synchronizer.prepare(document.cardId, document.generation.cardVersion)
            })
        }
    }

    private fun resolve(choice: NoteContentMigrationChoice) {
        val conflict = pendingConflict ?: return
        _uiState.value = MarkdownEditorMigrationUiState.Loading
        viewModelScope.launch {
            publish(withContext(ioDispatcher) { synchronizer.resolve(conflict.decision, choice) })
        }
    }

    private fun publish(outcome: NoteContentMigrationOutcome) {
        _uiState.value = when (outcome) {
            is NoteContentMigrationOutcome.Ready -> MarkdownEditorMigrationUiState.Ready
            is NoteContentMigrationOutcome.Conflict -> {
                pendingConflict = outcome
                MarkdownEditorMigrationUiState.Conflict(
                    legacyMarkdown = outcome.decision.backup.markdown,
                    structuredMarkdown = outcome.decision.generatedMarkdown,
                )
            }
            is NoteContentMigrationOutcome.Failed -> MarkdownEditorMigrationUiState.Failed(
                outcome.message,
                outcome.legacyMarkdown,
            )
        }
    }
}
