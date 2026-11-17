@file:OptIn(com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi::class)

package com.unarchive.editorkernelprobe.source

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import java.io.File

private data class ProbeResult(val name: String, val passed: Boolean, val error: String?)

fun main(args: Array<String>) {
    val directory = File(args.single()).apply { mkdirs() }
    val results = mutableListOf<ProbeResult>()
    fun verify(name: String, block: () -> Unit) {
        val result = runCatching(block)
        results += ProbeResult(name, result.isSuccess, result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" })
    }
    val fixtures = listOf(
        "common-formatting" to "# Heading\n\nA **bold** and *italic* paragraph.\n\n- first\n- second\n",
        "crlf-and-unicode" to "# 标题\r\n\r\n中文与 emoji 📝，保留换行。\r\n",
        "front-matter" to "---\ntitle: \"Original title\"\ncustom: KEEP_YAML_7E9\n---\n\n# Heading\n\nBody.\n",
        "reference-links" to "# Links\n\nVisit [reference][unique-ref].\n\n[unique-ref]: https://example.com/keep \"KEEP_REF_7E9\"\n",
        "list-markers" to "# Lists\n\n+ first\n+ second\n\n7. seven\n8. eight\n",
        "task-list" to "# Tasks\n\n- [ ] KEEP_UNCHECKED_7E9\n- [x] KEEP_CHECKED_7E9\n",
        "table" to "# Table\n\n| Left | Right |\n| :--- | ---: |\n| KEEP_CELL_7E9 | 42 |\n",
        "code-fence" to "# Code\n\n~~~~kotlin\nval value = \"KEEP_CODE_7E9\"\n~~~~\n",
        "local-image" to "# Image\n\n![KEEP_ALT_7E9](assets/gallery-7e9.png)\n",
        "unknown-syntax" to "# Unknown\n\n<!-- KEEP_COMMENT_7E9 -->\n\n:::custom KEEP_BLOCK_7E9\nopaque body\n:::\n",
    )
    fixtures.forEach { (name, source) ->
        verify("no-op-exact-$name") {
            val session = SourcePreservingSession(source)
            check(session.acceptCandidate() is SourceEditResult.Unchanged)
            check(session.source == source && session.version == 0L && !session.canUndo)
        }
    }
    verify("candidate-plain-text-edit-preserves-other-source") {
        val source = "# Title\n\nAlpha first.\n\n~~~kotlin\nval keep = 1\n~~~\n"
        val session = SourcePreservingSession(source)
        edit(session, "first", "second")
        check(session.source == source.replace("first", "second"))
    }
    verify("candidate-styled-text-edit-keeps-original-delimiters") {
        val source = "# Title\n\nA __bold__ and _italic_ text.\n\n[ref]: https://example.com/keep\n"
        val session = SourcePreservingSession(source)
        val at = session.state.annotatedString.text.indexOf("bold") + 2
        session.state.selection = TextRange(at)
        session.state.addTextAfterSelection("o")
        check(session.acceptCandidate() is SourceEditResult.Applied)
        check(session.source == source.replace("bold", "boold"))
    }
    verify("candidate-atx-heading-text-preserves-prefix") {
        val source = "### Title\n\nBody.\n"
        val session = SourcePreservingSession(source)
        edit(session, "Title", "Updated")
        check(session.source == source.replace("Title", "Updated"))
    }
    verify("candidate-bold-format-is-source-range-transaction") {
        val source = "Plain target text.\n\n[ref]: https://example.com/keep \"label\"\n"
        val session = SourcePreservingSession(source)
        select(session, "target")
        session.state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        check(session.acceptCandidate() is SourceEditResult.Applied)
        check(session.source == source.replace("target", "**target**"))
        check(session.undo() && session.source == source)
        check(session.redo() && session.source == source.replace("target", "**target**"))
    }
    verify("candidate-remove-bold-keeps-outside-source") {
        val source = "A __target__ text.\n\n# Keep\n"
        val session = SourcePreservingSession(source)
        select(session, "target")
        session.state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        check(session.acceptCandidate() is SourceEditResult.Applied)
        check(session.source == source.replace("__target__", "target"))
    }
    verify("candidate-cross-paragraph-replacement-exact") {
        val source = "Alpha first\r\n\r\nBeta second\r\n\r\n# Keep\r\n"
        val session = SourcePreservingSession(source)
        val initial = session.state.annotatedString.text
        session.state.selection = TextRange(initial.indexOf("first"), initial.indexOf("second"))
        session.state.replaceSelectedText("joined ")
        check(session.acceptCandidate() is SourceEditResult.Applied)
        check(session.source == "Alpha joined second\r\n\r\n# Keep\r\n")
        check(session.undo() && session.source == source)
        check(session.redo() && session.source == "Alpha joined second\r\n\r\n# Keep\r\n")
    }
    verify("candidate-crlf-unicode-source-mapping") {
        val source = "# 标题\r\n\r\n中文 📝 与文本。\r\n\r\n[ref]: https://example.com/keep\r\n"
        val session = SourcePreservingSession(source)
        edit(session, "📝", "🧠")
        check(session.source == source.replace("📝", "🧠"))
        val block = session.blocks.indexOfFirst { it.text.contains("🧠") }
        check(session.sourceOffset(block, session.blocks[block].text.indexOf("🧠")) == session.source.indexOf("🧠"))
    }
    verify("split-surrogate-is-rejected") {
        val source = "Body 📝 tail.\n"
        val session = SourcePreservingSession(source)
        val index = session.state.annotatedString.text.indexOf("📝")
        session.state.selection = TextRange(index, index + 1)
        session.state.replaceSelectedText("")
        check(session.acceptCandidate() is SourceEditResult.Rejected && session.source == source)
    }
    verify("stable-node-id-and-offset-after-prefix-edit") {
        val session = SourcePreservingSession("First paragraph.\n\nSecond paragraph.\n")
        val id = session.blocks.last().id
        edit(session, "First", "Longer first")
        check(session.blocks.last().id == id)
        val index = session.blocks.indexOfFirst { it.id == id }
        check(session.sourceOffset(index, 0) == session.source.indexOf("Second"))
    }
    verify("stale-generation-does-not-overwrite") {
        val session = SourcePreservingSession("First paragraph.\n")
        val initialVersion = session.version
        edit(session, "First", "Updated")
        val accepted = session.source
        select(session, "Updated")
        session.state.replaceSelectedText("Stale")
        check(session.acceptCandidate(initialVersion) == SourceEditResult.Rejected("stale-version"))
        check(session.source == accepted && session.state.annotatedString.text.contains("Updated"))
    }
    verify("protected-front-matter-and-table-edit-rejected") {
        val source = "---\ncustom: keep\n---\n\n# Title\n\n| A | B |\n| --- | --- |\n| a | b |\n"
        val session = SourcePreservingSession(source)
        val block = session.blocks.indexOfFirst { !it.editable }
        session.state.selection = TextRange(session.candidateBufferOffset(block, 0), session.candidateBufferOffset(block, session.blocks[block].text.length))
        session.state.replaceSelectedText("changed")
        check(session.acceptCandidate() is SourceEditResult.Rejected && session.source == source)
    }
    verify("cross-protected-node-rejected-with-source-intact") {
        val source = "Before paragraph.\n\n| A | B |\n| --- | --- |\n| a | b |\n\nAfter paragraph.\n"
        val session = SourcePreservingSession(source)
        val value = session.state.annotatedString.text
        session.state.selection = TextRange(value.indexOf("Before"), value.indexOf("After"))
        session.state.replaceSelectedText("merged ")
        check(session.acceptCandidate() is SourceEditResult.Rejected && session.source == source)
    }
    verify("supported-edit-keeps-all-opaque-fixtures") {
        val source = fixtures.drop(2).joinToString("\n") { it.second } + "\nEditable paragraph.\n"
        val session = SourcePreservingSession(source)
        edit(session, "Editable", "Updated")
        check(session.source == source.replace("Editable", "Updated"))
    }
    verify("source-undo-redo-exact-generations-monotonic") {
        val source = "# 标题\r\n\r\nA __bold__ paragraph.\r\n\r\n<!-- keep -->\r\n"
        val session = SourcePreservingSession(source)
        val at = session.state.annotatedString.text.indexOf("bold") + 2
        session.state.selection = TextRange(at)
        session.state.addTextAfterSelection("o")
        check(session.acceptCandidate() is SourceEditResult.Applied)
        val first = session.source
        edit(session, "paragraph", "section")
        val second = session.source
        val version = session.version
        check(session.undo() && session.source == first)
        check(session.undo() && session.source == source && session.version > version)
        check(session.redo() && session.source == first)
        check(session.redo() && session.source == second)
    }
    verify("continuous-insertion-preserves-state-and-selection") {
        val session = SourcePreservingSession("中文正文。\n")
        val identity = session.state
        repeat(12) {
            session.state.selection = TextRange(session.state.annotatedString.length)
            session.state.addTextAfterSelection("字")
            val selection = session.state.selection
            check(session.acceptCandidate() is SourceEditResult.Applied)
            check(session.state === identity && session.state.selection == selection)
        }
        check(session.source == "中文正文。" + "字".repeat(12) + "\n")
    }
    verify("source-panel-table-edit-shares-history-and-keeps-outside-source") {
        val source = "# Keep\r\n\r\n| A | B |\r\n| --- | --- |\r\n| old | b |\r\n\r\n~~~kotlin\r\nval keep = 1\r\n~~~\r\n"
        val session = SourcePreservingSession(source)
        val table = session.blocks.first { it.kind == "table" }
        val raw = source.substring(table.start, table.end)
        check(session.replaceSourceBlock(table.id, raw.replace("old", "new")) is SourceEditResult.Applied)
        check(session.source == source.replace("old", "new"))
        check(session.blocks.first { it.kind == "table" }.id == table.id)
        check(session.undo() && session.source == source)
        check(session.redo() && session.source == source.replace("old", "new"))
    }
    verify("source-panel-stale-result-does-not-change-source") {
        val session = SourcePreservingSession("# Keep\n\n<!-- original -->\n")
        val comment = session.blocks.first { !it.editable }
        val originalVersion = session.version
        edit(session, "Keep", "Updated")
        val updated = session.source
        check(session.replaceSourceBlock(comment.id, "<!-- stale -->", originalVersion) is SourceEditResult.Rejected)
        check(session.source == updated)
    }
    verify("candidate-enter-splits-paragraph-with-crlf-retained") {
        val source = "Alpha Beta\r\n\r\n# Keep\r\n"
        val session = SourcePreservingSession(source)
        session.state.selection = TextRange("Alpha".length)
        session.state.addTextAfterSelection("\n")
        val result = session.acceptCandidate()
        check(result is SourceEditResult.Applied) { result.toString() }
        check(session.source == "Alpha\r\n\r\n Beta\r\n\r\n# Keep\r\n")
    }
    verify("empty-source-can-begin-writing") {
        val session = SourcePreservingSession("")
        session.state.addTextAfterSelection("开始写作")
        check(session.acceptCandidate() is SourceEditResult.Applied && session.source == "开始写作")
    }
    verify("combined-styled-word-replacement-is-explicitly-rejected") {
        // The candidate public API removes the old bold run on complete replacement.
        // This adapter does not guess how to combine a text diff with style removal.
        val source = "A __bold__ paragraph.\n"
        val session = SourcePreservingSession(source)
        select(session, "bold")
        session.state.replaceSelectedText("replacement")
        check(session.acceptCandidate() == SourceEditResult.Rejected("projection-mismatch"))
        check(session.source == source && !session.canUndo)
        check(session.state.annotatedString.text.contains("bold"))
    }
    verify("replacement-followed-by-new-edit-clears-redo") {
        val session = SourcePreservingSession("First paragraph.\n")
        edit(session, "First", "Updated")
        check(session.undo() && session.canRedo)
        edit(session, "First", "Fresh")
        check(!session.canRedo && !session.redo())
    }
    fun quoted(value: String?): String = value?.let { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")}\"" } ?: "null"
    File(directory, "adapter-report.json").writeText(buildString {
        appendLine("{\"scope\":\"source-owned paragraphs/headings and limited inline marks; protected fallback\",")
        appendLine("\"passed\":${results.count { it.passed }},\"total\":${results.size},\"allPassed\":${results.all { it.passed }},\"checks\":[")
        appendLine(results.joinToString(",\n") { "{\"name\":${quoted(it.name)},\"passed\":${it.passed},\"error\":${quoted(it.error)}}" })
        appendLine("]}")
    }, Charsets.UTF_8)
    File(directory, "adapter-report.md").writeText(buildString {
        appendLine("# Source-preserving candidate bridge probe\n")
        appendLine("${results.count { it.passed }}/${results.size} checks passed. Candidate edits are read from actual RichTextState snapshots and mapped to original source ranges. Persistence never uses full-document toMarkdown.\n")
        results.forEach { appendLine("- ${it.name}: ${it.passed}${it.error?.let { value -> " ($value)" } ?: ""}") }
        appendLine("\nBounded capability: paragraphs, ATX headings and limited inline marks. Lists, images, tables, links/reference definitions, fences, unknown extensions and multiline paragraphs use protected source-only nodes. Crossing protected nodes is rejected. This does not establish complete editor features, incremental parsing, Android IME or frame performance.")
    }, Charsets.UTF_8)
    results.filterNot { it.passed }.forEach { println("FAIL ${it.name}: ${it.error}") }
    println("Source adapter checks: ${results.count { it.passed }}/${results.size}; reports: ${directory.absolutePath}")
    check(results.all { it.passed }) { "Source adapter probe failed; inspect bounded evidence" }
}

private fun select(session: SourcePreservingSession, text: String) {
    val start = session.state.annotatedString.text.indexOf(text)
    check(start >= 0) { "Synthetic selection marker missing" }
    session.state.selection = TextRange(start, start + text.length)
}

private fun edit(session: SourcePreservingSession, old: String, replacement: String) {
    select(session, old)
    session.state.replaceSelectedText(replacement)
    check(session.acceptCandidate() is SourceEditResult.Applied) { "Candidate edit rejected: ${session.lastResult}" }
}
