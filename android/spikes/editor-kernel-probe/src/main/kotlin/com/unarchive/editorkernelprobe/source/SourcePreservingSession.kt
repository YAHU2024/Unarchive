@file:OptIn(com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi::class)

package com.unarchive.editorkernelprobe.source

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.document.RichTextBlock
import com.mohamedrejeb.richeditor.document.RichTextDocument
import com.mohamedrejeb.richeditor.document.RichTextSpanMark
import com.mohamedrejeb.richeditor.model.RichTextState
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Evidence prototype: original Markdown owns persistence; candidate owns the active IME buffer.
 *
 * Unsupported source nodes are explicit protected projections, never passed through the
 * candidate Markdown codec. This bounded adapter currently edits paragraphs/ATX headings,
 * inline bold/italic/strike/code text and adds/removes formatting markers. Lists, tables,
 * reference links/definitions, images, fences and unknown extensions require source mode.
 * Owned edits reparse complete affected blocks plus one neighbor on each side. Changes
 * to protected/source-only nodes use an explicit full-context fallback. Native continuous
 * buffers can use acceptProjectedText without materializing a candidate RichTextState.
 */
class SourcePreservingSession(initialSource: String, historyLimit: Int = 100, historyByteBudget: Long = 256L * 1024L) {
    private val candidateState = lazy {
        RichTextState(historyLimit = 0).apply { setRichTextDocument(acceptedDocument, acceptedSelection) }
    }
    val state: RichTextState get() = candidateState.value
    var source: String = initialSource
        private set
    var version: Long = 0L
        private set
    var lastResult: SourceEditResult = SourceEditResult.Unchanged
        private set

    private var nextId = 1L
    private var parseDiagnostics = SourceParseDiagnostics()
    private var nodes: List<SourceNode> = parseFull(initialSource, "initial-load")
    private var acceptedDocument = projection(nodes)
    private var acceptedSelection = TextRange(0)
    private val history = BoundedSourceHistory(historyLimit, historyByteBudget)

    init {
        require(historyLimit >= 1)
    }

    val blocks: List<SourceBlockInfo>
        get() = nodes.map { SourceBlockInfo(it.id, it.kind, it.start, it.end, it.editable, it.block.text) }
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
    val historyStats: SourceHistoryStats get() = history.stats
    val diagnostics: SourceParseDiagnostics get() = parseDiagnostics
    val candidateMaterialized: Boolean get() = candidateState.isInitialized()

    fun projectedDocument(): RichTextDocument = acceptedDocument
    fun projectedText(): String = logicalText(acceptedDocument)
    val projectedSelection: TextRange get() = acceptedSelection
    fun resetParseDiagnostics() { parseDiagnostics = SourceParseDiagnostics() }

    /** Native editor route: one continuous projected buffer with real paragraph breaks.
     * Source mappings are still owned by this session. No whole-document codec is used.
     * Native composing updates must be held by the view until composition commits.
     */
    fun acceptProjectedText(text: String, selection: TextRange, expectedVersion: Long = version, beforeSelection: TextRange? = null): SourceEditResult {
        if (expectedVersion != version) return SourceEditResult.Rejected("stale-version").also { lastResult = it }
        if (!wellFormedUtf16(text) || !isBoundary(text, selection.start) || !isBoundary(text, selection.end)) {
            return SourceEditResult.Rejected("unicode-boundary").also { lastResult = it }
        }
        val old = logicalText(acceptedDocument)
        if (beforeSelection != null && (!isBoundary(old, beforeSelection.start) || !isBoundary(old, beforeSelection.end))) {
            return SourceEditResult.Rejected("unicode-boundary").also { lastResult = it }
        }
        if (old == text) {
            acceptedSelection = selection
            return SourceEditResult.Unchanged.also { lastResult = it }
        }
        val patch = textPatch(old, text) ?: return SourceEditResult.Rejected("unsupported-or-protected-edit").also { lastResult = it }
        val updated = source.replaceRange(patch.start, patch.end, patch.replacement)
        val updatedNodes = parseUpdated(updated, patch)
        val proposed = projection(updatedNodes)
        if (logicalText(proposed) != text) return SourceEditResult.Rejected("projection-mismatch").also { lastResult = it }
        recordHistory(patch, beforeSelection ?: acceptedSelection, selection)
        source = updated
        nodes = updatedNodes
        acceptedDocument = proposed
        acceptedSelection = selection
        if (candidateState.isInitialized()) state.setRichTextDocument(acceptedDocument, acceptedSelection)
        version += 1L
        return SourceEditResult.Applied(version, patch.start, patch.end, patch.replacement.length).also { lastResult = it }
    }

