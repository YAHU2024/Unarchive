package com.unarchive.android.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unarchive.android.card.NoteBlock
import com.unarchive.android.card.NoteBlockOrigin
import com.unarchive.android.card.NoteBlockType
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteDocumentRepository
import com.unarchive.android.card.NoteDocumentSaveResult
import com.unarchive.android.card.NoteDocumentSavePhase
import com.unarchive.android.card.NotePoint
import com.unarchive.android.card.NoteSourceRef
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.NoteGenerationState
import com.unarchive.android.card.NoteGeneration
import com.unarchive.android.card.NoteEditingState
import com.unarchive.android.card.NotePublishingState
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NoteEditorSaveState {
    CLEAN,
    DIRTY,
    SAVING,
    SAVED,
    PROJECTION_PENDING,
    FAILED,
}

data class NoteEditorUiState(
    val document: NoteDocument,
    val saveState: NoteEditorSaveState = initialSaveState(document),
    val draftTitle: String = document.title,
    val draftBlockTexts: Map<String, String> = emptyMap(),
    val draftChapterTitles: Map<String, String> = emptyMap(),
    val tagsInput: String = document.tags.joinToString(", "),
    val errorMessage: String? = document.editing.lastSaveError,
) {
    val isDirty: Boolean get() = saveState == NoteEditorSaveState.DIRTY || document.editing.dirty

    fun blockText(block: NoteBlock): String = draftBlockTexts[block.id] ?: block.text

    fun chapterTitle(block: NoteBlock): String = draftChapterTitles[block.id] ?: block.title

    companion object {
        private fun initialSaveState(document: NoteDocument): NoteEditorSaveState = when {
            document.editing.lastSaveError != null -> NoteEditorSaveState.FAILED
            document.editing.dirty -> NoteEditorSaveState.DIRTY
            document.editing.lastSavedAtEpochMs != null -> NoteEditorSaveState.SAVED
            else -> NoteEditorSaveState.CLEAN
        }
    }
}

sealed interface NoteEditorEvent {
    data class TitleChanged(val value: String) : NoteEditorEvent
    data class BlockTextChanged(val blockId: String, val value: String) : NoteEditorEvent
    data class ChapterTitleChanged(val blockId: String, val value: String) : NoteEditorEvent
    data class ChapterDescriptionChanged(val blockId: String, val value: String) : NoteEditorEvent
    data class TagsChanged(val value: String) : NoteEditorEvent
    data class AddBlock(val type: NoteBlockType) : NoteEditorEvent
    data class RemoveBlock(val blockId: String) : NoteEditorEvent
    data class MoveBlock(val blockId: String, val direction: Direction) : NoteEditorEvent
    data object Save : NoteEditorEvent
    data object RestoreLastSaved : NoteEditorEvent

    enum class Direction { UP, DOWN }
}

/**
 * Owns structured note editing. AI output is intentionally not an event here:
 * a future AI proposal must be compared or explicitly applied by the user.
 */
