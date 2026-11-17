package com.unarchive.android.editor.session

enum class EditOrigin { INPUT, FORMAT, PASTE, CUT, SOURCE, IMAGE, AI, UNDO, REDO }

data class HistoryEntry(
    val patch: SourceReplacement, val removed: String,
    val before: DocumentSelection, val after: DocumentSelection,
    val origin: EditOrigin, val fullContext: Boolean,
    val inputGroup: Long = 0,
    val timestampMs: Long = System.nanoTime() / 1_000_000L,
    /** Null means no asset mutation; normal typing retains no manifest copies. */
    val assetIdsBefore: Set<String>? = null,
    val assetIdsAfter: Set<String>? = null,
) {
    val byteCharge: Long get() = (removed.length.toLong() + patch.replacement.length) * 2 + 112 +
        (assetIdsBefore.orEmpty().sumOf { it.length.toLong() * 2 + 32 } + assetIdsAfter.orEmpty().sumOf { it.length.toLong() * 2 + 32 })
}

data class DocumentHistoryStats(val undoCount: Int, val redoCount: Int, val retainedBytes: Long, val evicted: Long)

/** Range inverses only, sharing one byte/count budget across undo and redo. */
class DocumentHistory(val maximumEntries: Int = 128, val maximumBytes: Long = 256 * 1024L) {
    init { require(maximumEntries > 0 && maximumBytes >= 112) }
    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private var charge = 0L
    private var evicted = 0L
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val stats get() = DocumentHistoryStats(undoStack.size, redoStack.size, charge, evicted)
    fun wouldExceedSingleEntry(entry: HistoryEntry): Boolean = entry.byteCharge > maximumBytes
    fun record(entry: HistoryEntry, allowBarrier: Boolean = false): Boolean {
        if (wouldExceedSingleEntry(entry)) {
            if (!allowBarrier) return false
            clear(); return true
        }
        while (redoStack.isNotEmpty()) charge -= redoStack.removeLast().byteCharge
        val previous = undoStack.lastOrNull()
        // An input shortcut rewrites the literal prefix just typed. Keep trigger and conversion
        // in the same inverse, including a prefix which existed before the final space.
        if (previous != null && previous.origin == EditOrigin.INPUT && entry.origin == EditOrigin.INPUT &&
            previous.assetIdsBefore == null && entry.assetIdsBefore == null &&
            previous.inputGroup == entry.inputGroup && entry.timestampMs - previous.timestampMs in 0..1_000 &&
            entry.patch.start <= previous.patch.start &&
            entry.patch.end >= previous.patch.start + previous.patch.replacement.length &&
            entry.removed.isNotEmpty() && !previous.fullContext && !entry.fullContext) {
            val relative = previous.patch.start - entry.patch.start
            if (relative + previous.patch.replacement.length <= entry.removed.length &&
                entry.removed.regionMatches(relative, previous.patch.replacement, 0, previous.patch.replacement.length)) {
                val removed = entry.removed.replaceRange(relative, relative + previous.patch.replacement.length, previous.removed)
                val merged = entry.copy(patch = entry.patch.copy(end = entry.patch.start + removed.length), removed = removed, before = previous.before)
                if (merged.byteCharge <= maximumBytes) {
                    undoStack.removeLast(); charge -= previous.byteCharge
                    undoStack += merged; charge += merged.byteCharge
                    return true
                }
            }
        }
        if (previous != null && previous.origin == EditOrigin.INPUT && entry.origin == EditOrigin.INPUT &&
            previous.assetIdsBefore == null && entry.assetIdsBefore == null &&
            previous.inputGroup == entry.inputGroup && entry.timestampMs - previous.timestampMs in 0..1_000 &&
            previous.after == entry.before && previous.after.isCollapsed && entry.before.isCollapsed &&
            previous.removed.isEmpty() && entry.removed.isEmpty() &&
            previous.patch.start + previous.patch.replacement.length == entry.patch.start &&
            '\n' !in previous.patch.replacement && '\n' !in entry.patch.replacement &&
            !previous.fullContext && !entry.fullContext) {
            val merged = previous.copy(patch = previous.patch.copy(replacement = previous.patch.replacement + entry.patch.replacement),
                after = entry.after, timestampMs = entry.timestampMs)
            if (merged.byteCharge <= maximumBytes) {
                undoStack.removeLast(); charge -= previous.byteCharge
                undoStack += merged; charge += merged.byteCharge
                return true
            }
        }
        undoStack += entry; charge += entry.byteCharge
        while (undoStack.size + redoStack.size > maximumEntries || charge > maximumBytes) {
            charge -= undoStack.removeFirst().byteCharge; evicted++
        }
        return true
    }
    fun peekUndo(): HistoryEntry? = undoStack.lastOrNull()
    fun peekRedo(): HistoryEntry? = redoStack.lastOrNull()
    fun commitUndo(entry: HistoryEntry): Boolean {
        if (undoStack.lastOrNull() !== entry) return false
        redoStack += undoStack.removeLast(); return true
    }
    fun commitRedo(entry: HistoryEntry): Boolean {
        if (redoStack.lastOrNull() !== entry) return false
        undoStack += redoStack.removeLast(); return true
    }
    fun clear() { undoStack.clear(); redoStack.clear(); charge = 0 }
}