    /** Offset in the actual candidate TextField buffer, whose paragraph separator is a space. */
    fun candidateBufferOffset(blockIndex: Int, textOffset: Int): Int {
        require(blockIndex in nodes.indices)
        require(textOffset in 0..nodes[blockIndex].block.text.length)
        return nodes.take(blockIndex).sumOf { it.block.text.length + 1 } + textOffset
    }

    fun sourceOffset(blockIndex: Int, textOffset: Int): Int? =
        nodes.getOrNull(blockIndex)?.sourceBoundary(textOffset)

    /** Explicit source-panel transaction, separate from rich-text edits. The panel owns one
     * versioned node, not caller-supplied global offsets, and shares the source undo history.
     */
    fun replaceSourceBlock(nodeId: Long, replacement: String, expectedVersion: Long = version): SourceEditResult {
        if (expectedVersion != version) return SourceEditResult.Rejected("stale-version").also { lastResult = it }
        if (!wellFormedUtf16(replacement)) return SourceEditResult.Rejected("unicode-boundary").also { lastResult = it }
        val node = nodes.firstOrNull { it.id == nodeId }
            ?: return SourceEditResult.Rejected("missing-node").also { lastResult = it }
        if (node.raw == replacement) return SourceEditResult.Unchanged.also { lastResult = it }
        val patch = SourcePatch(node.start, node.end, replacement)
        val updated = source.replaceRange(patch.start, patch.end, patch.replacement)
        // Arbitrary source text can introduce an unclosed fence, definition or
        // container even if the original block was editable. Context is global.
        val updatedNodes = parseFull(updated, "explicit-source-panel", nodes, patch)
        recordHistory(patch, acceptedSelection, TextRange(0), requiresFullContext = true)
        source = updated
        nodes = updatedNodes
        acceptedDocument = projection(nodes)
        acceptedSelection = TextRange(0)
        if (candidateState.isInitialized()) state.setRichTextDocument(acceptedDocument, acceptedSelection)
        version += 1L
        return SourceEditResult.Applied(version, patch.start, patch.end, replacement.length).also { lastResult = it }
    }

    /** Reads the candidate's actual structural snapshot; callers do not provide source offsets.
     * No re-import occurs on accepted input, preserving the candidate IME composition/selection.
     */
    fun acceptCandidate(expectedVersion: Long = version): SourceEditResult {
        if (expectedVersion != version) return reject("stale-version")
        val proposed = state.toRichTextDocument()
        if (proposed.blocks.any { !wellFormedUtf16(it.text) }) return reject("unicode-boundary")
        if (proposed == acceptedDocument) {
            acceptedSelection = state.selection
            return SourceEditResult.Unchanged.also { lastResult = it }
        }
        val oldText = logicalText(acceptedDocument)
        val newText = logicalText(proposed)
        val patch = if (oldText != newText) textPatch(oldText, newText) else formatPatch(proposed)
        if (patch == null) return reject("unsupported-or-protected-edit")
        if (!isBoundary(source, patch.start) || !isBoundary(source, patch.end)) return reject("unicode-boundary")
        val updated = source.replaceRange(patch.start, patch.end, patch.replacement)
        val updatedNodes = parseUpdated(updated, patch)
        // A source edit is accepted only if reparsing gives the candidate's intended structure.
        // This catches ambiguous syntax, changed block structure and style boundary mismatches.
        if (projection(updatedNodes) != proposed) return reject("projection-mismatch")
        recordHistory(patch, acceptedSelection, state.selection)
        source = updated
        nodes = updatedNodes
        acceptedDocument = proposed
        acceptedSelection = state.selection
        version += 1L
        return SourceEditResult.Applied(version, patch.start, patch.end, patch.replacement.length)
            .also { lastResult = it }
    }

