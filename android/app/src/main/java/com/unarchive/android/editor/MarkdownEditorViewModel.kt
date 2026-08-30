package com.unarchive.android.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unarchive.android.card.FileNoteContentRepository
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteContent
import com.unarchive.android.card.NoteContentSaveResult
import com.unarchive.android.card.NoteDraftState
import com.unarchive.android.card.NoteMarkdownWriteback
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
    val aiProposal: MarkdownAiProposal? = null,
    val aiProposalState: MarkdownAiProposalState = MarkdownAiProposalState.IDLE,
    val aiProposalError: String? = null,
    /** True after the candidate has been copied into the draft, before save. */
    val aiProposalApplied: Boolean = false,
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
            content.draftState != null -> MarkdownEditorSaveState.DIRTY
            // A projection diagnostic is independent from the formal save:
            // the Markdown revision was committed and is safe to reopen.
            content.projectionStatus == NoteProjectionStatus.FAILED &&
                content.lastSaveError != null -> MarkdownEditorSaveState.SAVED
            content.lastSaveError != null -> MarkdownEditorSaveState.FAILED
            else -> MarkdownEditorSaveState.CLEAN
        }
    }
}

enum class MarkdownAiProposalState { IDLE, RUNNING, FAILED }

sealed interface MarkdownEditorEvent {
    data class MarkdownChanged(val value: String) : MarkdownEditorEvent
    data object RequestAiProposal : MarkdownEditorEvent
    data object ApplyAiProposal : MarkdownEditorEvent
    data object RejectAiProposal : MarkdownEditorEvent
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
    private val aiProposalGenerator: MarkdownAiProposalGenerator? = null,
    private val aiProposalRepository: MarkdownAiProposalRepository? = null,
) : ViewModel() {
    private val initialContent = requireNotNull(repository.find(cardId, cardVersion)) {
        "Missing schema-v3 note content for ${cardId.value}/$cardVersion"
    }
    private val _uiState = MutableStateFlow(MarkdownEditorUiState(initialContent))
    val uiState: StateFlow<MarkdownEditorUiState> = _uiState.asStateFlow()

    private var editGeneration = 0L
    private var draftJob: Job? = null
    private var saveJob: Job? = null
    private var aiGenerationJob: Job? = null
    private var aiGenerationToken = 0L

    init {
        restorePendingAiProposal()
    }

    fun onEvent(event: MarkdownEditorEvent) {
        when (event) {
            is MarkdownEditorEvent.MarkdownChanged -> markdownChanged(event.value)
            MarkdownEditorEvent.RequestAiProposal -> requestAiProposal()
            MarkdownEditorEvent.ApplyAiProposal -> applyAiProposal()
            MarkdownEditorEvent.RejectAiProposal -> rejectAiProposal()
            MarkdownEditorEvent.Save -> save()
            MarkdownEditorEvent.ApplyRecoveredDraft -> applyRecoveredDraft()
            MarkdownEditorEvent.DiscardRecoveredDraft -> discardRecoveredDraft()
            MarkdownEditorEvent.RestoreLastSaved -> restoreLastSaved()
        }
    }

    private fun markdownChanged(value: String) {
        editGeneration += 1L
        aiGenerationToken += 1L
        aiGenerationJob?.cancel()
        draftJob?.cancel()
        val current = _uiState.value
        val generationInvalidated = current.aiProposalState == MarkdownAiProposalState.RUNNING
        val proposalInvalidated = current.aiProposal?.let { proposal ->
            !current.aiProposalApplied &&
                NoteContent.markdownFingerprint(value) != proposal.baseContentFingerprint
        } == true
        if (proposalInvalidated) {
            expireAiProposal("正文已变化，原 AI 候选已过期，请重新生成")
        }
        _uiState.update {
            it.copy(
                draftMarkdown = value,
                saveState = if (value == it.content.markdown) {
                    MarkdownEditorSaveState.CLEAN
                } else {
                    MarkdownEditorSaveState.DIRTY
                },
                errorMessage = null,
                aiProposalState = if (generationInvalidated) MarkdownAiProposalState.FAILED else it.aiProposalState,
                aiProposalError = if (generationInvalidated) {
                    "生成期间正文已变化，候选已过期，请重新生成。"
                } else {
                    it.aiProposalError
                },
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

    private fun requestAiProposal() {
        val generator = aiProposalGenerator
        val current = _uiState.value
        if (generator == null) {
            _uiState.update {
                it.copy(
                    aiProposalState = MarkdownAiProposalState.FAILED,
                    aiProposalError = "当前未配置 Markdown AI 服务。",
                )
            }
            return
        }
        if (current.aiProposalState == MarkdownAiProposalState.RUNNING ||
            current.saveState == MarkdownEditorSaveState.SAVING
        ) return
        if (current.isDirty) {
            _uiState.update {
                it.copy(
                    aiProposalState = MarkdownAiProposalState.FAILED,
                    aiProposalError = "请先正式保存当前 Markdown，再生成 AI 候选。",
                )
            }
            return
        }

        aiGenerationJob?.cancel()
        val generation = ++aiGenerationToken
        val baseContent = current.content
        // The formal Markdown is the recovery-safe base. A dirty draft may be
        // edited while generation runs, but it cannot silently become the AI
        // candidate's new base.
        _uiState.update {
            it.copy(
                aiProposal = null,
                aiProposalState = MarkdownAiProposalState.RUNNING,
                aiProposalError = null,
                aiProposalApplied = false,
            )
        }
        aiGenerationJob = viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { generator.generate(baseContent) }
                if (generation != aiGenerationToken) return@launch
                val latest = _uiState.value
                if (latest.content.markdownRevision != baseContent.markdownRevision ||
                    latest.draftMarkdown != baseContent.markdown
                ) {
                    _uiState.update {
                        it.copy(
                            aiProposal = null,
                            aiProposalState = MarkdownAiProposalState.FAILED,
                            aiProposalError = "生成期间正文或修订已变化，候选已过期，请重新生成。",
                            aiProposalApplied = false,
                        )
                    }
                    return@launch
                }
                val proposal = MarkdownAiProposalBuilder.create(
                    content = baseContent,
                    proposedMarkdown = result.markdown,
                    proposalId = "markdown-${nowEpochMs()}-${generation}",
                    model = result.model,
                    createdAtEpochMs = nowEpochMs(),
                )
                withContext(ioDispatcher) { aiProposalRepository?.save(proposal) }
                _uiState.update {
                    it.copy(
                        aiProposal = proposal,
                        aiProposalState = MarkdownAiProposalState.IDLE,
                        aiProposalError = null,
                        aiProposalApplied = false,
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (generation == aiGenerationToken) {
                    _uiState.update {
                        it.copy(
                            aiProposalState = MarkdownAiProposalState.FAILED,
                            aiProposalError = safeMessage(error),
                            aiProposalApplied = false,
                        )
                    }
                }
            }
        }
    }

    private fun applyAiProposal() {
        val state = _uiState.value
        val proposal = state.aiProposal ?: return
        if (proposal.status != MarkdownAiProposalStatus.PENDING) return
        val baseMatches = state.content.cardId.value == proposal.baseCardId &&
            state.content.cardVersion == proposal.baseCardVersion &&
            state.content.markdownRevision == proposal.baseMarkdownRevision &&
            NoteContent.markdownFingerprint(state.draftMarkdown) == proposal.baseContentFingerprint
        if (!baseMatches) {
            expireAiProposal("AI 候选基于旧版本，已过期，未覆盖当前正文")
            return
        }
        val safety = MarkdownAiSafetyValidator.validate(proposal.proposedMarkdown)
        if (!safety.isSafe) {
            _uiState.update {
                it.copy(
                    aiProposalState = MarkdownAiProposalState.FAILED,
                    aiProposalError = "候选未通过安全检查：${safety.errors.joinToString("；")}",
                )
            }
            return
        }
        editGeneration += 1L
        aiGenerationToken += 1L
        aiGenerationJob?.cancel()
        draftJob?.cancel()
        _uiState.update {
            it.copy(
                draftMarkdown = proposal.proposedMarkdown,
                saveState = MarkdownEditorSaveState.DIRTY,
                aiProposalState = MarkdownAiProposalState.IDLE,
                aiProposalError = null,
                aiProposalApplied = true,
                errorMessage = null,
            )
        }
        scheduleDraftSave()
    }

    private fun rejectAiProposal() {
        val proposal = _uiState.value.aiProposal ?: return
        aiGenerationJob?.cancel()
        viewModelScope.launch {
            withContext(ioDispatcher) {
                aiProposalRepository?.delete(proposal.baseCardId, proposal.baseCardVersion)
            }
        }
        _uiState.update {
            it.copy(
                aiProposal = null,
                aiProposalState = MarkdownAiProposalState.IDLE,
                aiProposalError = null,
                aiProposalApplied = false,
            )
        }
    }

    private fun expireAiProposal(message: String) {
        val proposal = _uiState.value.aiProposal ?: return
        aiGenerationJob?.cancel()
        viewModelScope.launch {
            withContext(ioDispatcher) {
                aiProposalRepository?.delete(proposal.baseCardId, proposal.baseCardVersion)
            }
        }
        _uiState.update {
            it.copy(
                aiProposal = null,
                aiProposalState = MarkdownAiProposalState.FAILED,
                aiProposalError = message,
                aiProposalApplied = false,
            )
        }
    }

    private fun restorePendingAiProposal() {
        val store = aiProposalRepository ?: return
        val restored = runCatching {
            store.find(initialContent.cardId.value, initialContent.cardVersion)
        }.getOrNull() ?: return
        val matches = restored.baseCardId == initialContent.cardId.value &&
            restored.baseCardVersion == initialContent.cardVersion &&
            restored.baseMarkdownRevision == initialContent.markdownRevision &&
            restored.baseContentFingerprint == NoteContent.markdownFingerprint(initialContent.markdown)
        if (matches) {
            _uiState.update { it.copy(aiProposal = restored) }
        } else {
            runCatching { store.delete(restored.baseCardId, restored.baseCardVersion) }
            _uiState.update {
                it.copy(
                    aiProposalState = MarkdownAiProposalState.FAILED,
                    aiProposalError = "发现基于旧版本的 AI 候选，已自动废弃；当前正文未被修改。",
                )
            }
        }
    }

    private fun scheduleDraftSave() {
        val state = _uiState.value
        if (state.draftMarkdown == state.content.markdown) return
        val generation = editGeneration
        draftJob = viewModelScope.launch {
            delay(draftDebounceMs)
            if (generation != editGeneration) return@launch
            val latest = _uiState.value
            val draft = NoteDraftState(
                markdown = latest.draftMarkdown,
                baseMarkdownRevision = latest.content.markdownRevision,
                contentFingerprint = NoteContent.markdownFingerprint(latest.draftMarkdown),
                updatedAtEpochMs = nowEpochMs(),
            )
            _uiState.update { it.copy(saveState = MarkdownEditorSaveState.DRAFT_SAVING) }
            try {
                val persisted = withContext(ioDispatcher) { repository.saveDraft(latest.content, draft) }
                if (generation == editGeneration && _uiState.value.draftMarkdown == draft.markdown) {
                    _uiState.update {
                        it.copy(content = persisted, saveState = MarkdownEditorSaveState.DIRTY, errorMessage = null)
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (generation == editGeneration) {
                    _uiState.update { it.copy(saveState = MarkdownEditorSaveState.FAILED, errorMessage = safeMessage(error)) }
                }
            }
        }
    }

    private fun save() {
        aiGenerationToken += 1L
        aiGenerationJob?.cancel()
        draftJob?.cancel()
        saveJob?.cancel()
        val snapshotGeneration = editGeneration
        val state = _uiState.value
        val markdown = state.draftMarkdown
        _uiState.update { it.copy(saveState = MarkdownEditorSaveState.SAVING, errorMessage = null) }
        saveJob = viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) {
                    val writeback = NoteMarkdownWriteback.apply(state.content.structuredMetadata, markdown)
                    val parseError = writeback.error?.let { "Markdown 投影解析失败：$it" }
                    repository.save(
                        state.content.copy(
                            markdown = markdown,
                            structuredMetadata = writeback.document,
                            structuredProjection = writeback.projection,
                            projectionStatus = writeback.status,
                            lastSaveError = parseError,
                        ),
                    )
                }
                if (snapshotGeneration != editGeneration || _uiState.value.draftMarkdown != markdown) return@launch
                if (result.isComplete) {
                    val savedProposal = _uiState.value.aiProposal
                    withContext(ioDispatcher) {
                        savedProposal?.let { aiProposalRepository?.delete(it.baseCardId, it.baseCardVersion) }
                    }
                    _uiState.update {
                        it.copy(content = result.content, draftMarkdown = markdown,
                            recoveryDraft = null, saveState = MarkdownEditorSaveState.SAVED,
                            // A parser diagnostic means the Markdown was still
                            // committed, but its structured projection needs
                            // user attention. Keep it visible independently of
                            // the formal-save state.
                            errorMessage = result.content.lastSaveError,
                            aiProposal = null,
                            aiProposalApplied = false,
                            aiProposalError = null)
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
        aiGenerationToken += 1L
        aiGenerationJob?.cancel()
        draftJob?.cancel()
        saveJob?.cancel()
        val proposal = state.aiProposal
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    repository.discardDraft(state.content)
                    proposal?.let { aiProposalRepository?.delete(it.baseCardId, it.baseCardVersion) }
                }
                if (generation == editGeneration) {
                    _uiState.update { it.copy(content = it.content.copy(draftState = null),
                        draftMarkdown = it.content.markdown, recoveryDraft = null,
                        saveState = MarkdownEditorSaveState.CLEAN, errorMessage = null,
                        aiProposal = null, aiProposalApplied = false,
                        aiProposalError = null) }
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
