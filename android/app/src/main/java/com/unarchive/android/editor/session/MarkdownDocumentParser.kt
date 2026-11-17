package com.unarchive.android.editor.session

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** All offsets in the owned model are UTF-16 offsets. No Android/Compose types live here. */
data class DocumentSelection(val anchor: Int = 0, val focus: Int = anchor) {
    val start: Int get() = minOf(anchor, focus)
    val end: Int get() = maxOf(anchor, focus)
    val isCollapsed: Boolean get() = anchor == focus
}

enum class InlineMark { BOLD, ITALIC, STRIKE, CODE, LINK }

data class DocumentLineStyle(
    val start: Int, val end: Int, val sourceStart: Int,
    val contentSourceStart: Int = sourceStart,
    val listStyle: DocumentListStyle? = null, val ordinal: Int = 1, val checked: Boolean = false,
    val indent: Int = 0, val quoteDepth: Int = 0, val codeBlock: Boolean = false,
)

data class DocumentAtomicNode(val kind: String, val text: String, val destination: String? = null,
    val rows: List<List<String>> = emptyList())
data class DocumentInlineAtom(val start: Int, val end: Int, val sourceStart: Int, val sourceEnd: Int, val node: DocumentAtomicNode)

data class DocumentMark(
    val kind: InlineMark, val start: Int, val end: Int,
    val sourceStart: Int, val sourceEnd: Int, val contentStart: Int, val contentEnd: Int,
    val destination: String? = null,
)

data class DocumentNode(
    val id: Long,
    val start: Int,
    val end: Int,
    val kind: String,
    val raw: String,
    val text: String,
    /** Relative to [start], so untouched nodes can move without copying their maps. */
    val characterSources: List<Int>,
    val headingLevel: Int = 0,
    val marks: List<DocumentMark> = emptyList(),
    val hidden: Boolean = false,
    val lines: List<DocumentLineStyle> = emptyList(),
    val atomic: DocumentAtomicNode? = null,
    val synthetic: Boolean = false,
    val inlineAtoms: List<DocumentInlineAtom> = emptyList(),
) {
    fun shifted(delta: Int): DocumentNode = if (delta == 0) this else copy(start = start + delta, end = end + delta)
}

data class ProjectedNode(val node: DocumentNode, val start: Int, val end: Int)

data class DocumentProjection(
    val text: String,
    val nodes: List<ProjectedNode>,
    val characterSources: List<Int>,
    val sourceLength: Int,
) {
    fun sourceOffset(offset: Int, endAffinity: Boolean = false): Int {
        val safe = offset.coerceIn(0, text.length)
        val node = nodes.firstOrNull { !it.node.hidden && safe >= it.start && safe <= it.end }
        if (node != null && node.node.characterSources.isNotEmpty()) {
            val local = safe - node.start
            if (endAffinity && local == 0) return node.node.start
            if (node.node.atomic != null) return if (local == 0 && !endAffinity) node.node.start else node.node.end
            node.node.inlineAtoms.firstOrNull { local == it.start || local == it.end && endAffinity }?.let {
                return node.node.start + if (local == it.end) it.sourceEnd else it.sourceStart
            }
            node.node.lines.lastOrNull { it.start == local && it.end == local }?.let { return node.node.start + it.contentSourceStart }
            return if (local == node.node.characterSources.size || (endAffinity && local > 0)) {
                node.node.start + node.node.characterSources[local - 1] + 1
            } else node.node.start + node.node.characterSources[local]
        }
        if (safe == text.length) return characterSources.lastOrNull()?.plus(1) ?: sourceLength
        return characterSources[safe]
    }

    fun projectedOffset(sourceOffset: Int): Int {
        if (characterSources.isEmpty()) return 0
        var low = 0
        var high = characterSources.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (characterSources[middle] < sourceOffset) low = middle + 1 else high = middle
        }
        return low
    }
}

data class DocumentParseDiagnostics(
    val fullParses: Long = 0, val incrementalParses: Long = 0,
    val parsedCharacters: Long = 0, val lastParsedCharacters: Int = 0,
    val lastFallbackReason: String? = null,
)

/** AST parsing is a projection only: raw source slices remain the persistence authority. */
class MarkdownDocumentParser {
    private var nextId = 1L
    private var referenceDefinitions = emptyMap<String, String>()
    var diagnostics = DocumentParseDiagnostics()
        private set