    fun undo(): Boolean {
        return replay(history.prepareUndo(source))
    }

    fun redo(): Boolean {
        return replay(history.prepareRedo(source))
    }

    private fun replay(plan: SourceHistoryReplay?): Boolean {
        if (plan == null) return false
        val patch = SourcePatch(plan.start, plan.end, plan.replacement)
        val updatedNodes = if (plan.requiresFullContext) parseFull(plan.sourceAfter, "source-history-context", nodes, patch)
            else parseUpdated(plan.sourceAfter, patch)
        if (!history.commit(plan)) return false
        source = plan.sourceAfter
        nodes = updatedNodes
        acceptedDocument = projection(nodes)
        acceptedSelection = plan.selection
        if (candidateState.isInitialized()) state.setRichTextDocument(acceptedDocument, acceptedSelection)
        version += 1L // An undo never makes an old asynchronous result current again.
        lastResult = SourceEditResult.Unchanged
        return true
    }

    private fun recordHistory(patch: SourcePatch, beforeSelection: TextRange, afterSelection: TextRange, requiresFullContext: Boolean = false) {
        history.record(SourceHistoryEdit(patch.start, source.substring(patch.start, patch.end), patch.replacement,
            beforeSelection, afterSelection, requiresFullContext))
    }

    private fun reject(reason: String): SourceEditResult {
        if (candidateState.isInitialized()) state.setRichTextDocument(acceptedDocument, acceptedSelection)
        return SourceEditResult.Rejected(reason).also { lastResult = it }
    }

    private fun textPatch(old: String, new: String): SourcePatch? {
        val diff = changedRange(old, new)
        val first = locate(diff.oldStart, endAffinity = false) ?: return null
        val last = locate(diff.oldEnd, endAffinity = true) ?: return null
        if (first.first > last.first) return null
        val affected = nodes.subList(first.first, last.first + 1)
        if (affected.any { !it.editable }) return null
        if (affected.size > 1 && affected.any { it.block.headingLevel != 0 || it.block.spans.isNotEmpty() }) return null
        val start = nodes[first.first].sourceBoundary(first.second) ?: return null
        val end = nodes[last.first].sourceBoundary(last.second) ?: return null
        if (start > end) return null
        val lineBreak = if (source.contains("\r\n")) "\r\n" else "\n"
        val inserted = new.substring(diff.newStart, diff.newEnd)
        val replacement = escapePlain(inserted).replace("\n", lineBreak + lineBreak)
        // Do not mistake a text change plus arbitrary formatting operation for ordinary typing.
        // The exact reprojection comparison in acceptCandidate verifies the intended marks.
        return SourcePatch(start, end, replacement)
    }

