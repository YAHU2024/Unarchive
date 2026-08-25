package com.unarchive.android.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unarchive.android.card.FileNoteContentRepository
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteContent
import com.unarchive.android.card.NoteContentSaveResult
import com.unarchive.android.card.NoteDraftState
import com.unarchive.android.card.NoteProjectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MarkdownEditorSaveState {
    CLEAN,
    DIRTY,
    DRAFT_SAVING,
    SAVING,
    SAVED,
    FAILED,
}

data class MarkdownEditorUiState(
    val content: NoteContent,
    val draftMarkdown: String = content.markdown,
    val saveState: MarkdownEditorSaveState = initialSaveState(content),
    val recoveryDraft: NoteDraftState? = content.draftState,
    val errorMessage: String? = content.lastSaveError,
) {
    val isDirty: Boolean get() = draftMarkdown != content.markdown

    /** A changed Markdown source cannot claim a current structured projection. */
    val effectiveProjectionStatus: NoteProjectionStatus
        get() = if (isDirty && content.projectionStatus == NoteProjectionStatus.CURRENT) {
            NoteProjectionStatus.PARTIAL
        } else {
            content.projectionStatus
        }

    companion object {
        private fun initialSaveState(content: NoteContent): MarkdownEditorSaveState = when {
            content.lastSaveError != null -> MarkdownEditorSaveState.FAILED
            content.draftState != null -> MarkdownEditorSaveState.DIRTY
            else -> MarkdownEditorSaveState.CLEAN
        }
    }
}

sealed interface MarkdownEditorEvent {
    data class MarkdownChanged(val value: String) : MarkdownEditorEvent
    data object Save : MarkdownEditorEvent
    data object ApplyRecoveredDraft : MarkdownEditorEvent
    data object DiscardRecoveredDraft : MarkdownEditorEvent
    data object RestoreLastSaved : MarkdownEditorEvent
}

/**
 * Owns the schema-v3 Markdown source editor without changing the existing
 * structured editor or its schema-v2 repository.
 */
