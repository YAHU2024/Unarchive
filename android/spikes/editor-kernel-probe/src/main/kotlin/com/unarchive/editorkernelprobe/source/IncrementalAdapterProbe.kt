@file:OptIn(com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi::class)

package com.unarchive.editorkernelprobe.source

import androidx.compose.ui.text.TextRange
import java.io.File
import kotlin.system.measureNanoTime

private data class IncrementalCheck(val name: String, val passed: Boolean, val failure: String?)

/** Real parser-work counters accompany exact full-parser oracle checks. */
fun main(args: Array<String>) {
    val directory = File(args.single()).apply { mkdirs() }
    val checks = mutableListOf<IncrementalCheck>()
    val samples = mutableListOf<Pair<String, SourceParseDiagnostics>>()
    val costSamples = mutableListOf<Double>()
    fun verify(name: String, action: () -> Unit) {
        val outcome = runCatching(action)
        checks += IncrementalCheck(name, outcome.isSuccess, outcome.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" })
    }
    fun compareOracle(session: SourcePreservingSession) {
        val oracle = SourcePreservingSession(session.source)
        val fullBlocks = oracle.projectedDocument().blocks
        val localBlocks = session.projectedDocument().blocks
        check(fullBlocks.size == localBlocks.size)
        fullBlocks.indices.forEach { index ->
            if (session.blocks[index].editable) check(fullBlocks[index] == localBlocks[index]) { "Editable structure differs from full-parser oracle" }
            else check(session.source.substring(session.blocks[index].start, session.blocks[index].end) ==
                oracle.source.substring(oracle.blocks[index].start, oracle.blocks[index].end)) { "Protected source differs from full-parser oracle" }
        }
        check(oracle.blocks.map { it.kind to it.editable } == session.blocks.map { it.kind to it.editable })
    }
    fun nativeEdit(session: SourcePreservingSession, at: Int, count: Int, inserted: String) {
        val previous = session.projectedText()
        val changed = previous.replaceRange(at, at + count, inserted)
        val elapsed = measureNanoTime {
            val result = session.acceptProjectedText(changed, TextRange(at + inserted.length))
            check(result is SourceEditResult.Applied) { result.toString() }
        }
        costSamples += elapsed / 1_000_000.0
    }
    val longSource = buildString {
        append("# Long title\r\n\r\n")
        var index = 0
        while (length < 116_000) {
            append("Paragraph ").append(index++).append("：中文 English emoji 📝 and retained text.\r\n\r\n")
        }
        append("TAIL_E2\r\n")
    }
    check(longSource.codePointCount(0, longSource.length) >= 110_275)
    verify("native-load-does-not-materialize-candidate") {
        val session = SourcePreservingSession(longSource)
        check(!session.candidateMaterialized)
        check(session.projectedDocument().blocks.size > 1000)
    }
    for ((name, position) in listOf("head" to 1, "middle" to 700, "tail" to -1)) {
        verify("long-$name-owned-edit-parses-bounded-window") {
            val session = SourcePreservingSession(longSource)
            val index = if (position < 0) session.blocks.lastIndex else position
            val stableId = session.blocks[index].id
            val target = session.blocks[index]
            check(target.editable)
            session.resetParseDiagnostics()
            val at = session.blocks.take(index).sumOf { it.text.length + 1 } + minOf(4, target.text.length)
            nativeEdit(session, at, 0, "字")
            val diagnostic = session.diagnostics
            check(diagnostic.fullParses == 0L && diagnostic.incrementalParses == 1L)
            check(diagnostic.lastParsedCharacters < 500)
            check(diagnostic.lastParsedNodes <= 3)
            check(session.blocks[index].id == stableId)
            check(!session.candidateMaterialized)
            compareOracle(session)
            samples += name to diagnostic
        }
    }
    verify("repeat-200-native-appends-keeps-local-parse") {
        val session = SourcePreservingSession(longSource)
        session.resetParseDiagnostics()
        repeat(200) { nativeEdit(session, session.projectedText().length, 0, "x") }
        check(session.diagnostics.fullParses == 0L)
        check(session.diagnostics.incrementalParses == 200L)
        check(session.diagnostics.lastParsedCharacters < 500)
        check(session.source == longSource.trimEnd('\r', '\n') + "x".repeat(200) + "\r\n")
        check(!session.candidateMaterialized)
        compareOracle(session)
        samples += "append-200" to session.diagnostics
    }
    verify("native-styled-edit-preserves-markers-and-oracle") {
        val source = "# Title\r\n\r\nA __bold__ and _italic_ text.\r\n\r\nAfter.\r\n"
        val session = SourcePreservingSession(source)
        val at = session.projectedText().indexOf("bold") + 2
        nativeEdit(session, at, 0, "o")
        check(session.source == source.replace("bold", "boold"))
        compareOracle(session)
    }
    verify("native-paragraph-end-insertion-targets-previous-block") {
        val session = SourcePreservingSession("First.\n\nSecond.\n")
        nativeEdit(session, "First.".length, 0, "X")
        check(session.source == "First.X\n\nSecond.\n")
        compareOracle(session)
    }
    verify("native-enter-crlf-local-and-exact") {
        val session = SourcePreservingSession("Alpha Beta\r\n\r\nGamma Delta\r\n\r\nTail.\r\n")
        session.resetParseDiagnostics()
        nativeEdit(session, 5, 0, "\n")
        check(session.source == "Alpha\r\n\r\n Beta\r\n\r\nGamma Delta\r\n\r\nTail.\r\n")
        check(session.diagnostics.fullParses == 0L)
        compareOracle(session)
    }
    verify("native-cross-paragraph-replace-local-and-undo-exact") {
        val original = "Alpha first\r\n\r\nBeta second\r\n\r\nGamma third\r\n"
        val session = SourcePreservingSession(original)
        val text = session.projectedText()
        nativeEdit(session, text.indexOf("first"), text.indexOf("second") - text.indexOf("first"), "joined ")
        check(session.source == "Alpha joined second\r\n\r\nGamma third\r\n")
        compareOracle(session)
        check(session.undo() && session.source == original)
        check(session.redo() && session.source == "Alpha joined second\r\n\r\nGamma third\r\n")
    }
    verify("native-emoji-replace-surrogate-safe") {
        val source = "Before 📝 after.\n\nUnchanged.\n"
        val session = SourcePreservingSession(source)
        nativeEdit(session, session.projectedText().indexOf("📝"), 2, "🧠")
        check(session.source == source.replace("📝", "🧠"))
        compareOracle(session)
    }
    verify("native-protected-cross-delete-refused-with-source-intact") {
        val source = "Before.\n\n| A | B |\n| --- | --- |\n| one | two |\n\nAfter.\n"
        val session = SourcePreservingSession(source)
        val text = session.projectedText()
        val result = session.acceptProjectedText(text.replaceRange(0, text.indexOf("After"), "Merged "), TextRange(7))
        check(result is SourceEditResult.Rejected && session.source == source)
    }
    verify("protected-source-panel-is-explicit-full-fallback") {
        val source = "# Keep\n\n[ref]: https://example.com/a\n\nAfter.\n"
        val session = SourcePreservingSession(source)
        val reference = session.blocks.first { !it.editable }
        session.resetParseDiagnostics()
        check(session.replaceSourceBlock(reference.id, source.substring(reference.start, reference.end).replace("/a", "/b")) is SourceEditResult.Applied)
        check(session.diagnostics.fullParses == 1L && session.diagnostics.incrementalParses == 0L)
        check(session.diagnostics.lastFallbackReason == "explicit-source-panel")
        check(session.source == source.replace("/a", "/b"))
        compareOracle(session)
    }
    verify("native-stale-selection-does-not-overwrite") {
        val session = SourcePreservingSession("Before.\n\nAfter.\n")
        val initial = session.version
        nativeEdit(session, 0, 1, "b")
        val current = session.source
        check(session.acceptProjectedText("stale", TextRange(5), initial) is SourceEditResult.Rejected)
        check(session.source == current)
    }
    verify("source-panel-introduced-unclosed-fence-reparses-remote-tail") {
        val source = "First.\n\nSecond.\n\nThird.\n\nFourth.\n"
        val session = SourcePreservingSession(source)
        val target = session.blocks.first()
        session.resetParseDiagnostics()
        check(session.replaceSourceBlock(target.id, "```kotlin") is SourceEditResult.Applied)
        check(session.diagnostics.fullParses == 1L)
        compareOracle(session)
        check(session.blocks.any { !it.editable && session.source.substring(it.start, it.end).contains("Fourth.") })
        check(session.undo() && session.source == source)
        compareOracle(session)
        check(session.redo())
        compareOracle(session)
    }
    for ((name, replacement) in listOf(
        "reference-definition" to "[ref]: https://example.com/new",
        "html-container" to "<div>",
        "unknown-container" to ":::custom",
        "paragraph-removal" to "",
    )) {
        verify("editable-source-panel-$name-full-context-oracle") {
            val source = "# Header\n\nReplace this paragraph.\n\n[ref]\n\nLater paragraph.\n\nEnd paragraph.\n"
            val session = SourcePreservingSession(source)
            val node = session.blocks.first { it.text == "Replace this paragraph." }
            session.resetParseDiagnostics()
            check(session.replaceSourceBlock(node.id, replacement) is SourceEditResult.Applied)
            check(session.diagnostics.fullParses == 1L && session.diagnostics.incrementalParses == 0L)
            check(session.diagnostics.lastFallbackReason == "explicit-source-panel")
            check(session.source == source.replace("Replace this paragraph.", replacement))
            compareOracle(session)
            check(session.undo() && session.source == source)
            check(session.redo() && session.source == source.replace("Replace this paragraph.", replacement))
            compareOracle(session)
        }
    }
    verify("integrated-source-history-bounds-after-300-edits") {
        val original = "First.\n\nSecond 📝.\n"
        val session = SourcePreservingSession(original, historyLimit = 32, historyByteBudget = 4096)
        repeat(300) { nativeEdit(session, session.projectedText().length, 0, "字") }
        check(session.historyStats.retainedCount == 32)
        check(session.historyStats.retainedByteCharge <= 4096)
        val final = session.source
        repeat(32) { check(session.undo()) }
        check(!session.canUndo && session.source == original.trimEnd() + "字".repeat(268) + "\n")
        repeat(32) { check(session.redo()) }
        check(session.source == final && !session.canRedo)
        check(!session.candidateMaterialized)
    }
    verify("native-cut-whole-160-paragraphs-and-paste") {
        val source = buildString { append("# 十万字符长文\n\n"); repeat(300) { append("段落 $it：中文连续写作，English with punctuation，与 emoji 📝。保留末尾和全部文字。\n\n") } }
        val session = SourcePreservingSession(source)
        val original = session.projectedText()
        val first = original.indexOf("段落 0")
        val last = original.indexOf("段落 160")
        val removed = original.substring(first, last)
        val after = original.removeRange(first,last)
        val result=session.acceptProjectedText(after,TextRange(first))
        check(result is SourceEditResult.Applied) { "Cut result: $result" }
        check(session.projectedText()==after)
        val pasted=session.acceptProjectedText(after.substring(0,first)+removed+after.substring(first),TextRange(last))
        check(pasted is SourceEditResult.Applied) { "Paste result: $pasted" }
        check(session.source==source)
    }
    verify("coalesced-native-cut-retains-actual-pre-edit-selection") {
        val source = "First.\n\nSecond.\n"
        val session = SourcePreservingSession(source)
        val original = session.projectedText()
        val end = original.indexOf("Second.")
        val before = TextRange(end, 0)
        check(session.acceptProjectedText(original.substring(end), TextRange(0), beforeSelection = before) is SourceEditResult.Applied)
        check(session.undo() && session.source == source && session.projectedSelection == before)
        check(session.redo() && session.projectedSelection == TextRange(0))
        compareOracle(session)
    }
    verify("native-before-selection-rejects-invalid-unicode-boundary") {
        val source = "A 📝 tail.\n"
        val session = SourcePreservingSession(source)
        val result = session.acceptProjectedText(session.projectedText() + "X", TextRange(11), beforeSelection = TextRange(3))
        check(result is SourceEditResult.Rejected && session.source == source && !session.canUndo)
    }
    verify("shifted-styled-neighbor-remains-editable-and-oracle-equal") {
        val source = "Before.\n\nMiddle.\n\nA __bold__ ending.\n\n<!-- protected -->\n"
        val session = SourcePreservingSession(source)
        nativeEdit(session, 0, 0, "Long ")
        nativeEdit(session, session.projectedText().indexOf("bold") + 2, 0, "o")
        check(session.source == "Long " + source.replace("bold", "boold"))
        compareOracle(session)
    }
    verify("mixed-repeated-native-edits-match-full-oracle") {
        val source = "# Title\r\n\r\nRepeated text.\r\n\r\nRepeated text.\r\n\r\nA __bold__ paragraph.\r\n\r\n| A | B |\r\n| --- | --- |\r\n| x | y |\r\n\r\nTail.\r\n"
        val session = SourcePreservingSession(source)
        repeat(40) { number ->
            val editable = session.blocks.filter { it.editable }
            val target = editable[number % editable.size]
            val index = session.blocks.indexOfFirst { it.id == target.id }
            val at = session.blocks.take(index).sumOf { it.text.length + 1 } + minOf(2, target.text.length)
            nativeEdit(session, at, 0, "字")
            compareOracle(session)
        }
        check(session.diagnostics.fullParses == 1L) // Initial load only.
    }
    fun quoted(value: String?): String = value?.let { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")}\"" } ?: "null"
    File(directory, "incremental-report.json").writeText(buildString {
        appendLine("{\"passed\":${checks.count { it.passed }},\"total\":${checks.size},\"allPassed\":${checks.all { it.passed }},")
        appendLine("\"sourceCodePoints\":${longSource.codePointCount(0, longSource.length)},\"sourceBytes\":${longSource.toByteArray(Charsets.UTF_8).size},")
        appendLine("\"checks\":[${checks.joinToString(",") { "{\"name\":${quoted(it.name)},\"passed\":${it.passed},\"failure\":${quoted(it.failure)}}" }}],")
        appendLine("\"parserSamples\":[${samples.joinToString(",") { (name, d) -> "{\"name\":${quoted(name)},\"fullParses\":${d.fullParses},\"incrementalParses\":${d.incrementalParses},\"parsedCharacters\":${d.parsedCharacters},\"lastParsedCharacters\":${d.lastParsedCharacters},\"lastParsedNodes\":${d.lastParsedNodes}}" }}],")
        appendLine("\"hostNativeEditSamplesMs\":[${costSamples.joinToString(",")}],\"androidPerformanceClaimed\":false}")
    }, Charsets.UTF_8)
    checks.filterNot { it.passed }.forEach { println("FAIL ${it.name}: ${it.failure}") }
    println("Incremental adapter checks: ${checks.count { it.passed }}/${checks.size}; ${samples.size} bounded parser samples")
    check(checks.all { it.passed })
}