    private fun formatPatch(proposed: RichTextDocument): SourcePatch? {
        if (proposed.blocks.size != nodes.size) return null
        val changes = proposed.blocks.indices.filter { proposed.blocks[it] != nodes[it].block }
        if (changes.size != 1) return null
        val index = changes.single()
        val node = nodes[index]
        val old = node.block
        val new = proposed.blocks[index]
        if (!node.editable || old.text != new.text || old.type != new.type) return null
        if (old.headingLevel != new.headingLevel && old.copy(headingLevel = new.headingLevel) == new) {
            val content = node.sourceBoundary(0) ?: return null
            val prefix = if (new.headingLevel == 0) "" else "#".repeat(new.headingLevel) + " "
            return SourcePatch(node.start, content, prefix)
        }
        if (old.headingLevel != new.headingLevel) return null
        val added = new.spans.filterNot(old.spans::contains)
        val removed = old.spans.filterNot(new.spans::contains)
        if (added.size == 1 && removed.isEmpty()) {
            val mark = added.single()
            val delimiter = delimiter(mark) ?: return null
            val start = node.sourceBoundary(mark.range.first) ?: return null
            val end = node.sourceBoundary(mark.range.last + 1) ?: return null
            return SourcePatch(start, end, delimiter + source.substring(start, end) + delimiter)
        }
        if (removed.size == 1 && added.isEmpty()) {
            val mark = removed.single()
            val marker = node.markers.firstOrNull { it.mark == mark } ?: return null
            return SourcePatch(marker.start + node.coordinateShift, marker.end + node.coordinateShift,
                source.substring(marker.contentStart + node.coordinateShift, marker.contentEnd + node.coordinateShift))
        }
        return null
    }

    private fun locate(offset: Int, endAffinity: Boolean): Pair<Int, Int>? {
        var start = 0
        nodes.forEachIndexed { index, node ->
            val end = start + node.block.text.length
            if (offset <= end) {
                return index to (offset - start).coerceIn(0, node.block.text.length)
            }
            if (offset == start) return index to 0
            start = end + 1
        }
        return null
    }

    private fun parseUpdated(markdown: String, patch: SourcePatch): List<SourceNode> {
        val first = nodes.indexOfFirst { patch.start in it.start..it.end }
        val last = nodes.indexOfLast { patch.end in it.start..it.end }
        if (first < 0 || last < first) return parseFull(markdown, "unmapped-source-boundary", nodes, patch)
        val touched = nodes.subList(first, last + 1)
        // Source-mode changes may introduce an unclosed fence, reference definition,
        // HTML container or extension affecting later blocks. Do not claim locality.
        if (touched.any { !it.editable }) return parseFull(markdown, "protected-source-panel", nodes, patch)
        val from = (first - 1).coerceAtLeast(0)
        val to = (last + 1).coerceAtMost(nodes.lastIndex)
        val start = nodes[from].start
        val oldEnd = if (to < nodes.lastIndex) nodes[to + 1].start else source.length
        val delta = patch.replacement.length - (patch.end - patch.start)
        val end = oldEnd + delta
        if (start !in 0..end || end > markdown.length) return parseFull(markdown, "invalid-dirty-window", nodes, patch)
        val fragment = markdown.substring(start, end)
        val parsed = parseRaw(fragment, allowFrontMatter = start == 0).map { it.shifted(start) }
        val rebound = bindIds(parsed, nodes.subList(from, to + 1), patch)
        parseDiagnostics = parseDiagnostics.copy(
            incrementalParses = parseDiagnostics.incrementalParses + 1,
            parsedCharacters = parseDiagnostics.parsedCharacters + fragment.length,
            parsedNodes = parseDiagnostics.parsedNodes + parsed.size,
            lastParsedCharacters = fragment.length,
            lastParsedNodes = parsed.size,
            lastWindowStart = start,
            lastWindowEnd = end,
            lastFallbackReason = null,
        )
        return buildList(nodes.size - (to - from + 1) + rebound.size) {
            addAll(nodes.subList(0, from))
            addAll(rebound)
            nodes.subList(to + 1, nodes.size).forEach { add(it.shifted(delta)) }
        }
    }

