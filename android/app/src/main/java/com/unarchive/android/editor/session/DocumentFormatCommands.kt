package com.unarchive.android.editor.session

enum class DocumentListStyle { UNORDERED, ORDERED, TASK }
enum class FormatCoverage { OFF, ON, MIXED }
data class SelectionFormatState(
    val marks: Map<InlineMark, FormatCoverage> = emptyMap(),
    val headingLevel: Int? = 0, val listStyle: DocumentListStyle? = null,
    val quote: Boolean = false, val codeBlock: Boolean = false,
)

sealed interface DocumentFormatCommand {
    data class SetBlockStyle(val level: Int) : DocumentFormatCommand
    data class ToggleMark(val mark: InlineMark) : DocumentFormatCommand
    data class SetList(val style: DocumentListStyle) : DocumentFormatCommand
    data object ToggleTask : DocumentFormatCommand
    data object SetQuote : DocumentFormatCommand
    data object SetCodeBlock : DocumentFormatCommand
    data object InsertDivider : DocumentFormatCommand
    data class SetLink(val destination: String, val label: String? = null) : DocumentFormatCommand
    data object RemoveLink : DocumentFormatCommand
    data object Enter : DocumentFormatCommand
    data object Backspace : DocumentFormatCommand
    data object ApplyShortcut : DocumentFormatCommand
    data class ReplaceSelection(val text: String, val markdown: Boolean = false, val origin: EditOrigin = EditOrigin.PASTE) : DocumentFormatCommand
}

/** Commands create source-range transactions; spans are a consequence of parsing the result. */
object DocumentFormatCommands {
    fun selectionState(session: DocumentSession, selection: DocumentSelection = session.selection): SelectionFormatState {
        val projected = session.projection
        val blocks = selectedNodes(projected, selection)
        val marks = InlineMark.entries.associateWith { kind ->
            if (selection.isCollapsed) {
                val explicit = session.typingMarks
                if (kind != InlineMark.LINK && explicit != null) {
                    if (kind in explicit) FormatCoverage.ON else FormatCoverage.OFF
                } else if (blocks.any { block -> block.node.marks.any { it.kind == kind && selection.anchor >= block.start + it.start && selection.anchor <= block.start + it.end } }) FormatCoverage.ON
                else FormatCoverage.OFF
            } else {
                val intervals = blocks.flatMap { block -> block.node.marks.filter { it.kind == kind }.map { block.start + it.start until block.start + it.end } }
                val ranges = visibleRanges(projected, selection)
                val length = ranges.sumOf { it.last + 1 - it.first }
                val covered = ranges.sumOf { range ->
                    (range.first..range.last).count { offset -> intervals.any { offset in it } }
                }
                when { covered == 0 -> FormatCoverage.OFF; covered == length && length > 0 -> FormatCoverage.ON; else -> FormatCoverage.MIXED }
            }
        }
        val sourceStart = projected.sourceOffset(selection.start)
        val sourceEnd = projected.sourceOffset(selection.end, selection.end > selection.start)
        val lines = selectedLines(session.source, sourceStart, sourceEnd)
        val levels = blocks.filter { it.node.atomic == null }.map { it.node.headingLevel }.distinct()
        val lists = lines.map { parsePrefix(it.text).listStyle }.distinct()
        return SelectionFormatState(marks, levels.singleOrNull(), lists.singleOrNull(),
            lines.isNotEmpty() && lines.all { parsePrefix(it.text).quote.isNotEmpty() },
            blocks.isNotEmpty() && blocks.all { it.node.kind == "code_fence" || it.node.kind == "code_block" })
    }

