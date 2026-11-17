package com.unarchive.android.editor

import com.unarchive.android.card.NoteContent

enum class NoteEditorMode { Loading, Reading, Editing, Saving, LoadFailed }

/** UI state deliberately excludes the document text and native Editable. */
data class MarkdownNoteUiState(
    val mode: NoteEditorMode = NoteEditorMode.Loading,
    val title: String = "笔记",
    val dirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val panel: NoteEditorPanel = NoteEditorPanel.None,
    val error: NoteEditorError? = null,
    val recoveryCount: Int = 0,
    val documentVersion: Long = 0,
    val formalRevision: Long = 0,
)

sealed interface NoteEditorPanel {
    data object None : NoteEditorPanel
    data object More : NoteEditorPanel
    data object Leave : NoteEditorPanel
    data object Recovery : NoteEditorPanel
    data object Info : NoteEditorPanel
    data object Outline : NoteEditorPanel
    data object Formatting : NoteEditorPanel
    data object Source : NoteEditorPanel
    data object Find : NoteEditorPanel
    data object Insert : NoteEditorPanel
    data object Link : NoteEditorPanel
    data object External : NoteEditorPanel
    data object Import : NoteEditorPanel
    data object Ai : NoteEditorPanel
    data object AiReview : NoteEditorPanel
    data object Title : NoteEditorPanel
    data class RecoveryReview(val index: Int) : NoteEditorPanel
    data object Conflict : NoteEditorPanel
}

enum class NoteErrorDomain { Load, Save, Draft, Recovery, Input }
data class NoteEditorError(val domain: NoteErrorDomain, val message: String)

sealed interface NoteEditorEffect {
    data object Leave : NoteEditorEffect
    data class FormalCommitted(val content: NoteContent) : NoteEditorEffect
    data class CopyText(val text: String) : NoteEditorEffect
    data class OpenExternal(val request: FormalOperationRequest) : NoteEditorEffect
}

/** Captured only at a queue boundary, never on each UI recomposition. */
data class NoteEditorSnapshot(
    val markdown: String,
    val documentVersion: Long,
    val selectionStart: Int,
    val selectionEnd: Int,
)

data class NoteSourceScratch(val start: Int, val end: Int, val original: String, val text: String,
    val baseSourceFingerprint: String, val documentVersion: Long) {
    val changed: Boolean get() = original != text
}

/** Adapter to the project-owned session; contains no second editable model. */
interface NoteDocumentPort {
    fun activate() = Unit
    fun dispose() = Unit
    fun displayTitle(fallback: String): String = fallback
    suspend fun capture(): NoteEditorSnapshot
    suspend fun restore(markdown: String, selectionStart: Int = 0, selectionEnd: Int = selectionStart)
    suspend fun restoreSnapshot(markdown: String, selectionStart: Int, selectionEnd: Int, assets: List<com.unarchive.android.card.CardAsset>) =
        restore(markdown, selectionStart, selectionEnd)
    suspend fun markFormal(markdown: String)
}

sealed interface NoteEditorIntent {
    data object RetryOpen : NoteEditorIntent
    data object Edit : NoteEditorIntent
    data object Done : NoteEditorIntent
    data object Undo : NoteEditorIntent
    data object Redo : NoteEditorIntent
    data object RequestLeave : NoteEditorIntent
    data object SaveAndLeave : NoteEditorIntent
    data object DiscardAndLeave : NoteEditorIntent
    data object ClosePanel : NoteEditorIntent
    data class OpenPanel(val panel: NoteEditorPanel) : NoteEditorIntent
    data class RestoreRecovery(val index: Int) : NoteEditorIntent
    data class DiscardRecovery(val index: Int) : NoteEditorIntent
    data class CopyRecovery(val index: Int) : NoteEditorIntent
    data object RetryDraft : NoteEditorIntent
    data object RequestExternal : NoteEditorIntent
    data object SaveThenExternal : NoteEditorIntent
    data object UseFormalExternal : NoteEditorIntent
    data object CopyCurrent : NoteEditorIntent
    data object LoadLatestAfterDiscard : NoteEditorIntent
}
