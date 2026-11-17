package com.unarchive.editorkernelprobe.review

import androidx.compose.ui.text.TextRange
import com.unarchive.editorkernelprobe.source.SourceEditResult
import com.unarchive.editorkernelprobe.source.SourcePreservingSession
import java.io.File

/** Independent range-identity and protected-source checks after the adapter author's suite. */
fun main(args: Array<String>) {
    val checks = mutableListOf<Pair<String, Boolean>>()
    fun verify(name: String, action: () -> Unit) {
        checks += name to runCatching(action).isSuccess
    }
    verify("second-identical-paragraph-only") {
        val original = "same text.\r\n\r\nsame text.\r\n"
        val session = SourcePreservingSession(original)
        val start = session.candidateBufferOffset(1, 5)
        session.state.selection = TextRange(start, start + 4)
        session.state.replaceSelectedText("line")
        check(session.acceptCandidate() is SourceEditResult.Applied)
        check(session.source == "same text.\r\n\r\nsame line.\r\n")
        check(session.undo() && session.source == original)
        check(session.redo() && session.source == "same text.\r\n\r\nsame line.\r\n")
    }
    verify("unclosed-front-matter-unchanged") {
        val original = "---\r\ncustom: KEEP_UNCLOSED\r\n"
        val session = SourcePreservingSession(original)
        check(session.acceptCandidate() is SourceEditResult.Unchanged)
        check(session.source == original)
    }
    verify("protected-node-identity-survives-prefix-growth") {
        val original = "Prefix.\n\n| A | B |\n| --- | --- |\n| one | two |\n\nAfter.\n"
        val session = SourcePreservingSession(original)
        val protectedId = session.blocks.first { !it.editable }.id
        session.state.selection = TextRange(0, "Prefix".length)
        session.state.replaceSelectedText("Longer prefix")
        check(session.acceptCandidate() is SourceEditResult.Applied)
        check(session.source == original.replaceFirst("Prefix", "Longer prefix"))
        check(session.blocks.first { !it.editable }.id == protectedId)
    }
    val directory = File(args.single()).apply { mkdirs() }
    File(directory, "review-checks.json").writeText(
        "{\"allPassed\":${checks.all { it.second }},\"checks\":[" +
            checks.joinToString(",") { "{\"name\":\"${it.first}\",\"passed\":${it.second}}" } + "]}",
        Charsets.UTF_8,
    )
    println("Independent adapter checks: ${checks.count { it.second }}/${checks.size}")
    checks.filterNot { it.second }.forEach { println("FAIL ${it.first}") }
    check(checks.all { it.second })
}