class NoteEditorViewModel(
    initialDocument: NoteDocument,
    private val repository: NoteDocumentRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val initial = initialDocument
    private val _uiState = MutableStateFlow(NoteEditorUiState(initialDocument))
    val uiState: StateFlow<NoteEditorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val persisted = withContext(ioDispatcher) {
                repository.find(initialDocument.cardId, initialDocument.generation.cardVersion)
            } ?: return@launch
            val current = _uiState.value
            if (current.document == initial && current.saveState == NoteEditorSaveState.CLEAN) {
                _uiState.value = NoteEditorUiState(persisted)
            }
        }
    }

    fun onEvent(event: NoteEditorEvent) {
        when (event) {
            is NoteEditorEvent.TitleChanged -> titleChanged(event.value)
            is NoteEditorEvent.BlockTextChanged -> blockTextChanged(event.blockId, event.value)
            is NoteEditorEvent.ChapterTitleChanged -> chapterTitleChanged(event.blockId, event.value)
            is NoteEditorEvent.ChapterDescriptionChanged -> chapterDescriptionChanged(event.blockId, event.value)
            is NoteEditorEvent.TagsChanged -> tagsChanged(event.value)
            is NoteEditorEvent.AddBlock -> addBlock(event.type)
            is NoteEditorEvent.RemoveBlock -> removeBlock(event.blockId)
            is NoteEditorEvent.MoveBlock -> moveBlock(event.blockId, event.direction)
            NoteEditorEvent.Save -> save()
            NoteEditorEvent.RestoreLastSaved -> restoreLastSaved()
        }
    }

    private fun titleChanged(value: String) {
        val current = _uiState.value
        val document = if (value.isBlank()) current.document else current.document.copy(title = value)
        markDirty(current.copy(document = document, draftTitle = value))
    }

    private fun blockTextChanged(blockId: String, value: String) {
        val current = _uiState.value
        val block = current.document.blocks.firstOrNull { it.id == blockId } ?: return
        val document = if (value.isBlank()) {
            current.document
        } else {
            current.document.replaceBlock(blockId) { it.copy(text = value) }
        }
        val drafts = if (value.isBlank()) {
            current.draftBlockTexts + (blockId to value)
        } else {
            current.draftBlockTexts - blockId
        }
        markDirty(current.copy(document = document, draftBlockTexts = drafts))
    }

    private fun chapterTitleChanged(blockId: String, value: String) {
        val current = _uiState.value
        val document = if (value.isBlank()) {
            current.document
        } else {
            current.document.replaceBlock(blockId) { it.copy(title = value) }
        }
        val drafts = if (value.isBlank()) {
            current.draftChapterTitles + (blockId to value)
        } else {
            current.draftChapterTitles - blockId
        }
        markDirty(current.copy(document = document, draftChapterTitles = drafts))
    }

    private fun chapterDescriptionChanged(blockId: String, value: String) {
        val current = _uiState.value
        if (current.document.blocks.none { it.id == blockId && it.type == NoteBlockType.CHAPTER }) return
        markDirty(current.copy(document = current.document.replaceBlock(blockId) { it.copy(text = value) }))
    }

    private fun tagsChanged(value: String) {
        val current = _uiState.value
        val tags = value.split(',', '，')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val existing = current.document.blocks.firstOrNull { it.type == NoteBlockType.TAG_LIST }
        val blocks = current.document.blocks.filterNot { it.type == NoteBlockType.TAG_LIST }.toMutableList()
        if (tags.isNotEmpty()) {
            blocks += (existing?.copy(tags = tags) ?: NoteBlock(
                id = newBlockId(),
                type = NoteBlockType.TAG_LIST,
                origin = NoteBlockOrigin.USER,
                tags = tags,
            ))
        }
        markDirty(current.copy(document = current.document.copy(blocks = blocks, tags = tags), tagsInput = value))
    }

    private fun addBlock(type: NoteBlockType) {
        if (type == NoteBlockType.TAG_LIST) return
        val current = _uiState.value
        val block = when (type) {
            NoteBlockType.SUMMARY -> NoteBlock(newBlockId(), type, NoteBlockOrigin.USER, text = "待补充")
            NoteBlockType.KEY_POINT -> NoteBlock(newBlockId(), type, NoteBlockOrigin.USER, text = "新要点")
            NoteBlockType.USER_NOTE -> NoteBlock(newBlockId(), type, NoteBlockOrigin.USER, text = "新想法")
            NoteBlockType.CHAPTER -> {
                val endMs = current.document.source.durationMs.coerceAtLeast(1_000L)
                NoteBlock(
                    id = newBlockId(), type = type, origin = NoteBlockOrigin.USER,
                    title = "新章节", startMs = 0L, endMs = endMs,
                    sourceRef = NoteSourceRef(current.document.source.canonicalUrl, 0L, endMs),
                )
            }
            NoteBlockType.TAG_LIST -> error("tag blocks are managed by tagsChanged")
        }
        markDirty(current.copy(document = current.document.copy(blocks = current.document.blocks + block)))
    }

    private fun removeBlock(blockId: String) {
        val current = _uiState.value
        val removed = current.document.blocks.firstOrNull { it.id == blockId } ?: return
        val document = if (removed.type == NoteBlockType.TAG_LIST) {
            current.document.copy(
                blocks = current.document.blocks.filterNot { it.id == blockId },
                tags = emptyList(),
            )
        } else {
            current.document.copy(blocks = current.document.blocks.filterNot { it.id == blockId })
        }
        markDirty(current.copy(
            document = document,
            draftBlockTexts = current.draftBlockTexts - blockId,
            draftChapterTitles = current.draftChapterTitles - blockId,
            tagsInput = if (removed.type == NoteBlockType.TAG_LIST) "" else current.tagsInput,
        ))
    }

    private fun moveBlock(blockId: String, direction: NoteEditorEvent.Direction) {
        val current = _uiState.value
        val index = current.document.blocks.indexOfFirst { it.id == blockId }
        if (index < 0) return
        val target = when (direction) {
            NoteEditorEvent.Direction.UP -> index - 1
            NoteEditorEvent.Direction.DOWN -> index + 1
        }
        if (target !in current.document.blocks.indices) return
        val blocks = current.document.blocks.toMutableList()
        val moved = blocks.removeAt(index)
        blocks.add(target, moved)
        markDirty(current.copy(document = current.document.copy(blocks = blocks)))
    }

    private fun save() {
        val current = _uiState.value
        if (current.saveState == NoteEditorSaveState.SAVING) return
        val document = materialize(current)
        _uiState.value = current.copy(
            document = document,
            draftTitle = document.title,
            draftBlockTexts = current.draftBlockTexts.filterKeys { id -> document.blocks.any { it.id == id } },
            draftChapterTitles = current.draftChapterTitles.filterKeys { id -> document.blocks.any { it.id == id } },
            saveState = NoteEditorSaveState.SAVING,
            errorMessage = null,
        )
        viewModelScope.launch {
            val result = runCatching { withContext(ioDispatcher) { repository.save(document) } }
            result.fold(
                onSuccess = { saveResult -> applySaveResult(document, saveResult) },
                onFailure = { error ->
                    val current = _uiState.value
                    _uiState.value = current.copy(
                        saveState = NoteEditorSaveState.FAILED,
                        errorMessage = error.message ?: error::class.java.simpleName,
                    )
                },
            )
        }
    }

    private fun applySaveResult(savedDocument: NoteDocument, result: NoteDocumentSaveResult) {
        val current = _uiState.value
        if (hasDraftChangedSince(current, savedDocument)) {
            _uiState.value = current.copy(
                saveState = if (result.structuredPersisted) {
                    NoteEditorSaveState.DIRTY
                } else {
                    NoteEditorSaveState.FAILED
                },
                errorMessage = result.error,
            )
            return
        }
        val saveState = when {
            result.isComplete -> NoteEditorSaveState.SAVED
            result.structuredPersisted -> NoteEditorSaveState.PROJECTION_PENDING
            else -> NoteEditorSaveState.FAILED
        }
        val document = if (result.structuredPersisted) {
            result.document
        } else {
            current.document.copy(
                editing = current.document.editing.copy(
                    dirty = true,
                    lastSaveError = result.error,
                ),
            )
        }
        _uiState.value = current.copy(
            document = document,
            saveState = saveState,
            errorMessage = result.error,
            draftTitle = document.title,
        )
    }

    private fun hasDraftChangedSince(current: NoteEditorUiState, savedDocument: NoteDocument): Boolean =
            current.draftTitle != savedDocument.title ||
            current.draftBlockTexts.isNotEmpty() ||
            current.draftChapterTitles.isNotEmpty() ||
            editorContentFingerprint(current.document) != editorContentFingerprint(savedDocument)

    private fun editorContentFingerprint(document: NoteDocument): String = document.copy(
        editing = NoteEditingState(),
        publishing = NotePublishingState(),
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    ).toString()

    private fun restoreLastSaved() {
        val current = _uiState.value
        viewModelScope.launch {
            val saved = withContext(ioDispatcher) {
                repository.find(current.document.cardId, current.document.generation.cardVersion)
            } ?: initial
            _uiState.value = NoteEditorUiState(saved)
        }
    }

    private fun materialize(state: NoteEditorUiState): NoteDocument {
        val blocks = state.document.blocks.mapNotNull { block ->
            val text = state.draftBlockTexts[block.id] ?: block.text
            when {
                block.type == NoteBlockType.KEY_POINT && text.isBlank() -> null
                block.type == NoteBlockType.USER_NOTE && text.isBlank() -> null
                block.type == NoteBlockType.SUMMARY -> block.copy(text = text.ifBlank { "待补充" })
                block.type == NoteBlockType.CHAPTER -> block.copy(
                    title = (state.draftChapterTitles[block.id] ?: block.title).ifBlank { "未命名章节" },
                    text = text,
                )
                else -> block.copy(text = text)
            }
        }
        return state.document.copy(
            title = state.draftTitle.trim().ifBlank { "未命名笔记" },
            blocks = blocks,
            editing = state.document.editing.copy(dirty = true, lastSaveError = null),
        )
    }

    private fun markDirty(state: NoteEditorUiState) {
        _uiState.value = state.copy(
            document = state.document.copy(
                editing = state.document.editing.copy(dirty = true, lastSaveError = null),
            ),
            saveState = NoteEditorSaveState.DIRTY,
            errorMessage = null,
        )
    }

    private fun newBlockId(): String = "user-${UUID.randomUUID()}"
}

private fun NoteDocument.replaceBlock(blockId: String, transform: (NoteBlock) -> NoteBlock): NoteDocument =
    copy(blocks = blocks.map { if (it.id == blockId) transform(it) else it })

class NoteEditorViewModelFactory(
    private val initialDocument: NoteDocument,
    private val repository: NoteDocumentRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(NoteEditorViewModel::class.java)) {
            "Unsupported ViewModel: ${modelClass.name}"
        }
        return NoteEditorViewModel(initialDocument, repository) as T
    }
}
