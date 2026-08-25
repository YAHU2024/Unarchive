package com.unarchive.android.editor

internal enum class MarkdownLiveBlockType {
    HEADING,
    PARAGRAPH,
    LIST,
    QUOTE,
    CODE,
    IMAGE,
    DIVIDER,
    TABLE_SOURCE,
    FRONT_MATTER,
    SOURCE_FALLBACK,
}

internal data class MarkdownLiveBlock(
    val id: String,
    val startOffset: Int,
    val endOffset: Int,
    val markdown: String,
    val type: MarkdownLiveBlockType,
    val editable: Boolean = true,
) {
    init {
        require(startOffset >= 0)
        require(endOffset >= startOffset)
    }
}

/**
 * Immutable source range for one Live Preview edit session.
 *
 * Input methods may dispatch several changes before Compose has redrawn the
 * parent document. Every candidate therefore derives from the original source
 * and range instead of from a range mutated by the previous input callback.
 */
internal data class MarkdownLiveEditSession(
    val originalDocument: String,
    val block: MarkdownLiveBlock,
    val replacement: String,
) {
    val candidateDocument: String
        get() = originalDocument.replaceRange(block.startOffset, block.endOffset, replacement)

    fun withReplacement(value: String): MarkdownLiveEditSession = copy(replacement = value)

    fun acceptsParentDocument(value: String): Boolean {
        val prefix = originalDocument.substring(0, block.startOffset)
        val suffix = originalDocument.substring(block.endOffset)
        return value.startsWith(prefix) && value.endsWith(suffix) && value.length >= prefix.length + suffix.length
    }

    companion object {
        fun start(document: String, block: MarkdownLiveBlock): MarkdownLiveEditSession? {
            if (block.startOffset < 0 || block.endOffset > document.length || block.endOffset < block.startOffset) {
                return null
            }
            if (document.substring(block.startOffset, block.endOffset) != block.markdown) return null
            return MarkdownLiveEditSession(document, block, block.markdown)
        }
    }
}

/**
 * Lossless top-level splitter for Live Preview. It does not parse Markdown
 * semantics or serialize nodes; exact source ranges remain the edit contract.
 */
internal object MarkdownLiveBlockParser {
    fun parse(markdown: String): List<MarkdownLiveBlock> {
        if (markdown.isEmpty()) {
            return listOf(block(0, 0, "", MarkdownLiveBlockType.PARAGRAPH))
        }
        val lines = lines(markdown)
        val blocks = mutableListOf<MarkdownLiveBlock>()
        var index = 0

        if (lines.firstOrNull()?.text?.trim() == "---") {
            val closing = (1 until lines.size).firstOrNull { lines[it].text.trim() == "---" }
            if (closing != null) {
                blocks += sourceBlock(markdown, lines, 0, closing, MarkdownLiveBlockType.FRONT_MATTER, false)
                index = closing + 1
            }
        }

        while (index < lines.size) {
            val line = lines[index]
            if (line.text.isBlank()) {
                index += 1
                continue
            }
            val fence = fenceToken(line.text)
            if (fence != null) {
                var end = index
                while (end + 1 < lines.size) {
                    end += 1
                    if (isClosingFence(lines[end].text, fence)) break
                }
                blocks += sourceBlock(markdown, lines, index, end, MarkdownLiveBlockType.CODE)
                index = end + 1
                continue
            }
            if (isTableStart(lines, index)) {
                val end = untilBlank(lines, index)
                blocks += sourceBlock(markdown, lines, index, end, MarkdownLiveBlockType.TABLE_SOURCE)
                index = end + 1
                continue
            }
            val singleLineType = when {
                HEADING.matches(line.text) -> MarkdownLiveBlockType.HEADING
                DIVIDER.matches(line.text) -> MarkdownLiveBlockType.DIVIDER
                IMAGE_ONLY.matches(line.text) -> MarkdownLiveBlockType.IMAGE
                else -> null
            }
            if (singleLineType != null) {
                blocks += sourceBlock(markdown, lines, index, index, singleLineType)
                index += 1
                continue
            }
            val groupedType = when {
                LIST_ITEM.matches(line.text) -> MarkdownLiveBlockType.LIST
                QUOTE.matches(line.text) -> MarkdownLiveBlockType.QUOTE
                HTML_START.matches(line.text) -> MarkdownLiveBlockType.SOURCE_FALLBACK
                else -> MarkdownLiveBlockType.PARAGRAPH
            }
            var end = index
            while (end + 1 < lines.size && !lines[end + 1].text.isBlank()) {
                val next = lines[end + 1].text
                if (startsIndependentBlock(lines, end + 1)) break
                if (groupedType == MarkdownLiveBlockType.LIST && !LIST_ITEM.matches(next) && !next.startsWith(" ")) break
                if (groupedType == MarkdownLiveBlockType.QUOTE && !QUOTE.matches(next)) break
                end += 1
            }
            blocks += sourceBlock(markdown, lines, index, end, groupedType)
            index = end + 1
        }
        return blocks.ifEmpty { listOf(block(0, 0, "", MarkdownLiveBlockType.PARAGRAPH)) }
    }

