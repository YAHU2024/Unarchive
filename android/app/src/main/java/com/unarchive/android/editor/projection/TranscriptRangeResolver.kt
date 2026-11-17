package com.unarchive.android.editor.projection

import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteMarkdownProjection
import com.unarchive.android.editor.session.DocumentProjection
import com.unarchive.android.editor.session.MarkdownDocumentParser

data class TranscriptSourceRange(val start: Int, val end: Int, val title: String, val paragraphCount: Int)

/** Collapses only a uniquely identified unchanged generated transcript, never a heading alone. */
object TranscriptRangeResolver {
    fun resolve(source: String, projection: DocumentProjection, metadata: NoteDocument): TranscriptSourceRange? {
        if (metadata.sourceTranscript.isEmpty()) return null
        val candidates = projection.nodes.filter { it.node.headingLevel == 2 && it.node.text.trim() == "转录全文" }
        if (candidates.size != 1) return null
        val canonical = NoteMarkdownProjection.render(metadata)
        val parser = MarkdownDocumentParser()
        val expected = MarkdownDocumentParser.project(canonical, parser.parse(canonical))
        val heading = expected.nodes.singleOrNull { it.node.headingLevel == 2 && it.node.text.trim() == "转录全文" } ?: return null
        val expectedEnd = expected.nodes.firstOrNull { it.node.start > heading.node.start && it.node.headingLevel in 1..2 }?.node?.start ?: canonical.length
        val actual = candidates.single()
        val actualEnd = projection.nodes.firstOrNull { it.node.start > actual.node.start && it.node.headingLevel in 1..2 }?.node?.start ?: source.length
        fun normalized(value: String) = value.replace("\r\n", "\n").trimEnd()
        val expectedText = normalized(canonical.substring(heading.node.start, expectedEnd))
        val actualText = normalized(source.substring(actual.node.start, actualEnd))
        if (actualText != expectedText) return null
        return TranscriptSourceRange(actual.node.start, actualEnd, "转录全文", metadata.sourceTranscript.size)
    }
}