    fun execute(session: DocumentSession, command: DocumentFormatCommand,
        selection: DocumentSelection = session.selection, allowHistoryBarrier: Boolean = false): SessionResult {
        session.expandForMutation(selection)?.let { return it }
        session.updateSelection(selection)
        if (command != DocumentFormatCommand.ApplyShortcut) session.endInputGroup()
        return when (command) {
            is DocumentFormatCommand.ToggleMark -> toggleMark(session, selection, command.mark, allowHistoryBarrier)
            is DocumentFormatCommand.SetBlockStyle -> {
                require(command.level in 0..6)
                transformLines(session, selection, allowHistoryBarrier) { line, _ ->
                    val prefix = parsePrefix(line)
                    val after = line.substring(prefix.contentStart).replaceFirst(Regex("^#{1,6}[ \\t]+"), "")
                    line.substring(0, prefix.contentStart) + (if (command.level == 0) "" else "#".repeat(command.level) + " ") + after
                }
            }
            is DocumentFormatCommand.SetList -> {
                val state = selectionState(session, selection)
                val remove = state.listStyle == command.style
                transformLines(session, selection, allowHistoryBarrier) { line, index ->
                    val prefix = parsePrefix(line)
                    val leader = if (remove) "" else when (command.style) {
                        DocumentListStyle.UNORDERED -> "- "
                        DocumentListStyle.ORDERED -> "${index + 1}. "
                        DocumentListStyle.TASK -> "- [${if (prefix.checked) "x" else " "}] "
                    }
                    prefix.quote + prefix.indent + leader + line.substring(prefix.contentStart)
                }
            }
            DocumentFormatCommand.ToggleTask -> transformLines(session, selection, allowHistoryBarrier) { line, _ ->
                val prefix = parsePrefix(line)
                if (prefix.listStyle == DocumentListStyle.TASK) line.replaceFirst(Regex("\\[([ xX])]"), if (prefix.checked) "[ ]" else "[x]")
                else line
            }
            DocumentFormatCommand.SetQuote -> {
                val remove = selectionState(session, selection).quote
                transformLines(session, selection, allowHistoryBarrier) { line, _ ->
                    if (remove) line.replaceFirst(Regex("^ {0,3}>[ \\t]?"), "") else "> $line"
                }
            }
            DocumentFormatCommand.SetCodeBlock -> setCodeBlock(session, selection, allowHistoryBarrier)
            DocumentFormatCommand.InsertDivider -> {
                val sourceOffset = session.projection.sourceOffset(selection.start)
                val prefix = if (sourceOffset == 0 || session.source.substring(0, sourceOffset).endsWith("\n\n")) "" else eol(session.source).repeat(2)
                applyPatches(session, listOf(SourceReplacement(sourceOffset, sourceOffset, prefix + "---" + eol(session.source).repeat(2))), selection, allowHistoryBarrier)
            }
            is DocumentFormatCommand.SetLink -> setLink(session, selection, command, allowHistoryBarrier)
            DocumentFormatCommand.RemoveLink -> removeLink(session, selection, allowHistoryBarrier)
            DocumentFormatCommand.Enter -> enter(session, selection, allowHistoryBarrier)
            DocumentFormatCommand.Backspace -> backspace(session, selection, allowHistoryBarrier)
            DocumentFormatCommand.ApplyShortcut -> applyShortcut(session, selection, allowHistoryBarrier)
            is DocumentFormatCommand.ReplaceSelection -> {
                if (command.markdown) {
                    val start = session.projection.sourceOffset(selection.start)
                    val end = session.projection.sourceOffset(selection.end, selection.end > selection.start)
                    applyPatches(session, listOf(SourceReplacement(start, end, command.text)), selection, allowHistoryBarrier, command.origin)
                } else session.replaceProjectedRange(selection.start, selection.end, command.text, selection,
                    DocumentSelection(selection.start + command.text.length), origin = command.origin, allowHistoryBarrier = allowHistoryBarrier)
            }
        }
    }

