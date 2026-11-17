package com.unarchive.editorkernelprobe

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import java.io.File
import java.util.Locale
import kotlin.system.measureNanoTime

private data class Fixture(val name: String, val markdown: String, val markers: List<String>)

private data class RoundTripEvidence(
    val fixture: String,
    val sourceCharacters: Int,
    val sourceBytes: Int,
    val exactSource: Boolean,
    val markersRetained: Boolean,
    val outputCharacters: Int,
    val loadMs: Double,
    val exportMs: Double,
)

private data class OperationEvidence(val operation: String, val passed: Boolean, val errorType: String?)

private fun fixtures(): List<Fixture> = listOf(
    Fixture("common-formatting", "# Heading\n\nA **bold** and *italic* paragraph.\n\n- first\n- second\n", listOf("Heading", "bold", "italic", "first", "second")),
    Fixture("crlf-and-unicode", "# 标题\r\n\r\n中文与 emoji 📝，保留换行。\r\n", listOf("标题", "中文", "📝")),
    Fixture("front-matter", "---\ntitle: \"Original title\"\ncustom: KEEP_YAML_7E9\n---\n\n# Heading\n\nBody.\n", listOf("KEEP_YAML_7E9", "Heading", "Body")),
    Fixture("reference-links", "# Links\n\nVisit [reference][unique-ref].\n\n[unique-ref]: https://example.com/keep \"KEEP_REF_7E9\"\n", listOf("reference", "https://example.com/keep", "KEEP_REF_7E9")),
    Fixture("list-markers", "# Lists\n\n+ first\n+ second\n\n7. seven\n8. eight\n", listOf("first", "second", "seven", "eight")),
    Fixture("task-list", "# Tasks\n\n- [ ] KEEP_UNCHECKED_7E9\n- [x] KEEP_CHECKED_7E9\n", listOf("KEEP_UNCHECKED_7E9", "KEEP_CHECKED_7E9")),
    Fixture("table", "# Table\n\n| Left | Right |\n| :--- | ---: |\n| KEEP_CELL_7E9 | 42 |\n", listOf("Left", "Right", "KEEP_CELL_7E9", "42")),
    Fixture("code-fence", "# Code\n\n~~~~kotlin\nval value = \"KEEP_CODE_7E9\"\n~~~~\n", listOf("KEEP_CODE_7E9", "kotlin")),
    Fixture("local-image", "# Image\n\n![KEEP_ALT_7E9](assets/gallery-7e9.png)\n", listOf("KEEP_ALT_7E9", "assets/gallery-7e9.png")),
    Fixture("unknown-syntax", "# Unknown\n\n<!-- KEEP_COMMENT_7E9 -->\n\n:::custom KEEP_BLOCK_7E9\nopaque body\n:::\n", listOf("KEEP_COMMENT_7E9", "KEEP_BLOCK_7E9", "opaque body")),
)

