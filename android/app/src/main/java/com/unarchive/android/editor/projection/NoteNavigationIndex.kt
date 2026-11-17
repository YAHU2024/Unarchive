package com.unarchive.android.editor.projection

import com.unarchive.android.editor.session.DocumentProjection
import com.unarchive.android.editor.session.MarkdownDocumentParser

data class NoteOutlineEntry(val nodeId: Long, val level: Int, val title: String, val sourceOffset: Int)
data class NoteFindHit(val sourceStart: Int, val sourceEnd: Int, val nodeId: Long)

/** Build/query on a worker. Search uses original string offsets even for case mappings. */
class NoteNavigationIndex(displayProjection: DocumentProjection, val version: Long, source: String? = null) {
    private val projection = if (source != null && displayProjection.nodes.any { it.node.kind == "folded-transcript" })
        MarkdownDocumentParser().let { MarkdownDocumentParser.project(source, it.parse(source)) } else displayProjection
    val outline = projection.nodes.filter { it.node.headingLevel in 1..6 && !it.node.hidden && !it.node.synthetic }
        .map { NoteOutlineEntry(it.node.id, it.node.headingLevel, it.node.text, it.node.start) }

    fun find(query: String): List<NoteFindHit> {
        if (query.isEmpty()) return emptyList()
        return buildList {
            projection.nodes.filterNot { it.node.hidden || it.node.synthetic }.forEach { block ->
                val text = block.node.atomic?.text ?: block.node.text
                var offset = 0
                while (offset <= text.length - query.length) {
                    val found = text.indexOf(query, offset, ignoreCase = true)
                    if (found < 0) break
                    if (block.node.atomic != null) add(NoteFindHit(block.node.start, block.node.end, block.node.id))
                    else {
                        val from = projection.sourceOffset(block.start + found)
                        val to = projection.sourceOffset(block.start + found + query.length, true)
                        add(NoteFindHit(from, to, block.node.id))
                    }
                    offset = found + query.length.coerceAtLeast(1)
                }
                block.node.inlineAtoms.forEach { atom ->
                    var from = 0
                    while (from <= atom.node.text.length - query.length) {
                        val found = atom.node.text.indexOf(query, from, ignoreCase = true)
                        if (found < 0) break
                        add(NoteFindHit(block.node.start + atom.sourceStart, block.node.start + atom.sourceEnd, block.node.id))
                        from = found + query.length.coerceAtLeast(1)
                    }
                }
            }
        }.sortedBy { it.sourceStart }
    }
}