    private fun toggleMark(session: DocumentSession, selection: DocumentSelection, kind: InlineMark, barrier: Boolean): SessionResult {
        if (kind == InlineMark.LINK) return SessionResult.Rejected("use-link-command")
        if (selection.isCollapsed) {
            val active = selectionState(session, selection).marks.filterValues { it == FormatCoverage.ON }.keys - InlineMark.LINK
            session.setTypingMarks(if (kind in active) active - kind else active + kind)
            return SessionResult.Unchanged
        }
        val remove = selectionState(session, selection).marks[kind] == FormatCoverage.ON
        val patches = mutableListOf<SourceReplacement>()
        selectedNodes(session.projection, selection).forEach { block ->
            if (block.node.atomic != null) return@forEach
            val a = (selection.start - block.start).coerceAtLeast(0)
            val b = (selection.end - block.start).coerceAtMost(block.node.text.length)
            if (a >= b) return@forEach
            val marks = block.node.marks.filter { it.kind == kind && it.end > a && it.start < b }
            fun offset(local: Int, end: Boolean = false): Int = session.projection.sourceOffset(block.start + local, end)
            if (remove) {
                marks.forEach { mark ->
                    val start = maxOf(a, mark.start); val end = minOf(b, mark.end)
                    val delimiter = block.node.raw.substring(mark.sourceStart, mark.contentStart)
                    if (start == mark.start) patches += SourceReplacement(block.node.start + mark.sourceStart, block.node.start + mark.contentStart, "")
                    else patches += SourceReplacement(offset(start), offset(start), delimiter)
                    if (end == mark.end) patches += SourceReplacement(block.node.start + mark.contentEnd, block.node.start + mark.sourceEnd, "")
                    else patches += SourceReplacement(offset(end, true), offset(end, true), delimiter)
                }
            } else {
                val start = minOf(a, marks.minOfOrNull { it.start } ?: a)
                val end = maxOf(b, marks.maxOfOrNull { it.end } ?: b)
                marks.forEach { mark ->
                    patches += SourceReplacement(block.node.start + mark.sourceStart, block.node.start + mark.contentStart, "")
                    patches += SourceReplacement(block.node.start + mark.contentEnd, block.node.start + mark.sourceEnd, "")
                }
                val raw = session.source.substring(offset(start), offset(end, true))
                val delimiter = delimiter(kind, raw)
                val s = minOf(offset(start), marks.minOfOrNull { it.sourceStart + block.node.start } ?: Int.MAX_VALUE)
                val e = maxOf(offset(end, true), marks.maxOfOrNull { it.sourceEnd + block.node.start } ?: Int.MIN_VALUE)
                patches += SourceReplacement(s, s, delimiter)
                patches += SourceReplacement(e, e, delimiter)
            }
        }
        return applyPatches(session, patches, selection, barrier)
    }

    private fun setCodeBlock(session: DocumentSession, selection: DocumentSelection, barrier: Boolean): SessionResult {
        val blocks = selectedNodes(session.projection, selection)
        if (blocks.size == 1 && blocks.single().node.kind == "code_fence") {
            val node = blocks.single().node
            val sourceLines = node.raw.lines()
            val body = sourceLines.drop(1).dropLast(1).joinToString(eol(session.source))
            return applyPatches(session, listOf(SourceReplacement(node.start, node.end, body)), selection, barrier)
        }
        val start = session.projection.sourceOffset(selection.start)
        val end = session.projection.sourceOffset(selection.end, selection.end > selection.start)
        val lines = selectedLines(session.source, start, end)
        if (lines.isEmpty()) return SessionResult.Unchanged
        val a = lines.first().start; val b = lines.last().end
        val body = session.source.substring(a, b)
        val fence = "`".repeat(maxOf(3, Regex("`+").findAll(body).maxOfOrNull { it.value.length }?.plus(1) ?: 3))
        return applyPatches(session, listOf(SourceReplacement(a, b, fence + eol(session.source) + body + eol(session.source) + fence)), selection, barrier)
    }

    private fun setLink(session: DocumentSession, selection: DocumentSelection, command: DocumentFormatCommand.SetLink, barrier: Boolean): SessionResult {
        if (!com.unarchive.android.editor.NoteLinkPolicy.isAllowed(command.destination)) return SessionResult.Rejected("unsafe-link")
        val existing = linkAt(session.projection, selection)
        if (existing != null) {
            val (block, mark) = existing
            val label = command.label ?: block.node.text.substring(mark.start, mark.end)
            return applyPatches(session, listOf(SourceReplacement(block.node.start + mark.sourceStart, block.node.start + mark.sourceEnd,
                "[${escapeLabel(label)}](${escapeDestination(command.destination)})")), selection, barrier)
        }
        val label = command.label ?: session.projection.text.substring(selection.start, selection.end).ifEmpty { command.destination }
        val a = session.projection.sourceOffset(selection.start)
        val b = session.projection.sourceOffset(selection.end, selection.end > selection.start)
        return applyPatches(session, listOf(SourceReplacement(a, b, "[${escapeLabel(label)}](${escapeDestination(command.destination)})")), selection, barrier)
    }