@OptIn(ExperimentalRichTextApi::class)
fun main(args: Array<String>) {
    val directory = File(args.single()).apply { mkdirs() }
    check(directory.isDirectory) { "Cannot create report directory" }
    val evidence = fixtures().map { fixture ->
        val state = RichTextState(historyLimit = 20, coalesceWindowMs = 0L)
        val load = measureNanoTime { state.setMarkdown(fixture.markdown) }
        var output = ""
        val export = measureNanoTime { output = state.toMarkdown() }
        RoundTripEvidence(
            fixture.name, fixture.markdown.length, fixture.markdown.toByteArray(Charsets.UTF_8).size,
            fixture.markdown == output, fixture.markers.all(output::contains), output.length,
            load / 1_000_000.0, export / 1_000_000.0,
        )
    }
    val operations = mutableListOf<OperationEvidence>()

    fun probe(name: String, block: () -> Boolean) {
        // Report failure as evidence without stopping independent probes. No note text is logged.
        val result = runCatching(block)
        operations += OperationEvidence(name, result.getOrDefault(false), result.exceptionOrNull()?.javaClass?.simpleName)
    }

    probe("cross-paragraph-replacement") {
        val state = RichTextState(coalesceWindowMs = 0L).setMarkdown("Alpha first\n\nBeta second\n")
        val initial = state.annotatedString.text
        val start = initial.indexOf("first")
        val end = initial.indexOf("second")
        // This candidate represents paragraph separators as spaces in its internal
        // annotated buffer, so use paragraph ranges rather than assuming newlines.
        check(start >= 0 && end > start)
        check(state.annotatedString.paragraphStyles.count { it.start < end && it.end > start } >= 2)
        state.selection = TextRange(start, end)
        state.replaceSelectedText("joined ")
        state.annotatedString.text == initial.replaceRange(start, end, "joined ")
    }
    probe("append-undo-redo") {
        val state = RichTextState(coalesceWindowMs = 0L).setMarkdown("# Heading\n\nInitial paragraph.\n")
        val initial = state.toMarkdown()
        state.selection = TextRange(state.annotatedString.length)
        state.addTextAfterSelection(" Appended text.")
        val appended = state.toMarkdown()
        check(appended != initial && appended.contains("Appended text."))
        check(state.history.canUndo)
        state.history.undo()
        val undoMatches = state.toMarkdown() == initial
        state.history.redo()
        undoMatches && state.toMarkdown() == appended
    }
    probe("grouped-cross-paragraph-one-step-undo-redo") {
        val state = RichTextState(coalesceWindowMs = 0L).setMarkdown("Alpha first\n\nBeta second\n")
        val initial = state.toMarkdown()
        val initialText = state.annotatedString.text
        state.selection = TextRange(initialText.indexOf("first"), initialText.indexOf("second"))
        state.history.group { state.replaceSelectedText("joined ") }
        val replaced = state.toMarkdown()
        check(replaced != initial)
        state.history.undo()
        val undoMatches = state.toMarkdown() == initial
        state.history.redo()
        undoMatches && state.toMarkdown() == replaced
    }
    probe("format-one-step-undo-redo") {
        val state = RichTextState(coalesceWindowMs = 0L).setMarkdown("First paragraph\n\nSecond paragraph\n")
        val initial = state.toMarkdown()
        val initialText = state.annotatedString.text
        state.selection = TextRange(0, "First".length)
        state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        val formatted = state.toMarkdown()
        check(formatted != initial && state.annotatedString.text == initialText)
        state.history.undo()
        val undoMatches = state.toMarkdown() == initial
        state.history.redo()
        undoMatches && state.toMarkdown() == formatted
    }
    probe("set-markdown-resets-history") {
        val state = RichTextState().setMarkdown("Initial")
        state.selection = TextRange(state.annotatedString.length)
        state.addTextAfterSelection(" added")
        check(state.history.canUndo)
        state.setMarkdown("Reloaded")
        !state.history.canUndo && !state.history.canRedo
    }

    // A model-only cost sample. Never present these JVM timings as Android frame measurements.
    val longDocument = buildString {
        append("# Long document\n\n")
        var index = 0
        while (length < 110_000) {
            append("段落 ").append(index++).append(": 连续写作的中文、English 与 emoji 📝，保留完整内容。\n\n")
        }
        append("END_OF_DOCUMENT_7E9\n")
    }
    check(longDocument.codePointCount(0, longDocument.length) >= 100_000)
    val longState = RichTextState(historyLimit = 20, coalesceWindowMs = 0L)
    val longLoad = measureNanoTime { longState.setMarkdown(longDocument) }
    val updateSamples = (1..10).map {
        measureNanoTime {
            longState.selection = TextRange(longState.annotatedString.length)
            longState.addTextAfterSelection(" 新增$it")
        } / 1_000_000.0
    }
    val longTailRetained = longState.toMarkdown().contains("END_OF_DOCUMENT_7E9")
    val allRawSourcePreserved = evidence.all { it.exactSource }
    val modelOperationsPassed = operations.filterNot { it.operation == "set-markdown-resets-history" }.all { it.passed }
    val json = buildString {
        appendLine("{")
        appendLine("  \"candidate\": \"Compose Rich Editor 1.2.0 desktop model\",")
        appendLine("  \"sourceCommit\": \"34e06d6bb8906789522f4eae1ec8bca1b324f1c1\",")
        appendLine("  \"rawSourceGatePassed\": $allRawSourcePreserved,")
        appendLine("  \"modelOperationsPassed\": $modelOperationsPassed,")
        appendLine("  \"roundTrips\": [")
        appendLine(evidence.joinToString(",\n") { e ->
            "    {\"fixture\":\"${e.fixture}\",\"sourceCharacters\":${e.sourceCharacters},\"sourceBytes\":${e.sourceBytes},\"exactSource\":${e.exactSource},\"markersRetained\":${e.markersRetained},\"outputCharacters\":${e.outputCharacters},\"loadMs\":${number(e.loadMs)},\"exportMs\":${number(e.exportMs)}}"
        })
        appendLine("  ],")
        appendLine("  \"operations\": [")
        appendLine(operations.joinToString(",\n") { "    {\"operation\":\"${it.operation}\",\"passed\":${it.passed},\"errorType\":${it.errorType?.let { type -> "\"$type\"" } ?: "null"}}" })
        appendLine("  ],")
        appendLine("  \"longDocument\": {\"sourceUtf16CodeUnits\":${longDocument.length},\"sourceCodePoints\":${longDocument.codePointCount(0, longDocument.length)},\"sourceBytes\":${longDocument.toByteArray(Charsets.UTF_8).size},\"loadMs\":${number(longLoad / 1_000_000.0)},\"tailRetained\":$longTailRetained,\"appendSamplesMs\":[${updateSamples.joinToString(",", transform = ::number)}]},")
        appendLine("  \"androidImeAndFrameGate\": \"not-tested\"")
        appendLine("}")
    }
    File(directory, "report.json").writeText(json, Charsets.UTF_8)
    File(directory, "report.md").writeText(buildString {
        appendLine("# Editor kernel model probe")
        appendLine()
        appendLine("Candidate: Compose Rich Editor 1.2.0, source tag v1.2.0 (34e06d6).")
        appendLine("Runtime: ${System.getProperty("java.version")}; ${System.getProperty("os.name")}; desktop JVM model only.")
        appendLine()
        appendLine("| Fixture | Characters / UTF-8 bytes | Exact source | Markers retained | Load / export ms |")
        appendLine("|---|---|---|---|---|")
        evidence.forEach { appendLine("| ${it.fixture} | ${it.sourceCharacters} / ${it.sourceBytes} | ${it.exactSource} | ${it.markersRetained} | ${number(it.loadMs)} / ${number(it.exportMs)} |") }
        appendLine()
        operations.forEach { appendLine("- ${it.operation}: ${it.passed}${it.errorType?.let { type -> " ($type)" } ?: ""}") }
        appendLine()
        appendLine("Raw-source preservation gate: $allRawSourcePreserved. Model editing operations: $modelOperationsPassed.")
        appendLine("Long fixture: ${longDocument.length} UTF-16 code units, ${longDocument.codePointCount(0, longDocument.length)} code points, ${longDocument.toByteArray(Charsets.UTF_8).size} UTF-8 bytes; tail retained: $longTailRetained.")
        appendLine("Single-run model load: ${number(longLoad / 1_000_000.0)} ms. Ten model append samples: ${updateSamples.joinToString(", ", transform = ::number)} ms.")
        appendLine()
        appendLine("No mounted UI, Android IME, clipboard, selection handles, frame timeline, or APK validation is claimed. Timings are exploratory host samples, not performance acceptance.")
    }, Charsets.UTF_8)
    println("Evidence collected: ${evidence.size} round trips, ${operations.size} operations; rawSourceGate=$allRawSourcePreserved; modelOperations=$modelOperationsPassed; longTail=$longTailRetained")
    println("Report directory: ${directory.absolutePath}")
}

private fun number(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