    private fun parseFull(markdown: String, reason: String, previous: List<SourceNode> = emptyList(), patch: SourcePatch? = null): List<SourceNode> {
        val raw = parseRaw(markdown)
        parseDiagnostics = parseDiagnostics.copy(
            fullParses = parseDiagnostics.fullParses + 1,
            parsedCharacters = parseDiagnostics.parsedCharacters + markdown.length,
            parsedNodes = parseDiagnostics.parsedNodes + raw.size,
            lastParsedCharacters = markdown.length,
            lastParsedNodes = raw.size,
            lastWindowStart = 0,
            lastWindowEnd = markdown.length,
            lastFallbackReason = reason,
        )
        return bindIds(raw, previous, patch)
    }

    private fun parseRaw(markdown: String, allowFrontMatter: Boolean = true): List<SourceNode> {
        val normalized = normalizeForParsing(markdown)
        val frontEnd = if (allowFrontMatter) frontMatterEnd(normalized.text) else 0
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(normalized.text as CharSequence)
        val rawNodes = mutableListOf<SourceNode>()
        if (frontEnd > 0) rawNodes += protectedNode(markdown, 0, normalized.sourceOffsets[frontEnd], "front-matter")
        root.children.filter { it.endOffset > frontEnd && it.type.toString() != "Markdown:EOL" }
            .forEach { node ->
                if (node.startOffset < frontEnd || node.startOffset == node.endOffset) return@forEach
                val kind = node.type.toString().removePrefix("Markdown:")
                // Parsing uses LF, with explicit normalized-to-source offsets. Original
                // CRLF bytes remain untouched, including around every modified paragraph.
                val sourceStart = normalized.sourceOffsets[node.startOffset]
                val contentEnd = normalized.sourceOffsets[node.endOffset]
                val raw = markdown.substring(sourceStart, contentEnd)
                val owned = kind == "PARAGRAPH" || kind.matches(Regex("ATX_[1-6]"))
                val unsafe = raw.contains(":::") || raw.contains("<!--") || raw.contains('\n') || raw.contains('\r') ||
                    node.descendants().any { it.type.toString().removePrefix("Markdown:") in PROTECTED_TYPES }
                if (!owned || unsafe) {
                    rawNodes += protectedNode(markdown, sourceStart, contentEnd, kind.lowercase())
                } else {
                    // Plain editor paragraphs preserve spaces around a just-entered break.
                    // The candidate Markdown importer trims those spaces, but its editor
                    // buffer does not; loading an explicitly owned plain block avoids that
                    // codec normalization while the original bytes stay authoritative.
                    val block = if (kind == "PARAGRAPH" && raw.none { it in "\\`*_{}[]<>#~|" }) RichTextBlock(raw)
                        else RichTextState(historyLimit = 0).setMarkdown(raw).toRichTextDocument().blocks.singleOrNull()
                    val leaves = node.descendants().filter { it.children.isEmpty() && it.type.toString() in TEXT_TYPES }
                        .flatMap { (it.startOffset until it.endOffset).asSequence() }
                        .map { normalized.sourceOffsets[it] }.toList()
                    val map = block?.let { mapVisible(markdown, it.text, leaves) }
                    if (block == null || map == null || block.spans.any { delimiter(it) == null }) {
                        rawNodes += protectedNode(markdown, sourceStart, contentEnd, "source-only")
                    } else {
                        val markers = node.descendants().filter { it.children.isNotEmpty() && it.type.toString() in MARK_TYPES }
                            .mapNotNull { ast ->
                                val childText = ast.descendants().filter { it.children.isEmpty() && it.type.toString() in TEXT_TYPES }
                                    .flatMap { (it.startOffset until it.endOffset).asSequence() }
                                    .map { normalized.sourceOffsets[it] }.toList()
                                if (childText.isEmpty()) return@mapNotNull null
                                val visibleStart = map.indexOfFirst { it == childText.first() }
                                val visibleEnd = map.indexOfLast { it == childText.last() }
                                val mark = block.spans.firstOrNull { it.range == visibleStart..visibleEnd && markerType(it) == ast.type.toString() }
                                    ?: return@mapNotNull null
                                SourceMarker(mark, normalized.sourceOffsets[ast.startOffset], normalized.sourceOffsets[ast.endOffset], childText.first(), childText.last() + 1)
                            }.toList()
                        rawNodes += SourceNode(0, sourceStart, contentEnd, kind.lowercase(), raw, block, map, markers, true)
                    }
                }
            }
        if (rawNodes.isEmpty()) rawNodes += SourceNode(0, markdown.length, markdown.length, "paragraph", "", RichTextBlock(""), emptyList(), emptyList(), true)
        return rawNodes
    }