    private fun removeLink(session: DocumentSession, selection: DocumentSelection, barrier: Boolean): SessionResult {
        val (block, mark) = linkAt(session.projection, selection) ?: return SessionResult.Unchanged
        return applyPatches(session, listOf(SourceReplacement(block.node.start + mark.sourceStart, block.node.start + mark.contentStart, ""),
            SourceReplacement(block.node.start + mark.contentEnd, block.node.start + mark.sourceEnd, "")), selection, barrier)
    }

    private fun linkAt(projection: DocumentProjection, selection: DocumentSelection): Pair<ProjectedNode, DocumentMark>? =
        selectedNodes(projection, selection).firstNotNullOfOrNull { block -> block.node.marks.firstOrNull {
            it.kind == InlineMark.LINK && selection.start >= block.start + it.start && selection.end <= block.start + it.end
        }?.let { block to it } }

    private fun enter(session: DocumentSession, selection: DocumentSelection, barrier: Boolean): SessionResult {
        if (!selection.isCollapsed) return session.replaceProjectedRange(selection.start, selection.end, "\n", selection, DocumentSelection(selection.start + 1), allowHistoryBarrier = barrier)
        val sourceOffset = session.projection.sourceOffset(selection.anchor)
        val line = selectedLines(session.source, sourceOffset, sourceOffset).single()
        val prefix = parsePrefix(line.text)
        val code = selectedNodes(session.projection, selection).any { it.node.kind == "code_fence" || it.node.kind == "code_block" }
        if (!code && (prefix.listStyle != null || prefix.quote.isNotEmpty())) {
            if (line.text.substring(prefix.contentStart).isBlank()) {
                val newPrefix = when {
                    prefix.listStyle != null && prefix.indent.isNotEmpty() -> parentListPrefix(session.source, line.start, prefix)
                    prefix.listStyle != null -> prefix.quote + prefix.indent
                    prefix.quote.isNotEmpty() -> removeQuoteLevel(prefix.quote) + prefix.indent
                    else -> ""
                }
                return applyPatches(session, listOf(SourceReplacement(line.start, line.start + prefix.contentStart, newPrefix)), selection, barrier, EditOrigin.INPUT)
            }
            val continuation = prefix.quote + prefix.indent + when (prefix.listStyle) {
                DocumentListStyle.UNORDERED -> "${prefix.marker ?: "-"} "
                DocumentListStyle.ORDERED -> "${prefix.ordinal + 1}. "
                DocumentListStyle.TASK -> "- [ ] "
                null -> ""
            }
            return applyPatches(session, listOf(SourceReplacement(sourceOffset, sourceOffset, eol(session.source) + continuation)), selection, barrier, EditOrigin.INPUT,
                caretSource = sourceOffset + eol(session.source).length + continuation.length)
        }
        return session.replaceProjectedRange(selection.start, selection.end, "\n", selection, DocumentSelection(selection.start + 1), allowHistoryBarrier = barrier)
    }

    private fun backspace(session: DocumentSession, selection: DocumentSelection, barrier: Boolean): SessionResult {
        if (!selection.isCollapsed) return session.replaceProjectedRange(selection.start, selection.end, "", selection, DocumentSelection(selection.start), origin = EditOrigin.CUT, allowHistoryBarrier = barrier)
        val position = session.projection.sourceOffset(selection.anchor)
        val line = selectedLines(session.source, position, position).single()
        val prefix = parsePrefix(line.text)
        if (position == line.start + prefix.contentStart && prefix.contentStart > 0) {
            val replacement = when {
                prefix.listStyle != null && prefix.indent.isNotEmpty() -> parentListPrefix(session.source, line.start, prefix)
                prefix.listStyle != null -> prefix.quote + prefix.indent
                prefix.quote.isNotEmpty() -> removeQuoteLevel(prefix.quote) + prefix.indent
                prefix.indent.isNotEmpty() -> prefix.indent.dropLast(minOf(2, prefix.indent.length))
                else -> ""
            }
            return applyPatches(session, listOf(SourceReplacement(line.start, line.start + prefix.contentStart, replacement)), selection, barrier, EditOrigin.INPUT,
                caretSource = line.start + replacement.length)
        }
        if (selection.anchor == 0) return SessionResult.Unchanged
        val previous = session.projection.text.offsetByCodePoints(selection.anchor, -1)
        return session.replaceProjectedRange(previous, selection.anchor, "", selection, DocumentSelection(previous), allowHistoryBarrier = barrier)
    }

