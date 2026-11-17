package com.unarchive.android.ui.state

import com.unarchive.android.card.KnowledgeSyncRecord
import com.unarchive.android.card.KnowledgeSyncState
import com.unarchive.android.card.NoteBlockType
import com.unarchive.android.card.NoteDocument

internal const val COMPACT_NOTE_PREVIEW_CODE_POINTS = 160

internal enum class CompactNoteStatus(val label: String, val isError: Boolean = false) {
    SAVE_FAILED("保存失败", true),
    UNSAVED("未保存"),
    SYNC_ABNORMAL("同步异常", true),
    SYNC_FAILED("同步失败", true),
    SYNC_RETRY("待重试", true),
    SYNC_BLOCKED("同步受阻", true),
    SYNCING("同步中"),
    SAVED("已保存"),
}

/** A display projection only; local persistence and sync records remain unchanged. */
internal fun NoteDocument.compactStatus(syncRecords: List<KnowledgeSyncRecord> = emptyList(), formalRevision: Long? = null): CompactNoteStatus {
    if (formalRevision == null && editing.lastSaveError != null) return CompactNoteStatus.SAVE_FAILED
    if (formalRevision == null && editing.dirty) return CompactNoteStatus.UNSAVED

    val current = syncRecords.asSequence()
        .filter {
            it.key.cardId == cardId && it.key.cardVersion == generation.cardVersion &&
                it.key.contentRevision == (formalRevision ?: editing.contentRevision)
        }
        .groupBy(KnowledgeSyncRecord::key)
        .values
        .map { attempts ->
            // Persisted keys normally have a single record. For duplicated input,
            // newest wins; equal timestamps choose the conservative display state.
            attempts.maxWith(compareBy<KnowledgeSyncRecord> { it.updatedAtEpochMs }
                .thenBy { it.state.displayTiePriority() })
        }
    val abnormal = current.filter { it.state in abnormalSyncStates }
    if (abnormal.size > 1) return CompactNoteStatus.SYNC_ABNORMAL
    abnormal.singleOrNull()?.let {
        return when (it.state) {
            KnowledgeSyncState.RETRYABLE_FAILURE -> CompactNoteStatus.SYNC_RETRY
            KnowledgeSyncState.PERMANENT_FAILURE -> CompactNoteStatus.SYNC_FAILED
            else -> CompactNoteStatus.SYNC_BLOCKED
        }
    }
    return if (current.any { it.state in activeSyncStates }) CompactNoteStatus.SYNCING else CompactNoteStatus.SAVED
}

private val abnormalSyncStates = setOf(
    KnowledgeSyncState.RETRYABLE_FAILURE, KnowledgeSyncState.PERMANENT_FAILURE, KnowledgeSyncState.BLOCKED,
)

private val activeSyncStates = setOf(
    KnowledgeSyncState.CREATING, KnowledgeSyncState.CREATED, KnowledgeSyncState.ASSOCIATING,
)

private fun KnowledgeSyncState.displayTiePriority(): Int = when (this) {
    KnowledgeSyncState.NOT_SYNCED -> 0
    KnowledgeSyncState.SYNCED -> 1
    KnowledgeSyncState.CREATING -> 2
    KnowledgeSyncState.CREATED -> 3
    KnowledgeSyncState.ASSOCIATING -> 4
    KnowledgeSyncState.RETRYABLE_FAILURE -> 5
    KnowledgeSyncState.BLOCKED -> 6
    KnowledgeSyncState.PERMANENT_FAILURE -> 7
}

internal fun NoteDocument.compactSummaryPreview(): String {
    val summary = blocks.firstOrNull { it.type == NoteBlockType.SUMMARY && it.text.isNotBlank() }?.text
        ?: blocks.firstOrNull {
            (it.type == NoteBlockType.KEY_POINT || it.type == NoteBlockType.USER_NOTE) && it.text.isNotBlank()
        }?.text
    val preview = if (summary != null) summary.compactNotePreview()
    else compactPreview(sourceTranscript.asSequence().map { it.text })
    return preview.ifEmpty { "暂无摘要" }
}

internal fun String.compactNotePreview(): String = compactPreview(sequenceOf(this))

/** Reads at most one normalized code point beyond the bound, without joining a transcript. */
private fun compactPreview(parts: Sequence<String>): String {
    val result = StringBuilder(COMPACT_NOTE_PREVIEW_CODE_POINTS + 2)
    var codePoints = 0
    var pendingSpace = false

    fun truncated(): String {
        val end = result.offsetByCodePoints(0, COMPACT_NOTE_PREVIEW_CODE_POINTS - 1)
        return result.substring(0, end).trimEnd() + "…"
    }

    for (part in parts) {
        var offset = 0
        while (offset < part.length) {
            val codePoint = part.codePointAt(offset)
            offset += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = codePoints > 0
                continue
            }
            if (pendingSpace) {
                result.append(' ')
                codePoints++
                pendingSpace = false
                if (codePoints > COMPACT_NOTE_PREVIEW_CODE_POINTS) return truncated()
            }
            result.appendCodePoint(codePoint)
            codePoints++
            if (codePoints > COMPACT_NOTE_PREVIEW_CODE_POINTS) return truncated()
        }
        pendingSpace = codePoints > 0
    }
    return result.toString()
}