    private fun bindIds(rawNodes: List<SourceNode>, previous: List<SourceNode>, patch: SourcePatch?): List<SourceNode> {
        val delta = patch?.let { it.replacement.length - (it.end - it.start) } ?: 0
        val retained = previous.associateBy { old ->
            if (patch != null && old.start >= patch.end) old.start + delta else old.start
        }
        val consumed = mutableSetOf<Long>()
        return rawNodes.mapIndexed { index, node ->
            val existing = retained[node.start]?.takeIf { old -> old.raw == node.raw && old.kind == node.kind && old.id !in consumed }
                ?: previous.getOrNull(index)?.takeIf { old ->
                patch != null && old.start <= patch.start && old.end >= patch.end && old.start == node.start && old.id !in consumed
            }
            val id = existing?.id ?: nextId++
            consumed += id
            if (node.editable) node.copy(id = id) else node.copy(id = id, block = RichTextBlock("〔${node.kind}: source $id〕"))
        }
    }

    private fun protectedNode(markdown: String, start: Int, end: Int, kind: String) =
        SourceNode(0, start, end, kind, markdown.substring(start, end), RichTextBlock(""), emptyList(), emptyList(), false)

    private fun mapVisible(source: String, visible: String, rawIndices: List<Int>): List<Int>? {
        var cursor = 0
        val mapped = ArrayList<Int>(visible.length)
        visible.forEach { char ->
            while (cursor < rawIndices.size && source[rawIndices[cursor]] != char) cursor++
            if (cursor >= rawIndices.size) return null
            mapped += rawIndices[cursor++]
        }
        // Greedy matching never crosses Markdown markers, links or protected nodes; every
        // accepted replacement is reparsed and compared structurally before committing.
        return mapped
    }

    private fun projection(nodes: List<SourceNode>) = RichTextDocument(nodes.map { it.block })