    fun replace(markdown: String, block: MarkdownLiveBlock, replacement: String): String {
        require(block.endOffset <= markdown.length) { "block range exceeds Markdown source" }
        require(markdown.substring(block.startOffset, block.endOffset) == block.markdown) {
            "block source is stale"
        }
        return markdown.replaceRange(block.startOffset, block.endOffset, replacement)
    }

    private fun startsIndependentBlock(lines: List<SourceLine>, index: Int): Boolean {
        val text = lines[index].text
        return fenceToken(text) != null || isTableStart(lines, index) || HEADING.matches(text) ||
            DIVIDER.matches(text) || IMAGE_ONLY.matches(text) || LIST_ITEM.matches(text) ||
            QUOTE.matches(text) || HTML_START.matches(text)
    }

    private fun isTableStart(lines: List<SourceLine>, index: Int): Boolean =
        index + 1 < lines.size && lines[index].text.contains('|') && TABLE_SEPARATOR.matches(lines[index + 1].text)

    private fun untilBlank(lines: List<SourceLine>, start: Int): Int {
        var end = start
        while (end + 1 < lines.size && lines[end + 1].text.isNotBlank()) end += 1
        return end
    }

    private fun fenceToken(text: String): String? = FENCE.find(text)?.groupValues?.get(1)

    private fun isClosingFence(text: String, opening: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.takeWhile { it == opening.first() }.length >= opening.length &&
            trimmed.dropWhile { it == opening.first() }.isBlank()
    }

    private fun sourceBlock(
        markdown: String,
        lines: List<SourceLine>,
        startLine: Int,
        endLine: Int,
        type: MarkdownLiveBlockType,
        editable: Boolean = true,
    ): MarkdownLiveBlock {
        val start = lines[startLine].start
        val end = lines[endLine].contentEnd
        return block(start, end, markdown.substring(start, end), type, editable)
    }

    private fun block(
        start: Int,
        end: Int,
        source: String,
        type: MarkdownLiveBlockType,
        editable: Boolean = true,
    ) = MarkdownLiveBlock("${type.name.lowercase()}-$start", start, end, source, type, editable)

    private fun lines(markdown: String): List<SourceLine> = buildList {
        var start = 0
        while (start < markdown.length) {
            val newline = markdown.indexOf('\n', start)
            val physicalEnd = if (newline < 0) markdown.length else newline + 1
            var contentEnd = if (newline < 0) markdown.length else newline
            if (contentEnd > start && markdown[contentEnd - 1] == '\r') contentEnd -= 1
            add(SourceLine(start, contentEnd, physicalEnd, markdown.substring(start, contentEnd)))
            start = physicalEnd
        }
    }

    private data class SourceLine(
        val start: Int,
        val contentEnd: Int,
        val physicalEnd: Int,
        val text: String,
    )

    private val FENCE = Regex("^\\s*(`{3,}|~{3,})")
    private val HEADING = Regex("^ {0,3}#{1,6}(?:\\s+.*|\\s*)$")
    private val DIVIDER = Regex("^ {0,3}(?:(?:-\\s*){3,}|(?:_\\s*){3,}|(?:\\*\\s*){3,})$")
    private val IMAGE_ONLY = Regex("^\\s*!\\[[^]]*]\\([^)]*\\)\\s*$")
    private val LIST_ITEM = Regex("^\\s*(?:[-+*]\\s+|\\d+[.)]\\s+).+")
    private val QUOTE = Regex("^ {0,3}>.*")
    private val HTML_START = Regex("^\\s*</?[A-Za-z][^>]*>.*")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$")
}