    private fun applyShortcut(session: DocumentSession, selection: DocumentSelection, barrier: Boolean): SessionResult {
        if (!selection.isCollapsed || selectionState(session, selection).codeBlock) return SessionResult.Unchanged
        val offset = session.projection.sourceOffset(selection.anchor)
        val line = selectedLines(session.source, offset, offset).single()
        val prefix = session.source.substring(line.start, offset)
        // Native input is escaped as literal text first. Conversion is an explicit source operation.
        val unescaped = prefix.replace(Regex("\\\\([#*+\\[\\]~`>.-])"), "$1")
        if (!Regex("^(#{1,6} |[-+*] |[0-9]+\\. |[-+*] \\[ ?] |> |``` ?)$").matches(unescaped)) return SessionResult.Unchanged
        return applyPatches(session, listOf(SourceReplacement(line.start, offset, unescaped)), selection, barrier, EditOrigin.INPUT,
            caretSource = line.start + unescaped.length, trimEdges = false)
    }

    private fun transformLines(session: DocumentSession, selection: DocumentSelection, barrier: Boolean,
        transform: (String, Int) -> String): SessionResult {
        val sourceStart = session.projection.sourceOffset(selection.start)
        val sourceEnd = session.projection.sourceOffset(selection.end, selection.end > selection.start)
        val lines = selectedLines(session.source, sourceStart, sourceEnd)
        return applyPatches(session, lines.mapIndexedNotNull { index, line ->
            val next = transform(line.text, index)
            if (next == line.text) null else SourceReplacement(line.start, line.end, next)
        }, selection, barrier)
    }

    /** Transaction envelope retains unchanged inner bytes verbatim and trims equal edges. */
    private fun applyPatches(session: DocumentSession, patches: List<SourceReplacement>, selection: DocumentSelection,
        barrier: Boolean, origin: EditOrigin = EditOrigin.FORMAT, caretSource: Int? = null, trimEdges: Boolean = true): SessionResult {
        if (patches.isEmpty()) return SessionResult.Unchanged
        val sorted = patches.sortedWith(compareBy<SourceReplacement> { it.start }.thenBy { it.end })
        if (sorted.zipWithNext().any { (a, b) -> a.end > b.start }) return SessionResult.Rejected("overlapping-format-ranges")
        var from = sorted.first().start
        var to = sorted.last().end
        var replacement = session.source.substring(from, to)
        // At equal boundaries insertions are applied after marker removal in reverse order.
        sorted.asReversed().forEach { patch -> replacement = replacement.replaceRange(patch.start - from, patch.end - from, patch.replacement) }
        val original = session.source.substring(from, to)
        var prefix = 0
        while (trimEdges && prefix < original.length && prefix < replacement.length && original[prefix] == replacement[prefix]) prefix++
        while (!isUtf16Boundary(original, prefix) || !isUtf16Boundary(replacement, prefix)) prefix--
        var suffix = 0
        while (trimEdges && suffix < original.length - prefix && suffix < replacement.length - prefix && original[original.length - suffix - 1] == replacement[replacement.length - suffix - 1]) suffix++
        while (!isUtf16Boundary(original, original.length - suffix) || !isUtf16Boundary(replacement, replacement.length - suffix)) suffix--
        from += prefix; to -= suffix
        replacement = replacement.substring(prefix, replacement.length - suffix)
        if (from == to && replacement.isEmpty()) return SessionResult.Unchanged
        val result = session.replaceSource(from, to, replacement, origin, selectionBefore = selection,
            selectionAfter = selection, allowHistoryBarrier = barrier, requiresFullContext = false)
        if (result is SessionResult.Applied) {
            val target = caretSource?.let { session.projection.projectedOffset(it) }
            if (target != null) {
                session.updateSelection(DocumentSelection(target))
                return result.copy(snapshot = session.captureSnapshot())
            }
        }
        return result
    }