    companion object {
        private val TEXT_TYPES = setOf("Markdown:TEXT", "WHITE_SPACE")
        private val MARK_TYPES = setOf("Markdown:STRONG", "Markdown:EMPH", "Markdown:STRIKETHROUGH", "Markdown:CODE_SPAN")
        private val PROTECTED_TYPES = setOf("FULL_REFERENCE_LINK", "SHORT_REFERENCE_LINK", "LINK_DEFINITION", "INLINE_LINK", "IMAGE", "HTML_BLOCK", "HTML_TAG", "AUTOLINK", "URI_AUTOLINK", "EMAIL_AUTOLINK")

        private fun delimiter(mark: RichTextSpanMark): String? = when (mark) {
            is RichTextSpanMark.Bold -> "**"
            is RichTextSpanMark.Italic -> "*"
            is RichTextSpanMark.Strikethrough -> "~~"
            is RichTextSpanMark.CodeSpan -> "`"
            else -> null
        }

        private fun markerType(mark: RichTextSpanMark): String? = when (mark) {
            is RichTextSpanMark.Bold -> "Markdown:STRONG"
            is RichTextSpanMark.Italic -> "Markdown:EMPH"
            is RichTextSpanMark.Strikethrough -> "Markdown:STRIKETHROUGH"
            is RichTextSpanMark.CodeSpan -> "Markdown:CODE_SPAN"
            else -> null
        }

        private fun frontMatterEnd(source: String): Int {
            if (!source.startsWith("---\n") && !source.startsWith("---\r\n")) return 0
            val match = Regex("(?m)^---\\r?$").findAll(source).drop(1).firstOrNull() ?: return source.length
            return match.range.last + 1
        }

        private fun normalizeForParsing(source: String): NormalizedSource {
            val text = StringBuilder()
            val offsets = mutableListOf<Int>()
            var index = 0
            while (index < source.length) {
                offsets += index
                if (source[index] == '\r' && source.getOrNull(index + 1) == '\n') {
                    text.append('\n')
                    index += 2
                } else {
                    text.append(source[index++])
                }
            }
            offsets += source.length
            return NormalizedSource(text.toString(), offsets)
        }

        private fun logicalText(document: RichTextDocument): String = document.blocks.joinToString("\n") { it.text }

        private fun escapePlain(value: String): String = buildString {
            value.forEach { char ->
                if (char in "\\`*_{}[]<>#~|") append('\\')
                append(char)
            }
        }

        private fun isBoundary(text: String, offset: Int): Boolean = offset in 0..text.length &&
            !(offset > 0 && offset < text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())

        private fun wellFormedUtf16(text: String): Boolean {
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

        private fun changedRange(old: String, new: String): TextDiff {
            var prefix = 0
            while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
            while (!isBoundary(old, prefix) || !isBoundary(new, prefix)) prefix--
            var suffix = 0
            while (suffix < old.length - prefix && suffix < new.length - prefix && old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++
            while (!isBoundary(old, old.length - suffix) || !isBoundary(new, new.length - suffix)) suffix--
            return TextDiff(prefix, old.length - suffix, prefix, new.length - suffix)
        }
    }
}

data class SourceBlockInfo(val id: Long, val kind: String, val start: Int, val end: Int, val editable: Boolean, val text: String)

data class SourceParseDiagnostics(
    val fullParses: Long = 0,
    val incrementalParses: Long = 0,
    val parsedCharacters: Long = 0,
    val parsedNodes: Long = 0,
    val lastParsedCharacters: Int = 0,
    val lastParsedNodes: Int = 0,
    val lastWindowStart: Int = 0,
    val lastWindowEnd: Int = 0,
    val lastFallbackReason: String? = null,
)

sealed interface SourceEditResult {
    data object Unchanged : SourceEditResult
    data class Applied(val version: Long, val replacedStart: Int, val replacedEnd: Int, val insertedCharacters: Int) : SourceEditResult
    data class Rejected(val reason: String) : SourceEditResult
}

private data class SourceNode(
    val id: Long, val start: Int, val end: Int, val kind: String, val raw: String,
    val block: RichTextBlock, val characterSources: List<Int>, val markers: List<SourceMarker>, val editable: Boolean,
    val coordinateShift: Int = 0,
) {
    fun shifted(delta: Int): SourceNode = if (delta == 0) this else copy(start = start + delta, end = end + delta, coordinateShift = coordinateShift + delta)

    fun sourceBoundary(offset: Int): Int? {
        if (!editable || offset !in 0..block.text.length) return null
        if (offset > 0 && offset < block.text.length && block.text[offset - 1].isHighSurrogate() && block.text[offset].isLowSurrogate()) return null
        if (characterSources.isEmpty()) return start
        return (if (offset == characterSources.size) characterSources.last() + 1 else characterSources[offset]) + coordinateShift
    }
}

private data class SourceMarker(val mark: RichTextSpanMark, val start: Int, val end: Int, val contentStart: Int, val contentEnd: Int)
private data class SourcePatch(val start: Int, val end: Int, val replacement: String)
private data class TextDiff(val oldStart: Int, val oldEnd: Int, val newStart: Int, val newEnd: Int)
private data class NormalizedSource(val text: String, val sourceOffsets: List<Int>)
private fun ASTNode.descendants(): Sequence<ASTNode> = sequence {
    yield(this@descendants)
    children.forEach { yieldAll(it.descendants()) }
}
