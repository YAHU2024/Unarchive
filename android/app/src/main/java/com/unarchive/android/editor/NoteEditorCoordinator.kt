package com.unarchive.android.editor

import com.unarchive.android.card.*
import com.unarchive.android.editor.persistence.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Owns navigation/save decisions. The session and disk store each have one writer. */
class NoteEditorCoordinator(
    private val cardId: KnowledgeCardId,
    private val cardVersion: String,
    private val persistence: NotePersistenceCoordinator,
    private val scope: CoroutineScope,
    private val openDocument: suspend (NoteContent) -> NoteDocumentPort,
    private val draftDelayMs: Long = 500L,
) {
    private val mutableState = MutableStateFlow(MarkdownNoteUiState())
    val state = mutableState.asStateFlow()
    private val eventChannel = Channel<NoteEditorEffect>(Channel.UNLIMITED)
    val effects = eventChannel.receiveAsFlow()
    private val actions = Mutex()
    private val sessionId = UUID.randomUUID().toString()
    private var loadToken = 0L
    private var draftJob: Job? = null
    private var port: NoteDocumentPort? = null
    var formal: NoteContent? = null; private set
    var recovery: List<NoteRecoveryCandidate> = emptyList(); private set
    private var ownedDraft: NoteRecoveryToken? = null
    private var restoredDraft: NoteRecoveryToken? = null
    private var externalAfterSave = false
    private var terminal = false
    var pendingSourceEdit: PendingNoteSourceEdit? = null; private set
    var currentAssets: List<CardAsset> = emptyList(); private set
    var conflictLatest: NoteContent? = null; private set
    fun setAssets(assets: List<CardAsset>) {
        if (terminal) return
        currentAssets = assets.toList()
        mutableState.update { it.copy(dirty = it.dirty || currentAssets != formal?.structuredMetadata?.assets) }
        draftJob?.cancel()
        draftJob = scope.launch { delay(draftDelayMs); protectDraft() }
    }

    fun setPendingSourceEdit(value: PendingNoteSourceEdit?) {
        if (terminal) return
        pendingSourceEdit = value
        mutableState.update { it.copy(dirty = value != null || it.dirty) }
        draftJob?.cancel()
        draftJob = scope.launch { delay(draftDelayMs); protectDraft() }
    }

    fun open() {
        if (terminal) return
        val token = ++loadToken
        mutableState.value = MarkdownNoteUiState()
        scope.launch {
            try {
                val loaded = persistence.load(cardId, cardVersion) ?: error("找不到这篇笔记")
                if (token != loadToken) return@launch
                val document = openDocument(loaded.formal)
                if (token != loadToken) { document.dispose(); return@launch }
                document.activate()
                port = document
                formal = loaded.formal
                currentAssets = loaded.formal.structuredMetadata.assets
                pendingSourceEdit = null
                recovery = loaded.recovery
                ownedDraft = null
                restoredDraft = null
                mutableState.value = MarkdownNoteUiState(mode = NoteEditorMode.Reading,
                    title = document.displayTitle(loaded.formal.structuredMetadata.title), recoveryCount = recovery.size,
                    formalRevision = loaded.formal.markdownRevision)
            } catch (error: CancellationException) { throw error }
            catch (_: Throwable) {
                if (token == loadToken) mutableState.value = MarkdownNoteUiState(mode = NoteEditorMode.LoadFailed,
                    error = NoteEditorError(NoteErrorDomain.Load, "笔记载入失败，请重试"))
            }
        }
    }

    /** All mutable entry points must resolve existing recovery content first. */
    fun enterEditing(): Boolean {
        if (terminal || state.value.mode !in setOf(NoteEditorMode.Reading, NoteEditorMode.Editing)) return false
        if (recovery.isNotEmpty()) {
            mutableState.update { it.copy(panel = NoteEditorPanel.Recovery) }
            return false
        }
        mutableState.update { it.copy(mode = NoteEditorMode.Editing) }
        return true
    }

    fun onIntent(intent: NoteEditorIntent) {
        if (terminal || state.value.mode == NoteEditorMode.Saving) return
        when (intent) {
            NoteEditorIntent.RetryOpen -> open()
            NoteEditorIntent.Edit -> if (enterEditing()) mutableState.update { it.copy(panel = NoteEditorPanel.None) }
            NoteEditorIntent.Done -> save(false)
            NoteEditorIntent.SaveAndLeave -> save(true)
            NoteEditorIntent.RequestLeave -> requestLeave()
            NoteEditorIntent.DiscardAndLeave -> discardAndLeave()
            NoteEditorIntent.ClosePanel -> mutableState.update { it.copy(panel = NoteEditorPanel.None) }
            is NoteEditorIntent.OpenPanel -> mutableState.update { it.copy(panel = intent.panel) }
            is NoteEditorIntent.RestoreRecovery -> restoreRecovery(intent.index)
            is NoteEditorIntent.DiscardRecovery -> discardRecovery(intent.index)
            is NoteEditorIntent.CopyRecovery -> recovery.getOrNull(intent.index)?.let {
                eventChannel.trySend(NoteEditorEffect.CopyText(it.draft?.markdown ?: it.rawRecovery))
            }
            NoteEditorIntent.RetryDraft -> scope.launch { flushDraft() }
            NoteEditorIntent.RequestExternal -> scope.launch {
                actions.withLock {
                    val snapshot = frozen() ?: return@withLock
                    if (snapshot.markdown != snapshot.base.markdown || snapshot.assets != snapshot.base.structuredMetadata.assets || pendingSourceEdit != null)
                        mutableState.update { it.copy(panel = NoteEditorPanel.External) }
                    else openFormalExternal()
                }
            }
            NoteEditorIntent.SaveThenExternal -> { externalAfterSave = true; save(false) }
            NoteEditorIntent.UseFormalExternal -> { mutableState.update { it.copy(panel = NoteEditorPanel.None) }; openFormalExternal() }
            NoteEditorIntent.CopyCurrent -> scope.launch { port?.capture()?.let { eventChannel.send(NoteEditorEffect.CopyText(it.markdown)) } }
            NoteEditorIntent.LoadLatestAfterDiscard -> discardAndReload()
            NoteEditorIntent.Undo, NoteEditorIntent.Redo -> Unit // Routed through the native mutation barrier by the ViewModel.
        }
    }

    fun documentChanged(snapshot: NoteEditorSnapshot, title: String, canUndo: Boolean, canRedo: Boolean) {
        if (terminal) return
        val base = formal ?: return
        mutableState.update { it.copy(title = title, dirty = snapshot.markdown != base.markdown || pendingSourceEdit != null || currentAssets != base.structuredMetadata.assets,
            documentVersion = snapshot.documentVersion, canUndo = canUndo, canRedo = canRedo) }
        if (state.value.mode == NoteEditorMode.Editing) {
            draftJob?.cancel()
            draftJob = scope.launch { delay(draftDelayMs); protectDraft() }
        }
    }

    /** Returns after a real disk result; lifecycle callers must keep the scope alive until completion. */
    suspend fun flushDraft() {
        draftJob?.cancelAndJoin()
        draftJob = null
        protectDraft()
    }

    private suspend fun protectDraft() = actions.withLock {
        // Once disk IO starts, cancellation waits for its result and ownership token.
        withContext(NonCancellable) {
            try { writeCurrentDraft() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (!terminal) mutableState.update { it.copy(error = NoteEditorError(NoteErrorDomain.Draft,
                    "草稿保护未完成，当前输入仍保留，请重试")) }
            }
        }
    }

    /** Called before the native worker is disposed when this document really ends. */
    fun close() {
        terminal = true
        ++loadToken
        draftJob?.cancel()
        draftJob = null
    }

    private suspend fun frozen(): SaveSnapshot? {
        val base = formal ?: return null
        val value = port?.capture() ?: return null
        return SaveSnapshot(base, value.markdown, sessionId, value.documentVersion, assets = currentAssets,
            selectionAnchor = value.selectionStart.coerceIn(0, value.markdown.length),
            selectionFocus = value.selectionEnd.coerceIn(0, value.markdown.length), recoveryToken = restoredDraft,
            pendingSourceEdit = pendingSourceEdit)
    }

    private suspend fun writeCurrentDraft() {
        if (terminal) return
        if (state.value.mode !in setOf(NoteEditorMode.Editing, NoteEditorMode.Saving) &&
            !(state.value.mode == NoteEditorMode.Reading && (pendingSourceEdit != null || ownedDraft != null))) return
        val snapshot = frozen() ?: return
        if (snapshot.markdown == snapshot.base.markdown && snapshot.assets == snapshot.base.structuredMetadata.assets &&
            pendingSourceEdit == null && ownedDraft == null && restoredDraft == null) return
        when (val result = persistence.writeDraft(snapshot.copy(recoveryToken = ownedDraft ?: restoredDraft))) {
            is NoteDraftWriteResult.Written -> { ownedDraft = result.token
                mutableState.update { if (it.error?.domain == NoteErrorDomain.Draft) it.copy(error = null) else it } }
            is NoteDraftWriteResult.Conflict -> mutableState.update { it.copy(error = NoteEditorError(NoteErrorDomain.Draft,
                "草稿保护遇到版本冲突，当前输入仍保留，可复制或重试")) }
            is NoteDraftWriteResult.Failed -> mutableState.update { it.copy(error = NoteEditorError(NoteErrorDomain.Draft,
                "草稿保护失败，当前输入仍保留，请重试")) }
        }
    }

    fun save(leaveAfter: Boolean) {
        if (terminal) return
        if (state.value.mode !in setOf(NoteEditorMode.Editing, NoteEditorMode.Reading)) return
        mutableState.update { it.copy(mode = NoteEditorMode.Saving, panel = NoteEditorPanel.None, error = null) }
        scope.launch {
            draftJob?.cancelAndJoin(); draftJob = null
            actions.withLock {
                try {
                    val snapshot = frozen() ?: error("document-not-ready")
                    when (val result = persistence.save(snapshot)) {
                        is NotePersistenceSaveResult.Saved -> {
                            formal = result.content
                            currentAssets = result.content.structuredMetadata.assets
                            port?.markFormal(result.content.markdown)
                            ownedDraft = null; restoredDraft = null
                            recovery = persistence.loadRecoveryState(cardId, cardVersion)
                            mutableState.update { it.copy(mode = NoteEditorMode.Reading, dirty = false,
                                panel = NoteEditorPanel.None, formalRevision = result.content.markdownRevision,
                                recoveryCount = recovery.size,
                                error = (result.cleanupWarning ?: result.projectionWarning)?.let { message ->
                                    NoteEditorError(NoteErrorDomain.Recovery, message) }) }
                            eventChannel.send(NoteEditorEffect.FormalCommitted(result.content))
                            if (externalAfterSave) { externalAfterSave = false; openFormalExternal() }
                            if (leaveAfter) leave()
                        }
                        is NotePersistenceSaveResult.Conflict -> {
                            conflictLatest = result.latest
                            saveFailed("正式版本已变化，当前修改仍保留")
                            mutableState.update { it.copy(panel = NoteEditorPanel.Conflict) }
                        }
                        is NotePersistenceSaveResult.Failed -> saveFailed("正式保存失败，当前修改仍保留，请重试")
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Throwable) { saveFailed("正式保存失败，当前修改仍保留，请重试") }
            }
        }
    }

    private fun saveFailed(message: String) {
        externalAfterSave = false
        mutableState.update { it.copy(mode = NoteEditorMode.Editing, error = NoteEditorError(NoteErrorDomain.Save, message)) }
    }

    private fun openFormalExternal() { formal?.let { eventChannel.trySend(NoteEditorEffect.OpenExternal(NoteExternalActionCoordinator.request(it))) } }

    private suspend fun leave() {
        if (terminal) return
        terminal = true
        ++loadToken
        eventChannel.send(NoteEditorEffect.Leave)
    }

    private fun requestLeave() {
        scope.launch {
            draftJob?.cancelAndJoin(); draftJob = null
            actions.withLock {
                if (terminal || state.value.mode == NoteEditorMode.Saving) return@withLock
                val snapshot = port?.capture()
                val dirty = pendingSourceEdit != null || currentAssets != formal?.structuredMetadata?.assets ||
                    (snapshot != null && snapshot.markdown != formal?.markdown)
                if (dirty) {
                    mutableState.update { it.copy(dirty = true, panel = NoteEditorPanel.Leave) }
                    writeCurrentDraft()
                } else {
                    val token = ownedDraft
                    val result = token?.let { persistence.discard(cardId, cardVersion, it) }
                    if (result == null || result == NoteDraftDeleteResult.Deleted || result == NoteDraftDeleteResult.Missing) {
                        ownedDraft = null
                        leave()
                    } else mutableState.update { it.copy(error = NoteEditorError(NoteErrorDomain.Draft,
                        "草稿清理失败，当前页面仍保留，请重试")) }
                }
            }
        }
    }

    private fun discardAndLeave() {
        scope.launch {
            draftJob?.cancelAndJoin(); draftJob = null
            actions.withLock {
                if (terminal) return@withLock
                val token = ownedDraft ?: restoredDraft
                val result = token?.let { persistence.discard(cardId, cardVersion, it) }
                if (result == null || result == NoteDraftDeleteResult.Deleted || result == NoteDraftDeleteResult.Missing) {
                    leave()
                } else mutableState.update { it.copy(error = NoteEditorError(NoteErrorDomain.Draft,
                    "草稿清理失败，尚未离开，请重试")) }
            }
        }
    }

    private fun discardAndReload() {
        scope.launch {
            draftJob?.cancelAndJoin(); draftJob = null
            actions.withLock {
                val token = ownedDraft ?: restoredDraft
                val result = token?.let { persistence.discard(cardId, cardVersion, it) }
                if (result == null || result == NoteDraftDeleteResult.Deleted || result == NoteDraftDeleteResult.Missing) {
                    pendingSourceEdit = null; ownedDraft = null; restoredDraft = null; conflictLatest = null
                    open()
                } else mutableState.update { it.copy(error = NoteEditorError(NoteErrorDomain.Recovery, "当前草稿清理失败，尚未放弃修改")) }
            }
        }
    }

    private fun restoreRecovery(index: Int) {
        val candidate = recovery.getOrNull(index) ?: return
        val draft = candidate.draft ?: return
        if (candidate.status != NoteRecoveryStatus.AVAILABLE) return
        scope.launch {
            actions.withLock {
                port?.restoreSnapshot(draft.markdown, draft.selectionAnchor ?: 0, draft.selectionFocus ?: 0,
                    draft.assets ?: formal?.structuredMetadata?.assets.orEmpty())
                currentAssets = draft.assets ?: formal?.structuredMetadata?.assets.orEmpty()
                pendingSourceEdit = draft.pendingSourceEdit
                restoredDraft = candidate.token
                recovery = recovery.filterNot { it.token == candidate.token }
                mutableState.update { it.copy(mode = NoteEditorMode.Editing, dirty = draft.markdown != formal?.markdown ||
                    pendingSourceEdit != null || currentAssets != formal?.structuredMetadata?.assets,
                    panel = NoteEditorPanel.None, recoveryCount = recovery.size) }
            }
        }
    }

    private fun discardRecovery(index: Int) {
        val candidate = recovery.getOrNull(index) ?: return
        scope.launch {
            actions.withLock {
                when (persistence.discard(cardId, cardVersion, candidate.token)) {
                    NoteDraftDeleteResult.Deleted, NoteDraftDeleteResult.Missing -> {
                        recovery = recovery.filterNot { it.token == candidate.token }
                        mutableState.update { it.copy(recoveryCount = recovery.size,
                            panel = if (recovery.isEmpty()) NoteEditorPanel.None else NoteEditorPanel.Recovery, error = null) }
                    }
                    else -> mutableState.update { it.copy(error = NoteEditorError(NoteErrorDomain.Recovery,
                        "恢复稿清理失败，内容仍保留，请重试")) }
                }
            }
        }
    }
}