    private fun selectedNodes(projection: DocumentProjection, selection: DocumentSelection): List<ProjectedNode> = projection.nodes.filter {
        !it.node.hidden && if (selection.isCollapsed) selection.anchor in it.start..it.end else selection.end > it.start && selection.start < it.end
    }
    private fun visibleRanges(projection: DocumentProjection, selection: DocumentSelection): List<IntRange> = selectedNodes(projection, selection)
        .filter { it.node.atomic == null }.mapNotNull { node ->
            val start = maxOf(selection.start, node.start); val end = minOf(selection.end, node.end)
            if (start < end) start until end else null
        }
    private fun selectedLines(source: String, start: Int, end: Int): List<SourceLine> {
        val from = source.lastIndexOf('\n', (start - 1).coerceAtLeast(-1)) + 1
        val last = if (end > start && end > 0 && source[end - 1] == '\n') end - 1 else end
        val result = mutableListOf<SourceLine>()
        var cursor = from
        do {
            val newline = source.indexOf('\n', cursor).let { if (it < 0) source.length else it }
            val contentEnd = if (newline > cursor && source[newline - 1] == '\r') newline - 1 else newline
            result += SourceLine(cursor, contentEnd, source.substring(cursor, contentEnd))
            if (newline >= last || newline == source.length) break
            cursor = newline + 1
        } while (cursor <= source.length)
        return result
    }
    private fun parsePrefix(line: String): Prefix {
        val quote = Regex("^(?: {0,3}>[ \\t]?)+").find(line)?.value.orEmpty()
        val rest = line.substring(quote.length)
        val list = Regex("^([ \\t]*)(?:([-+*])|([0-9]+)[.)])[ \\t]+(?:\\[([ xX])] ?)?").find(rest)
        val indent = list?.groups?.get(1)?.value ?: rest.takeWhile { it == ' ' || it == '\t' }
        val style = when { list == null -> null; list.groups[4] != null -> DocumentListStyle.TASK; list.groups[3] != null -> DocumentListStyle.ORDERED; else -> DocumentListStyle.UNORDERED }
        return Prefix(quote, indent, quote.length + (list?.value?.length ?: indent.length), style,
            list?.groups?.get(2)?.value, list?.groups?.get(3)?.value?.toIntOrNull() ?: 1,
            list?.groups?.get(4)?.value?.equals("x", true) == true)
    }
    private fun parentListPrefix(source: String, before: Int, current: Prefix): String {
        val parent = source.substring(0, before).lineSequence().toList().asReversed().firstNotNullOfOrNull { line ->
            parsePrefix(line.removeSuffix("\r")).takeIf { it.listStyle != null && it.indent.length < current.indent.length &&
                it.quote.count { char -> char == '>' } == current.quote.count { char -> char == '>' } }
        }
        val base = parent ?: current.copy(indent = current.indent.dropLast(minOf(2, current.indent.length)))
        val marker = when (base.listStyle) {
            DocumentListStyle.ORDERED -> "${base.ordinal + 1}. "
            DocumentListStyle.TASK -> "${base.marker ?: "-"} [ ] "
            DocumentListStyle.UNORDERED -> "${base.marker ?: "-"} "
            null -> ""
        }
        return base.quote + base.indent + marker
    }
    private fun removeQuoteLevel(quote: String): String = quote.replace(Regex(">[ \\t]*$"), "")
    private fun delimiter(mark: InlineMark, raw: String): String = when (mark) {
        InlineMark.BOLD -> "**"; InlineMark.ITALIC -> "*"; InlineMark.STRIKE -> "~~"
        InlineMark.CODE -> "`".repeat((Regex("`+").findAll(raw).maxOfOrNull { it.value.length } ?: 0) + 1)
        InlineMark.LINK -> ""
    }
    private fun eol(source: String) = if (source.contains("\r\n")) "\r\n" else "\n"
    private fun escapeLabel(text: String) = text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
    private fun escapeDestination(value: String) = value.replace(" ", "%20").replace("(", "%28").replace(")", "%29")
    private data class SourceLine(val start: Int, val end: Int, val text: String)
    private data class Prefix(val quote: String, val indent: String, val contentStart: Int, val listStyle: DocumentListStyle?, val marker: String?, val ordinal: Int, val checked: Boolean)
}
