package com.unarchive.android.editor.session

data class SourceReplacement(val start: Int, val end: Int, val replacement: String) {
    val delta: Int get() = replacement.length - (end - start)
}

object SourceTransactionMapper {
    fun plainTextReplacement(projection: DocumentProjection, start: Int, end: Int, replacement: String, source: String): SourceReplacement? {
        if (start !in 0..projection.text.length || end !in start..projection.text.length ||
            !isUtf16Boundary(projection.text, start) || !isUtf16Boundary(projection.text, end) || !isWellFormedUtf16(replacement)) return null
        var sourceStart = projection.sourceOffset(start)
        var sourceEnd = projection.sourceOffset(end, endAffinity = end > start)
        if (start == end) sourceEnd = sourceStart
        var closing = ""
        var opening = ""
        if (start < end) {
            val left = projection.nodes.firstOrNull { !it.node.hidden && start >= it.start && start < it.end }
            val right = projection.nodes.lastOrNull { !it.node.hidden && end > it.start && end <= it.end }
            projection.nodes.forEach { projected ->
                val node = projected.node
                if (node.hidden || node.synthetic) return@forEach
                if (start <= projected.start && end >= projected.end && projected.end > projected.start) {
                    sourceStart = minOf(sourceStart, node.start)
                    sourceEnd = maxOf(sourceEnd, node.end)
                }
                node.marks.forEach { mark ->
                    val a = projected.start + mark.start; val b = projected.start + mark.end
                    if (start <= a && end >= b && start < b && end > a) {
                        sourceStart = minOf(sourceStart, node.start + mark.sourceStart)
                        sourceEnd = maxOf(sourceEnd, node.start + mark.sourceEnd)
                    }
                }
            }
            if (left != null) {
                val localStart = start - left.start
                val localEnd = end - left.start
                val crossed = left.node.marks.filter { localStart > it.start && localStart < it.end && localEnd >= it.end }
                crossed.sortedBy { it.sourceEnd - it.sourceStart }.forEach { mark ->
                    closing += left.node.raw.substring(mark.contentEnd, mark.sourceEnd)
                    sourceEnd = maxOf(sourceEnd, left.node.start + mark.sourceEnd)
                }
                // The visible end of a paragraph precedes hidden closers. Crossing that
                // structural boundary owns the closers as well, never just half a marker.
                if (start == left.end && end > left.end) sourceStart = minOf(sourceStart, left.node.end)
            }
            if (right != null) {
                val localStart = start - right.start
                val localEnd = end - right.start
                val crossed = right.node.marks.filter { localStart <= it.start && localEnd > it.start && localEnd < it.end }
                crossed.sortedByDescending { it.sourceEnd - it.sourceStart }.forEach { mark ->
                    opening += right.node.raw.substring(mark.sourceStart, mark.contentStart)
                    sourceStart = minOf(sourceStart, right.node.start + mark.sourceStart)
                }
            }
        }
        if (!isUtf16Boundary(source, sourceStart) || !isUtf16Boundary(source, sourceEnd) || sourceStart > sourceEnd) return null
        val eol = if (source.contains("\r\n")) "\r\n" else "\n"
        val inCode = projection.nodes.any { block -> start >= block.start && end <= block.end &&
            (block.node.kind == "code_fence" || block.node.kind == "code_block" ||
                block.node.marks.any { it.kind == InlineMark.CODE && start >= block.start + it.start && end <= block.start + it.end }) }
        val escaped = buildString {
            replacement.replace("\r\n", "\n").forEach { char ->
                if (!inCode && char in "\\`*_{}[]<>#~|") append('\\')
                if (char == '\n') append(eol) else append(char)
            }
        }
        var prefix = closing
        var suffix = opening
        if (start < end) {
            val left = projection.nodes.firstOrNull { !it.node.hidden && start >= it.start && start < it.end }
            val right = projection.nodes.lastOrNull { !it.node.hidden && end > it.start && end <= it.end }
            val leftCode = left?.takeIf { it.node.kind == "code_fence" || it.node.kind == "code_block" }
            val rightCode = right?.takeIf { it.node.kind == "code_fence" || it.node.kind == "code_block" }
            if (leftCode != null && rightCode != leftCode) {
                val surviving = leftCode.node.text.substring(0, start - leftCode.start)
                sourceStart = leftCode.node.start
                prefix = inlineCodeLines(surviving, eol)
            }
            if (rightCode != null && leftCode != rightCode) {
                val surviving = rightCode.node.text.substring(end - rightCode.start)
                sourceEnd = rightCode.node.end
                suffix = inlineCodeLines(surviving, eol)
            }
        }
        return SourceReplacement(sourceStart, sourceEnd, prefix + escaped + suffix)
    }

    private fun inlineCodeLines(text: String, eol: String): String = text.split('\n').joinToString(eol) { line ->
        if (line.isEmpty()) "" else {
            val delimiter = "`".repeat((Regex("`+").findAll(line).maxOfOrNull { it.value.length } ?: 0) + 1)
            val padded = if (line.startsWith('`') || line.endsWith('`') || line.startsWith(' ') && line.endsWith(' ')) " $line " else line
            delimiter + padded + delimiter
        }
    }
}

internal fun isUtf16Boundary(text: String, offset: Int): Boolean = offset in 0..text.length &&
    !(offset > 0 && offset < text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())

internal fun isWellFormedUtf16(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        if (text[index].isLowSurrogate()) return false
        if (text[index].isHighSurrogate()) {
            if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) return false
            index++
        }
        index++
    }
    return true
}