    fun parse(source: String, previous: List<DocumentNode> = emptyList(), patch: SourceReplacement? = null): List<DocumentNode> {
        referenceDefinitions = Regex("(?m)^ {0,3}\\[([^]]+)]:[ \\t]*<?([^\\s>]+)>?.*$").findAll(source)
            .associate { it.groupValues[1].trim().lowercase() to it.groupValues[2] }
        val parsed = parseRaw(source, 0)
        diagnostics = diagnostics.copy(fullParses = diagnostics.fullParses + 1,
            parsedCharacters = diagnostics.parsedCharacters + source.length,
            lastParsedCharacters = source.length, lastFallbackReason = if (patch == null) "initial-load" else "explicit-source")
        return bind(parsed, previous, patch)
    }

    fun update(source: String, previous: List<DocumentNode>, patch: SourceReplacement): List<DocumentNode> {
        // Separating blank lines are not AST nodes, but ordinary input there belongs to
        // the adjacent container window. Never classify a mapped gap as a whole-document edit.
        val first = previous.indexOfLast { it.start <= patch.start }.coerceAtLeast(0)
        val last = previous.indexOfFirst { it.end >= patch.end }.let { if (it < 0) previous.lastIndex else it }.coerceAtLeast(first)
        if (first < 0 || last < first || previous.subList(first, last + 1).any { it.hidden || it.kind !in LOCAL_KINDS }) {
            return parse(source, previous, patch)
        }
        // Complete root containers plus neighbors. A new fence/definition requires full context.
        if (patch.replacement.contains("```") || patch.replacement.contains("~~~") ||
            patch.replacement.contains("<!--") || Regex("(?m)^ {0,3}\\[[^]]+]:").containsMatchIn(patch.replacement)) {
            return parse(source, previous, patch)
        }
        val from = (first - 1).coerceAtLeast(0)
        val to = (last + 1).coerceAtMost(previous.lastIndex)
        val start = previous[from].start
        val oldEnd = if (to < previous.lastIndex) previous[to + 1].start else source.length - patch.delta
        val end = oldEnd + patch.delta
        if (start !in 0..end || end > source.length) return parse(source, previous, patch)
        val parsed = parseRaw(source.substring(start, end), start)
        val rebound = bind(parsed, previous.subList(from, to + 1), patch)
        diagnostics = diagnostics.copy(incrementalParses = diagnostics.incrementalParses + 1,
            parsedCharacters = diagnostics.parsedCharacters + end - start,
            lastParsedCharacters = end - start, lastFallbackReason = null)
        return buildList {
            addAll(previous.subList(0, from)); addAll(rebound)
            previous.subList(to + 1, previous.size).forEach { add(it.shifted(patch.delta)) }
        }
    }

    private fun bind(nodes: List<DocumentNode>, previous: List<DocumentNode>, patch: SourceReplacement?): List<DocumentNode> {
        val consumed = mutableSetOf<Long>()
        return nodes.map { node ->
            val old = previous.firstOrNull { prior ->
                prior.id !in consumed && prior.kind == node.kind && prior.raw == node.raw &&
                    (prior.start == node.start || patch != null && prior.start >= patch.end && prior.start + patch.delta == node.start)
            } ?: previous.firstOrNull { prior ->
                patch != null && prior.id !in consumed && prior.start == node.start && prior.start <= patch.start && prior.end >= patch.end
            }
            val id = old?.id ?: nextId++
            consumed += id
            node.copy(id = id)
        }
    }

