package com.unarchive.editorkernelprobe.source

import androidx.compose.ui.text.TextRange

/** One source transaction; selections are in the editor's projected coordinate space.
 * No original document, parsed node list, or candidate rich-text tree is retained.
 */
data class SourceHistoryEdit(
    val start: Int,
    val removed: String,
    val inserted: String,
    val beforeSelection: TextRange,
    val afterSelection: TextRange,
    val requiresFullContext: Boolean = false,
) {
    init {
        require(start >= 0)
        require(beforeSelection.min >= 0 && afterSelection.min >= 0)
        require(wellFormed(removed) && wellFormed(inserted)) { "History payload must have well-formed UTF-16" }
    }

    /** Explicit accounting charge, not an assertion about ART/JVM object sizes.
     * Includes UTF-16 payload plus a fixed transaction/selection bookkeeping charge.
     */
    val retainedByteCharge: Long
        get() = (removed.length.toLong() + inserted.length.toLong()) * 2L + METADATA_BYTE_CHARGE

    companion object {
        const val METADATA_BYTE_CHARGE = 96L

        private fun wellFormed(text: String): Boolean {
            var index = 0
            while (index < text.length) {
                val char = text[index]
                if (char.isLowSurrogate()) return false
                if (char.isHighSurrogate()) {
                    if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) return false
                    index++
                }
                index++
            }
            return true
        }
    }
}

enum class SourceHistoryRecordResult {
    RECORDED,
    NO_CHANGE,
    /** The edit remains valid, but undo cannot cross this boundary without violating the budget. */
    OVERSIZED_BARRIER,
}

/** A transient replay plan. Prepare does not mutate history; the caller can reparse first.
 * [sourceAfter] is returned to the caller and is never retained by [BoundedSourceHistory].
 */
class SourceHistoryReplay internal constructor(
    val sourceAfter: String,
    val selection: TextRange,
    val start: Int,
    val end: Int,
    val replacement: String,
    val requiresFullContext: Boolean,
    internal val ticket: Long,
    internal val entryId: Long,
    internal val undo: Boolean,
    internal val owner: Any,
)

data class SourceHistoryStats(
    val undoCount: Int,
    val redoCount: Int,
    val retainedByteCharge: Long,
    val retainedPayloadCharacters: Long,
    val evictedTransactions: Long,
    val oversizedBarriers: Long,
) {
    val retainedCount: Int get() = undoCount + redoCount
}

/** Bounded, inverse-operation history for the source-owned editor prototype.
 *
 * Both undo and redo share one count and byte budget. A new accepted edit discards
 * redo; oldest undo entries are evicted until both bounds hold. If a single edit
 * exceeds the budget, all history is cleared and [SourceHistoryRecordResult.OVERSIZED_BARRIER]
 * is returned. This prevents an undo chain from skipping the unrecorded edit.
 *
 * Byte charges describe retained payload/bookkeeping accounting, not measured heap.
 * Current document storage, layout/candidate state and temporary replay Strings are
 * outside this history object and must be measured separately on Android.
 */
class BoundedSourceHistory(
    val maximumEntries: Int = 100,
    val maximumRetainedByteCharge: Long = 256L * 1024L,
) {
    init {
        require(maximumEntries >= 1)
        require(maximumRetainedByteCharge >= SourceHistoryEdit.METADATA_BYTE_CHARGE)
    }

    private data class Entry(val id: Long, val edit: SourceHistoryEdit)

    private val undoEntries = ArrayDeque<Entry>()
    private val redoEntries = ArrayDeque<Entry>()
    private val owner = Any()
    private var retainedCharge = 0L
    private var retainedCharacters = 0L
    private var nextEntryId = 1L
    private var epoch = 0L
    private var evicted = 0L
    private var barriers = 0L

    val canUndo: Boolean get() = undoEntries.isNotEmpty()
    val canRedo: Boolean get() = redoEntries.isNotEmpty()
    val stats: SourceHistoryStats
        get() = SourceHistoryStats(undoEntries.size, redoEntries.size, retainedCharge, retainedCharacters, evicted, barriers)

    fun record(edit: SourceHistoryEdit): SourceHistoryRecordResult {
        if (edit.removed == edit.inserted) return SourceHistoryRecordResult.NO_CHANGE
        clearRedo()
        epoch++
        if (edit.retainedByteCharge > maximumRetainedByteCharge) {
            undoEntries.clear()
            redoEntries.clear()
            retainedCharge = 0L
            retainedCharacters = 0L
            barriers++
            return SourceHistoryRecordResult.OVERSIZED_BARRIER
        }
        val entry = Entry(nextEntryId++, edit)
        undoEntries.addLast(entry)
        retain(entry)
        while (undoEntries.size + redoEntries.size > maximumEntries || retainedCharge > maximumRetainedByteCharge) {
            discard(undoEntries.removeFirst())
            evicted++
        }
        return SourceHistoryRecordResult.RECORDED
    }

    fun prepareUndo(currentSource: String): SourceHistoryReplay? =
        undoEntries.lastOrNull()?.let { prepare(it, currentSource, undo = true) }

    fun prepareRedo(currentSource: String): SourceHistoryReplay? =
        redoEntries.lastOrNull()?.let { prepare(it, currentSource, undo = false) }

    /** Commits a prepared plan only if no record/clear/replay has intervened. */
    fun commit(replay: SourceHistoryReplay): Boolean {
        if (replay.owner !== owner || replay.ticket != epoch) return false
        val from = if (replay.undo) undoEntries else redoEntries
        val to = if (replay.undo) redoEntries else undoEntries
        if (from.lastOrNull()?.id != replay.entryId) return false
        to.addLast(from.removeLast())
        epoch++
        return true
    }

    fun clear() {
        undoEntries.clear()
        redoEntries.clear()
        retainedCharge = 0L
        retainedCharacters = 0L
        epoch++
    }

    private fun prepare(entry: Entry, source: String, undo: Boolean): SourceHistoryReplay? {
        val edit = entry.edit
        val expected = if (undo) edit.inserted else edit.removed
        val replacement = if (undo) edit.removed else edit.inserted
        val endLong = edit.start.toLong() + expected.length.toLong()
        if (endLong > source.length.toLong()) return null
        val end = endLong.toInt()
        if (!isBoundary(source, edit.start) || !isBoundary(source, end)) return null
        if (!source.regionMatches(edit.start, expected, 0, expected.length)) return null
        val output = source.replaceRange(edit.start, end, replacement)
        return SourceHistoryReplay(
            output,
            if (undo) edit.beforeSelection else edit.afterSelection,
            edit.start,
            end,
            replacement,
            edit.requiresFullContext,
            epoch,
            entry.id,
            undo,
            owner,
        )
    }

    private fun clearRedo() {
        while (redoEntries.isNotEmpty()) discard(redoEntries.removeFirst())
    }

    private fun retain(entry: Entry) {
        retainedCharge += entry.edit.retainedByteCharge
        retainedCharacters += entry.edit.removed.length.toLong() + entry.edit.inserted.length.toLong()
    }

    private fun discard(entry: Entry) {
        retainedCharge -= entry.edit.retainedByteCharge
        retainedCharacters -= entry.edit.removed.length.toLong() + entry.edit.inserted.length.toLong()
    }

    private fun isBoundary(text: String, offset: Int): Boolean = offset in 0..text.length &&
        !(offset > 0 && offset < text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())
}
