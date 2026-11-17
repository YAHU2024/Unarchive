package com.unarchive.editorkernelprobe.source

import androidx.compose.ui.text.TextRange
import java.io.File
import kotlin.system.measureNanoTime

/** Model evidence for history payload bounds; this is not an Android heap benchmark. */
fun main(args: Array<String>) {
    val directory = File(args.single()).apply { mkdirs() }
    val checks = mutableListOf<Pair<String, Boolean>>()
    fun verify(name: String, action: () -> Unit) {
        checks += name to runCatching(action).isSuccess
    }
    fun edit(start: Int, old: String, new: String, before: TextRange = TextRange(start), after: TextRange = TextRange(start + new.length)) =
        SourceHistoryEdit(start, old, new, before, after)
    fun undo(history: BoundedSourceHistory, source: String): SourceHistoryReplay =
        checkNotNull(history.prepareUndo(source)).also { check(history.commit(it)) }
    fun redo(history: BoundedSourceHistory, source: String): SourceHistoryReplay =
        checkNotNull(history.prepareRedo(source)).also { check(history.commit(it)) }

    verify("replacement-and-reversed-selection-round-trip") {
        val history = BoundedSourceHistory()
        val original = "Alpha first\r\n\r\nBeta second\r\n"
        val change = edit(6, "first\r\n\r\nBeta", "joined", TextRange(22, 6), TextRange(12))
        val changed = original.replaceRange(change.start, change.start + change.removed.length, change.inserted)
        check(history.record(change) == SourceHistoryRecordResult.RECORDED)
        val undone = undo(history, changed)
        check(undone.sourceAfter == original && undone.selection == TextRange(22, 6))
        val redone = redo(history, undone.sourceAfter)
        check(redone.sourceAfter == changed && redone.selection == TextRange(12))
    }
    verify("unicode-emoji-payload-round-trip") {
        val history = BoundedSourceHistory()
        val original = "中文📝尾部\r\n"
        val changed = "中文😀新增尾部\r\n"
        history.record(edit(2, "📝", "😀新增"))
        check(undo(history, changed).sourceAfter == original)
        check(redo(history, original).sourceAfter == changed)
    }
    verify("invalid-surrogate-payload-rejected") {
        check(runCatching { SourceHistoryEdit(0, "", "\uD83D", TextRange(0), TextRange(1)) }.isFailure)
    }
    verify("split-surrogate-current-range-rejected") {
        val history = BoundedSourceHistory()
        history.record(edit(1, "", "x"))
        check(history.prepareRedo("📝") == null)
        check(history.prepareUndo("📝") == null)
        check(history.stats.undoCount == 1 && history.stats.redoCount == 0)
    }
    verify("no-op-does-not-clear-redo-or-retain-entry") {
        val history = BoundedSourceHistory()
        history.record(edit(0, "", "x"))
        undo(history, "x")
        val before = history.stats
        check(history.record(edit(0, "same", "same")) == SourceHistoryRecordResult.NO_CHANGE)
        check(history.stats == before && history.canRedo)
    }
    verify("new-edit-clears-redo-and-accounting") {
        val history = BoundedSourceHistory()
        history.record(edit(0, "", "a"))
        history.record(edit(1, "", "bb"))
        undo(history, "abb")
        check(history.canRedo)
        history.record(edit(1, "", "c"))
        check(!history.canRedo && history.stats.retainedCount == 2)
        check(history.stats.retainedPayloadCharacters == 2L)
        check(undo(history, "ac").sourceAfter == "a")
    }
    verify("combined-undo-redo-count-stays-bounded") {
        val history = BoundedSourceHistory(maximumEntries = 3)
        var source = ""
        repeat(8) {
            history.record(edit(source.length, "", "$it"))
            source += "$it"
        }
        check(history.stats.undoCount == 3 && history.stats.evictedTransactions == 5L)
        repeat(3) { source = undo(history, source).sourceAfter }
        check(source == "01234" && !history.canUndo && history.stats.redoCount == 3)
        repeat(3) { source = redo(history, source).sourceAfter }
        check(source == "01234567" && history.stats.retainedCount == 3)
    }
    verify("byte-budget-evicts-before-count-limit") {
        val transactionCharge = edit(0, "", "字").retainedByteCharge
        val history = BoundedSourceHistory(maximumEntries = 100, maximumRetainedByteCharge = transactionCharge * 4)
        var source = ""
        repeat(10) {
            history.record(edit(source.length, "", "字"))
            source += "字"
        }
        check(history.stats.undoCount == 4 && history.stats.retainedByteCharge == transactionCharge * 4)
        repeat(4) { source = undo(history, source).sourceAfter }
        check(source == "字".repeat(6) && !history.canUndo)
        check(history.stats.retainedByteCharge == transactionCharge * 4)
    }
    verify("oversized-edit-is-explicit-history-barrier") {
        val history = BoundedSourceHistory(maximumEntries = 20, maximumRetainedByteCharge = 512)
        history.record(edit(0, "", "before"))
        val result = history.record(edit(6, "", "巨".repeat(1_000)))
        check(result == SourceHistoryRecordResult.OVERSIZED_BARRIER)
        check(!history.canUndo && !history.canRedo && history.stats.retainedByteCharge == 0L)
        check(history.stats.oversizedBarriers == 1L)
        val source = "before" + "巨".repeat(1_000)
        history.record(edit(source.length, "", "after"))
        check(undo(history, source + "after").sourceAfter == source)
        check(!history.canUndo)
    }
    verify("large-paste-within-budget-undo-redo") {
        val history = BoundedSourceHistory(maximumEntries = 10, maximumRetainedByteCharge = 128 * 1024L)
        val original = "前段\r\n\r\n后段\r\n"
        val insertion = "截图说明与中文😀\n".repeat(1_000)
        val changed = original.replaceRange(2, 2, insertion)
        history.record(edit(2, "", insertion))
        check(undo(history, changed).sourceAfter == original)
        check(redo(history, original).sourceAfter == changed)
        check(history.stats.retainedByteCharge < 128 * 1024L)
    }
    verify("expected-source-mismatch-leaves-history-intact") {
        val history = BoundedSourceHistory()
        history.record(edit(0, "old", "new"))
        val before = history.stats
        check(history.prepareUndo("other") == null && history.stats == before)
        check(history.prepareUndo("ne") == null && history.stats == before)
    }
    verify("prepare-is-nonmutating-and-commit-is-single-use") {
        val history = BoundedSourceHistory()
        history.record(edit(0, "", "a"))
        val plan = checkNotNull(history.prepareUndo("a"))
        check(history.canUndo && !history.canRedo)
        check(history.commit(plan) && !history.commit(plan))
        check(!history.canUndo && history.canRedo)
    }
    verify("intervening-record-invalidates-replay-plan") {
        val history = BoundedSourceHistory()
        history.record(edit(0, "", "a"))
        val plan = checkNotNull(history.prepareUndo("a"))
        history.record(edit(1, "", "b"))
        check(!history.commit(plan) && history.stats.undoCount == 2)
        check(undo(history, "ab").sourceAfter == "a")
    }
    verify("plan-from-another-history-is-rejected") {
        val first = BoundedSourceHistory()
        val second = BoundedSourceHistory()
        first.record(edit(0, "", "a"))
        second.record(edit(0, "", "a"))
        val plan = checkNotNull(first.prepareUndo("a"))
        check(!second.commit(plan) && second.canUndo && !second.canRedo)
        check(first.commit(plan))
    }
    verify("clear-invalidates-prepared-plan-and-zeroes-storage") {
        val history = BoundedSourceHistory()
        history.record(edit(0, "", "x"))
        val plan = checkNotNull(history.prepareUndo("x"))
        history.clear()
        check(!history.commit(plan) && history.stats.retainedCount == 0 && history.stats.retainedByteCharge == 0L)
    }

    val longSource = buildString {
        append("# 十万码点历史探针\r\n\r\n")
        var index = 0
        while (length < 114_000) append("段落 ${index++}：中文、English、emoji 📝 与 CRLF。\r\n\r\n")
        append("HISTORY_TAIL_7F2\r\n")
    }
    check(longSource.codePointCount(0, longSource.length) >= 100_000)
    var retainedCount = 0
    var retainedByteCharge = 0L
    var retainedPayload = 0L
    var evictedTransactions = 0L
    var updateTotalMs = 0.0
    var replayTotalMs = 0.0
    verify("four-thousand-edits-on-long-source-bounded-and-exact") {
        val history = BoundedSourceHistory(maximumEntries = 128, maximumRetainedByteCharge = 16 * 1024L)
        var current = longSource
        var expectedAfterEviction = ""
        updateTotalMs = measureNanoTime {
            repeat(4_000) { index ->
                if (index == 4_000 - 128) expectedAfterEviction = current
                val insertion = "字${index % 10}"
                val at = when (index % 3) { 0 -> 0; 1 -> current.length / 2; else -> current.length }
                // Avoid splitting the emoji pair in this mixed-placement stress test.
                val safeAt = if (at > 0 && at < current.length && current[at - 1].isHighSurrogate() && current[at].isLowSurrogate()) at + 1 else at
                history.record(edit(safeAt, "", insertion))
                current = current.replaceRange(safeAt, safeAt, insertion)
                check(history.stats.retainedCount <= 128)
                check(history.stats.retainedByteCharge <= 16 * 1024L)
            }
        } / 1_000_000.0
        val final = current
        check(history.stats.undoCount == 128)
        replayTotalMs = measureNanoTime {
            repeat(128) { current = undo(history, current).sourceAfter }
            check(current == expectedAfterEviction && !history.canUndo)
            repeat(128) { current = redo(history, current).sourceAfter }
            check(current == final && !history.canRedo)
        } / 1_000_000.0
        retainedCount = history.stats.retainedCount
        retainedByteCharge = history.stats.retainedByteCharge
        retainedPayload = history.stats.retainedPayloadCharacters
        evictedTransactions = history.stats.evictedTransactions
        check(retainedPayload == 256L)
        // A snapshot implementation would retain >14 million code units here;
        // this object's payload consists only of the final 128 two-character edits.
    }

    File(directory, "history-report.json").writeText(buildString {
        appendLine("{")
        appendLine("  \"allPassed\":${checks.all { it.second }},\"passed\":${checks.count { it.second }},\"total\":${checks.size},")
        appendLine("  \"checks\":[${checks.joinToString(",") { "{\"name\":\"${it.first}\",\"passed\":${it.second}}" }}],")
        appendLine("  \"longSourceCodePoints\":${longSource.codePointCount(0, longSource.length)},\"longSourceUtf16Units\":${longSource.length},\"longSourceUtf8Bytes\":${longSource.toByteArray(Charsets.UTF_8).size},")
        appendLine("  \"stressEdits\":4000,\"maximumEntries\":128,\"maximumRetainedByteCharge\":16384,\"retainedEntries\":$retainedCount,\"retainedByteCharge\":$retainedByteCharge,\"retainedPayloadCharacters\":$retainedPayload,\"evictedTransactions\":$evictedTransactions,")
        appendLine("  \"hostUpdateTotalMs\":$updateTotalMs,\"hostUndoRedoTotalMs\":$replayTotalMs,")
        appendLine("  \"byteMeaning\":\"UTF-16 patch payload plus fixed 96-byte per-entry bookkeeping charge; not actual heap measurement\",")
        appendLine("  \"limitations\":\"Current document and temporary replay output allocations are outside retained history; Android memory/performance and candidate integration are separate gates. Outer session owns document versions.\"")
        appendLine("}")
    }, Charsets.UTF_8)
    File(directory, "history-report.md").writeText(buildString {
        appendLine("# Bounded source transaction history probe\n")
        appendLine("${checks.count { it.second }}/${checks.size} checks passed.\n")
        checks.forEach { appendLine("- ${it.first}: ${it.second}") }
        appendLine("\nLong source: ${longSource.codePointCount(0, longSource.length)} code points / ${longSource.length} UTF-16 units / ${longSource.toByteArray(Charsets.UTF_8).size} UTF-8 bytes.")
        appendLine("4,000 accepted insertions across beginning/middle/end; retained $retainedCount entries, $retainedPayload UTF-16 payload characters, $retainedByteCharge accounting bytes; $evictedTransactions older transactions evicted. Last 128 undo and redo return exact text.")
        appendLine("Host source-update total: $updateTotalMs ms; 128 undo + 128 redo total: $replayTotalMs ms. These are exploratory PC model timings, not Android input/frame evidence.")
        appendLine("Byte budget accounts UTF-16 patch payload plus a fixed 96-byte bookkeeping charge. It is not measured heap. This helper retains no whole source or rich tree. Current source and transient replay output are outside history; caller must measure Android allocations and own document versions.")
    }, Charsets.UTF_8)
    println("Bounded source history: ${checks.count { it.second }}/${checks.size}; retained accounting bytes=$retainedByteCharge; payload chars=$retainedPayload")
    checks.filterNot { it.second }.forEach { println("FAIL ${it.first}") }
    check(checks.all { it.second })
}