    private fun parseRaw(raw: String, shift: Int): List<DocumentNode> {
        if (raw.isEmpty()) return listOf(DocumentNode(0, shift, shift, "paragraph", "", "", emptyList()))
        val normalized = StringBuilder()
        val sourceOffsets = ArrayList<Int>(raw.length + 1)
        var cursor = 0
        while (cursor < raw.length) {
            sourceOffsets += cursor
            if (raw[cursor] == '\r' && raw.getOrNull(cursor + 1) == '\n') { normalized.append('\n'); cursor += 2 }
            else normalized.append(raw[cursor++])
        }
        sourceOffsets += raw.length
        val markdown = normalized.toString()
        val frontEnd = if (shift == 0 && markdown.startsWith("---\n")) {
            Regex("(?m)^(---|\\.\\.\\.)[ \\t]*$").findAll(markdown).drop(1).firstOrNull()?.range?.last?.plus(1) ?: markdown.length
        } else 0
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown as CharSequence)
        val definitions = referenceDefinitions
        val result = mutableListOf<DocumentNode>()
        if (frontEnd > 0) {
            val end = sourceOffsets[frontEnd]
            result += DocumentNode(0, shift, shift + end, "front-matter", raw.substring(0, end), "", emptyList(), hidden = true)
        }
        root.children.filter { it.endOffset > frontEnd && it.startOffset >= frontEnd && it.type.toString() != "Markdown:EOL" && it.endOffset > it.startOffset }
            .forEach { ast ->
                val start = sourceOffsets[ast.startOffset]
                val end = sourceOffsets[ast.endOffset]
                val slice = raw.substring(start, end)
                val kind = ast.type.toString().removePrefix("Markdown:").lowercase()
                if (kind == "link_definition") {
                    result += DocumentNode(0, shift + start, shift + end, kind, slice, "", emptyList(), hidden = true)
                    return@forEach
                }
                val hidden = BooleanArray(slice.length)
                val markRanges = mutableListOf<RawMark>()
                val rawAtoms = mutableListOf<RawAtomic>()
                val heading = Regex("^ {0,3}(#{1,6})[ \\t]+").find(slice)
                val level = if (kind.startsWith("atx_")) heading?.groups?.get(1)?.value?.length ?: 0 else 0
                if (level > 0) {
                    for (index in 0 until heading!!.range.last + 1) hidden[index] = true
                    Regex("[ \\t]+#+[ \\t]*$").find(slice)?.let { suffix -> suffix.range.forEach { hidden[it] = true } }
                }
                fun hide(from: Int, to: Int) { for (index in from.coerceAtLeast(0) until to.coerceAtMost(hidden.size)) hidden[index] = true }
                ast.walk().forEach { child ->
                    val type = child.type.toString().removePrefix("Markdown:")
                    val a = sourceOffsets[child.startOffset] - start
                    val b = sourceOffsets[child.endOffset] - start
                    if (a < 0 || b > slice.length) return@forEach
                    val token = slice.substring(a, b)
                    val mark = when (type) { "STRONG" -> InlineMark.BOLD; "EMPH" -> InlineMark.ITALIC; "STRIKETHROUGH" -> InlineMark.STRIKE; "CODE_SPAN" -> InlineMark.CODE; else -> null }
                    if (mark != null && token.length > 1) {
                        val delimiter = if (mark == InlineMark.BOLD || mark == InlineMark.STRIKE) 2 else if (mark == InlineMark.CODE) token.takeWhile { it == '`' }.length else 1
                        if (delimiter * 2 <= token.length) {
                            hide(a, a + delimiter); hide(b - delimiter, b)
                            markRanges += RawMark(mark, a, b, a + delimiter, b - delimiter)
                        }
                    }
                    if (type == "ESCAPED_CHAR" && token.startsWith("\\")) hide(a, a + 1)
                    // Preserve the complete label while the URL remains in the authoritative source.
                    if (type == "INLINE_LINK" && token.startsWith("[")) {
                        val close = token.indexOf("](")
                        if (close >= 1 && token.endsWith(")")) {
                            hide(a, a + 1); hide(a + close, b)
                            markRanges += RawMark(InlineMark.LINK, a, b, a + 1, a + close,
                                token.substring(close + 2, token.length - 1).substringBefore(" \"").trim('<', '>'))
                        }
                    }
                    if ((type == "FULL_REFERENCE_LINK" || type == "SHORT_REFERENCE_LINK") && token.startsWith("[")) {
                        val labelEnd = token.indexOf(']')
                        if (labelEnd > 0) {
                            val label = token.substring(1, labelEnd)
                            val reference = token.substring(labelEnd + 1).removePrefix("[").removeSuffix("]").ifBlank { label }
                            val destination = definitions[reference.trim().lowercase()]
                            if (destination != null) {
                                hide(a, a + 1); hide(a + labelEnd, b)
                                markRanges += RawMark(InlineMark.LINK, a, b, a + 1, a + labelEnd, destination)
                            }
                        }
                    }
                    if (type == "IMAGE" && token.startsWith("![")) {
                        val image = Regex("^!\\[([^]]*)]\\(([^\\s)]+)(?:[ \\t]+\"[^\"]*\")?\\)$").matchEntire(token)
                        val referenceImage = Regex("^!\\[([^]]*)](?:\\[([^]]*)])?$").matchEntire(token)
                        val destination = image?.groupValues?.get(2) ?: referenceImage?.let { reference ->
                            definitions[reference.groupValues[2].ifBlank { reference.groupValues[1] }.lowercase()]
                        }
                        if (destination != null) {
                            val alt = image?.groupValues?.get(1) ?: referenceImage!!.groupValues[1]
                            rawAtoms += RawAtomic(a, b, DocumentAtomicNode("image", alt.ifBlank { "图片" }, destination))
                            hide(a, b)
                        }
                    }
                }
                if (kind != "code_fence" && kind != "code_block") {
                    // IntelliJ represents several escaped punctuation tokens as TEXT leaves.
                    // Remove exactly the escape byte, retaining a precise source map.
                    Regex("\\\\[\\\\`*_{}\\[\\]()#+.!<>|~-]").findAll(slice).forEach { escaped ->
                        val position = escaped.range.first
                        if (markRanges.none { it.kind == InlineMark.CODE && position >= it.contentStart && position < it.contentEnd }) hide(position, position + 1)
                    }
                }
                val rawLines = mutableListOf<RawLine>()
                var lineStart = 0
                slice.split('\n').forEach { lineWithCr ->
                    val line = lineWithCr.removeSuffix("\r")
                    var prefixEnd = 0
                    var quoteDepth = 0
                    if (kind == "block_quote") {
                        val quote = Regex("^(?: {0,3}>[ \\t]?)+").find(line)
                        if (quote != null) {
                            prefixEnd = quote.value.length; quoteDepth = quote.value.count { it == '>' }
                            hide(lineStart, lineStart + prefixEnd)
                        }
                    }
                    val rest = line.substring(prefixEnd)
                    val list = Regex("^([ \\t]*)(?:([-+*])|([0-9]+)[.)])[ \\t]+(?:\\[([ xX])] ?)?").find(rest)
                        ?.takeIf { kind == "unordered_list" || kind == "ordered_list" || kind == "block_quote" }
                    val listStyle = list?.let {
                        if (it.groups[4] != null) DocumentListStyle.TASK else if (it.groups[3] != null) DocumentListStyle.ORDERED else DocumentListStyle.UNORDERED
                    }
                    if (list != null) {
                        hide(lineStart + prefixEnd, lineStart + prefixEnd + list.value.length)
                        prefixEnd += list.value.length
                    }
                    if (kind == "code_block") {
                        val indent = if (line.startsWith('\t')) 1 else line.takeWhile { it == ' ' }.length.coerceAtMost(4)
                        hide(lineStart, lineStart + indent); prefixEnd += indent
                    }
                    if ((kind == "code_fence") && Regex("^ {0,3}(`{3,}|~{3,}).*$").matches(line) &&
                        (lineStart == 0 || lineStart + line.length >= slice.trimEnd().length)) {
                        hide(lineStart, (lineStart + lineWithCr.length + 1).coerceAtMost(slice.length))
                    } else rawLines += RawLine(lineStart, lineStart + line.length, lineStart + prefixEnd,
                        listStyle, list?.groups?.get(3)?.value?.toIntOrNull() ?: 1,
                        list?.groups?.get(4)?.value?.equals("x", true) == true,
                        list?.groups?.get(1)?.value?.replace("\t", "    ")?.length ?: 0,
                        quoteDepth, kind == "code_fence" || kind == "code_block")
                    lineStart += lineWithCr.length + 1
                }
                val visible = StringBuilder()
                val offsets = mutableListOf<Int>()
                slice.forEachIndexed { index, char ->
                    if (rawAtoms.any { it.start == index }) { visible.append('\uFFFC'); offsets += index }
                    if (!hidden[index] && !(char == '\r' && slice.getOrNull(index + 1) == '\n')) {
                        visible.append(char); offsets += index
                    }
                }
                val marks = markRanges.map { mark -> DocumentMark(mark.kind,
                    offsets.count { it < mark.contentStart }, offsets.count { it < mark.contentEnd },
                    mark.start, mark.end, mark.contentStart, mark.contentEnd, mark.destination) }
                val lines = rawLines.map { line -> DocumentLineStyle(offsets.count { it < line.contentStart },
                    offsets.count { it < line.end }, line.start, line.contentStart, line.style, line.ordinal, line.checked,
                    line.indent, line.quoteDepth, line.codeBlock) }
                val image = Regex("^!\\[([^]]*)]\\(([^\\s)]+)(?:[ \\t]+\"[^\"]*\")?\\)[ \\t]*$").matchEntire(slice)
                val referenceImage = Regex("^!\\[([^]]*)]\\[([^]]*)][ \\t]*$").matchEntire(slice)
                val table = kind.contains("table")
                val rule = kind == "horizontal_rule"
                val atomic = when {
                    image != null -> DocumentAtomicNode("image", image.groupValues[1].ifBlank { "图片" }, image.groupValues[2])
                    referenceImage != null && definitions.containsKey(referenceImage.groupValues[2].ifBlank { referenceImage.groupValues[1] }.lowercase()) ->
                        DocumentAtomicNode("image", referenceImage.groupValues[1].ifBlank { "图片" },
                            definitions[referenceImage.groupValues[2].ifBlank { referenceImage.groupValues[1] }.lowercase()])
                    table -> {
                        val rows = slice.lines().filterNot { Regex("^[ \\t|:-]+$").matches(it) }.map { row ->
                            row.trim().trim('|').split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }
                        }
                        DocumentAtomicNode("table", rows.joinToString("\n") { it.joinToString("\t") }, rows = rows)
                    }
                    rule -> DocumentAtomicNode("divider", "分隔线")
                    else -> null
                }
                result += DocumentNode(0, shift + start, shift + end, kind, slice,
                    if (atomic == null) visible.toString() else "\uFFFC",
                    if (atomic == null) offsets else listOf(0), level, if (atomic == null) marks else emptyList(),
                    lines = if (atomic == null) lines else emptyList(), atomic = atomic)
                    .let { node -> if (atomic == null) node.copy(inlineAtoms = rawAtoms.mapNotNull { rawAtom ->
                        val position = offsets.indexOf(rawAtom.start)
                        if (position < 0) null else DocumentInlineAtom(position, position + 1, rawAtom.start, rawAtom.end, rawAtom.node)
                    }) else node }
            }
        if (result.isEmpty()) result += DocumentNode(0, shift, shift + raw.length, "paragraph", raw, markdown, sourceOffsets.dropLast(1))
        return result
    }

    companion object {
        private val LOCAL_KINDS = setOf("paragraph", "atx_1", "atx_2", "atx_3", "atx_4", "atx_5", "atx_6",
            "unordered_list", "ordered_list", "block_quote")

        fun project(source: String, nodes: List<DocumentNode>): DocumentProjection {
            val text = StringBuilder()
            val offsets = ArrayList<Int>(source.length)
            val projected = ArrayList<ProjectedNode>(nodes.size)
            var prior = 0
            nodes.forEach { node ->
                for (index in prior until node.start) {
                    if (!(source[index] == '\r' && source.getOrNull(index + 1) == '\n')) {
                        text.append(source[index]); offsets += index
                    }
                }
                if (!node.hidden) {
                    val start = text.length
                    text.append(node.text)
                    node.characterSources.forEach { offsets += it + node.start }
                    projected += ProjectedNode(node, start, text.length)
                } else projected += ProjectedNode(node, text.length, text.length)
                prior = node.end
            }
            for (index in prior until source.length) {
                if (!(source[index] == '\r' && source.getOrNull(index + 1) == '\n')) { text.append(source[index]); offsets += index }
            }
            return DocumentProjection(text.toString(), projected, offsets, source.length)
        }
    }
}

private data class RawMark(val kind: InlineMark, val start: Int, val end: Int, val contentStart: Int, val contentEnd: Int, val destination: String? = null)
private data class RawLine(val start: Int, val end: Int, val contentStart: Int, val style: DocumentListStyle?,
    val ordinal: Int, val checked: Boolean, val indent: Int, val quoteDepth: Int, val codeBlock: Boolean)
private data class RawAtomic(val start: Int, val end: Int, val node: DocumentAtomicNode)
private fun ASTNode.walk(): Sequence<ASTNode> = sequence { yield(this@walk); children.forEach { yieldAll(it.walk()) } }