class MarkdownEditorViewModel(
    cardId: KnowledgeCardId,
    cardVersion: String,
    private val repository: FileNoteContentRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val draftDebounceMs: Long = 500L,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val initialContent = requireNotNull(repository.find(cardId, cardVersion)) {
        "Missing schema-v3 note content for ${cardId.value}/$cardVersion"
    }
    private val _uiState = MutableStateFlow(MarkdownEditorUiState(initialContent))
    val uiState: StateFlow<MarkdownEditorUiState> = _uiState.asStateFlow()

    private var editGeneration = 0L
    private var draftJob: Job? = null
    private var saveJob: Job? = null

    fun onEvent(event: MarkdownEditorEvent) {
        when (event) {
            is MarkdownEditorEvent.MarkdownChanged -> markdownChanged(event.value)
            MarkdownEditorEvent.Save -> save()
            MarkdownEditorEvent.ApplyRecoveredDraft -> applyRecoveredDraft()
            MarkdownEditorEvent.DiscardRecoveredDraft -> discardRecoveredDraft()
            MarkdownEditorEvent.RestoreLastSaved -> restoreLastSaved()
        }
    }

    private fun markdownChanged(value: String) {
        editGeneration += 1L
        draftJob?.cancel()
        _uiState.update {
            it.copy(
                draftMarkdown = value,
                saveState = if (value == it.content.markdown) {
                    MarkdownEditorSaveState.CLEAN
                } else {
                    MarkdownEditorSaveState.DIRTY
                },
                errorMessage = null,
            )
        }
        if (value == _uiState.value.content.markdown) return

        val generation = editGeneration
        draftJob = viewModelScope.launch {
            delay(draftDebounceMs)
            if (generation != editGeneration) return@launch
            val state = _uiState.value
            val draft = NoteDraftState(
                markdown = state.draftMarkdown,
                baseMarkdownRevision = state.content.markdownRevision,
                contentFingerprint = NoteContent.markdownFingerprint(state.draftMarkdown),
                updatedAtEpochMs = nowEpochMs(),
            )
            _uiState.update { it.copy(saveState = MarkdownEditorSaveState.DRAFT_SAVING) }
            try {
                val persisted = withContext(ioDispatcher) {
                    repository.saveDraft(state.content, draft)
                }
                if (generation == editGeneration && _uiState.value.draftMarkdown == draft.markdown) {
                    _uiState.update {
                        // A draft created in this active session is recovery data
                        // for a future editor instance, not a new recovery prompt.
                        it.copy(content = persisted,
                            saveState = MarkdownEditorSaveState.DIRTY, errorMessage = null)
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (generation == editGeneration) {
                    _uiState.update {
                        it.copy(saveState = MarkdownEditorSaveState.FAILED,
                            errorMessage = safeMessage(error))
                    }
                }
            }
        }
    }

    private fun save() {
        draftJob?.cancel()
        saveJob?.cancel()
        val snapshotGeneration = editGeneration
        val state = _uiState.value
        val markdown = state.draftMarkdown
        _uiState.update { it.copy(saveState = MarkdownEditorSaveState.SAVING, errorMessage = null) }
        saveJob = viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) {
                    val status = if (markdown != state.content.markdown &&
                        state.content.projectionStatus == NoteProjectionStatus.CURRENT
                    ) NoteProjectionStatus.PARTIAL else state.content.projectionStatus
                    repository.save(state.content.copy(markdown = markdown, projectionStatus = status))
                }
                if (snapshotGeneration != editGeneration || _uiState.value.draftMarkdown != markdown) return@launch
                if (result.isComplete) {
                    _uiState.update {
                        it.copy(content = result.content, draftMarkdown = markdown,
                            recoveryDraft = null, saveState = MarkdownEditorSaveState.SAVED,
                            errorMessage = null)
                    }
                } else {
                    _uiState.update {
                        it.copy(saveState = MarkdownEditorSaveState.FAILED,
                            errorMessage = result.error ?: "正式保存未完成")
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (snapshotGeneration == editGeneration) {
                    _uiState.update {
                        it.copy(saveState = MarkdownEditorSaveState.FAILED,
                            errorMessage = safeMessage(error))
                    }
                }
            }
        }
    }

    private fun applyRecoveredDraft() {
        val recovery = _uiState.value.recoveryDraft ?: return
        editGeneration += 1L
        draftJob?.cancel()
        _uiState.update {
            it.copy(draftMarkdown = recovery.markdown, recoveryDraft = null,
                saveState = MarkdownEditorSaveState.DIRTY, errorMessage = null)
        }
    }

    private fun discardRecoveredDraft() {
        val state = _uiState.value
        if (state.recoveryDraft == null) return
        val generation = ++editGeneration
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { repository.discardDraft(state.content) }
                if (generation == editGeneration) {
                    _uiState.update { it.copy(content = it.content.copy(draftState = null),
                        recoveryDraft = null, saveState = if (it.isDirty) MarkdownEditorSaveState.DIRTY else MarkdownEditorSaveState.CLEAN,
                        errorMessage = null) }
                }
            } catch (error: Throwable) {
                if (generation == editGeneration) {
                    _uiState.update { it.copy(saveState = MarkdownEditorSaveState.FAILED,
                        errorMessage = safeMessage(error)) }
                }
            }
        }
    }

    private fun restoreLastSaved() {
        val state = _uiState.value
        val generation = ++editGeneration
        draftJob?.cancel()
        saveJob?.cancel()
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { repository.discardDraft(state.content) }
                if (generation == editGeneration) {
                    _uiState.update { it.copy(content = it.content.copy(draftState = null),
                        draftMarkdown = it.content.markdown, recoveryDraft = null,
                        saveState = MarkdownEditorSaveState.CLEAN, errorMessage = null) }
                }
            } catch (error: Throwable) {
                if (generation == editGeneration) {
                    _uiState.update { it.copy(saveState = MarkdownEditorSaveState.FAILED,
                        errorMessage = safeMessage(error)) }
                }
            }
        }
    }

    private fun safeMessage(error: Throwable): String = error.message ?: error::class.java.simpleName
}
