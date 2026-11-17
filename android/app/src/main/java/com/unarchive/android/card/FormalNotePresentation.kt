package com.unarchive.android.card

import com.unarchive.android.editor.projection.DocumentTitleResolver
import com.unarchive.android.editor.session.MarkdownDocumentParser

/** Read-only list data derived only from a complete formal revision. */
data class FormalNotePresentation(
    val cardId: KnowledgeCardId,
    val cardVersion: String,
    val formalRevision: Long,
    val title: String,
    val summary: String,
    val updatedAtEpochMs: Long,
    val assets: List<CardAsset>,
) {
    companion object {
        /** Call on a worker. It never reads recovery data or writes v2 projection files. */
        fun from(content: NoteContent): FormalNotePresentation {
            val parser = MarkdownDocumentParser()
            val projection = MarkdownDocumentParser.project(content.markdown, parser.parse(content.markdown))
            val titleNode = projection.nodes.firstOrNull { it.node.headingLevel == 1 && it.node.text.isNotBlank() }
            val title = DocumentTitleResolver.resolve(projection, content.structuredMetadata.title)
            val body = projection.nodes.asSequence().filter { !it.node.hidden && it !== titleNode }
                .joinToString(" ") { it.node.text }
            val text = body.lineSequence()
                .filterNot { it.matches(Regex(" {0,3}(?:`{3,}|~{3,}).*")) || it.matches(Regex(" *\\[[^]]+]:.*")) }
                .map { line ->
                    inlineText(line.replace(Regex("^ {0,3}#{1,6} +|^ *> *|^ *[-+*] +(?:\\[[ xX]] +)?|^ *\\d+[.)] +"), ""))
                }.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            val end = text.offsetByCodePoints(0, minOf(160, text.codePointCount(0, text.length)))
            return FormalNotePresentation(
                content.cardId, content.cardVersion, content.markdownRevision, title, text.substring(0, end),
                content.updatedAtEpochMs, content.structuredMetadata.assets.toList(),
            )
        }

        private fun inlineText(value: String): String = value
            .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("!?\\[([^]]*)]\\[[^]]*]"), "$1")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace("**", "").replace("__", "").replace("~~", "")
            .replace(Regex("(?<!\\\\)[*_]"), "")
    }
}
