package com.unarchive.android.card

/** Result of applying the controlled, reversible Markdown projection back to a note. */
data class NoteMarkdownWritebackResult(
    val document: NoteDocument,
    val projection: NoteStructuredProjection,
    val status: NoteProjectionStatus,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Parses only the Markdown shapes emitted by [NoteMarkdownProjection].
 *
 * Markdown remains the source of truth. This parser updates only fields which
 * have a safe one-to-one mapping to structured data; source transcript,
 * timestamps, links, assets, relations, and generation metadata are retained
 * from the existing document. Unsupported nodes are reported and kept in
 * [NoteStructuredProjection.unknownMarkdown] byte-for-byte (including CRLF).
 */
object NoteMarkdownWriteback {
    fun apply(document: NoteDocument, markdown: String): NoteMarkdownWritebackResult {
        return try {
            Parser(document, markdown).parse()
        } catch (error: Throwable) {
            return NoteMarkdownWritebackResult(
                document = document,
                projection = NoteStructuredProjection(
                    recognizedBlockIds = document.blocks.map { it.id },
                    unknownMarkdown = markdown,
                ),
                status = NoteProjectionStatus.FAILED,
                warnings = emptyList(),
                error = safeMessage(error),
            )
        }
    }

    private class Parser(
        private val original: NoteDocument,
        private val markdown: String,
    ) {
        private val lines = splitLines(markdown)
        private val warnings = linkedSetOf<String>()
        private val unknown = StringBuilder()
        private val recognized = linkedSetOf<String>()

        fun parse(): NoteMarkdownWritebackResult {
            val bodyStart = frontMatterBodyStart(lines)
            validateFrontMatter(lines, bodyStart)
            val body = lines.drop(bodyStart)
            val topSections = mutableListOf<Section>()
            var current: Section? = null
            var h1Title: String? = null
            var h1Seen = 0
            var fenced = false
            var index = 0

            while (index < body.size) {
                val line = body[index]
                val value = line.text.trimEnd()
                if (value.trimStart().startsWith("```")) {
                    current?.lines?.add(line) ?: addUnknown(line)
                    fenced = !fenced
                    index += 1
                    continue
                }
                if (fenced) {
                    current?.lines?.add(line) ?: addUnknown(line)
                    index += 1
                    continue
                }
                when {
                    value.matches(H1_PATTERN) -> {
                        h1Seen += 1
                        if (h1Seen == 1) {
                            h1Title = value.removePrefix("#").trim()
                        } else {
                            addUnknown(line)
                            warn("发现重复的一级标题")
                        }
                    }
                    value.matches(H2_PATTERN) -> {
                        current = Section(value.removePrefix("##").trim(), mutableListOf(), line)
                        topSections += current
                    }
                    else -> {
                        // Formatting whitespace outside a section is emitted by
                        // the renderer and is not an unknown node.
                        if (current != null || line.text.isNotBlank()) {
                            current?.lines?.add(line) ?: addUnknown(line)
                        }
                    }
                }
                index += 1
            }

            if (h1Seen == 0) warn("缺少一级标题，保留原结构化标题")
            val title = h1Title?.takeIf { it.isNotBlank() } ?: original.title
            if (h1Title != null && h1Title!!.isBlank()) warn("一级标题为空，保留原结构化标题")

            val summarySections = topSections.filter { it.name == "摘要" }
            val keyPointSections = topSections.filter { it.name == "关键要点" }
            val storySections = topSections.filter { it.name == "故事线" }
            val userNoteSections = topSections.filter { it.name == "我的想法" }
            val tagSections = topSections.filter { it.name == "标签" }
            val transcriptSections = topSections.filter { it.name == "转录全文" }
            val known = setOf("摘要", "关键要点", "故事线", "我的想法", "标签", "转录全文")
            topSections.filter { it.name !in known }.forEach { section ->
                addUnknownHeading(section)
                section.lines.forEach(::addUnknown)
                warn("发现无法映射的 Markdown 区块：${section.name}")
            }
            if (transcriptSections.size > 1) warn("发现重复的转录全文区块，已保留来源转录")
            if (summarySections.size > 1) warn("发现重复的摘要区块")
            if (keyPointSections.size > 1) warn("发现重复的关键要点区块")
            if (storySections.size > 1) warn("发现重复的故事线区块")
            if (userNoteSections.size > 1) warn("发现重复的我的想法区块")
            if (tagSections.size > 1) warn("发现重复的标签区块")
            transcriptSections.firstOrNull()?.let(::validateTranscript)

            var blocks = original.blocks
            blocks = applySummaries(blocks, summarySections.firstOrNull())
            blocks = applyKeyPoints(blocks, keyPointSections.firstOrNull())
            blocks = applyChapters(blocks, storySections.firstOrNull())
            blocks = applyUserNotes(blocks, userNoteSections.firstOrNull())
            val tags = applyTags(blocks, tagSections.firstOrNull())
            blocks = tags.blocks

            // Any section omitted from a generated projection is intentionally
            // left unchanged. This avoids interpreting a partial/hand-written
            // document as an instruction to delete protected structured data.
            recognized += original.blocks.map { it.id }
            val updated = original.copy(
                title = title,
                blocks = blocks,
                tags = tags.tags,
                editing = original.editing.copy(dirty = false, lastSaveError = null),
            )
            val status = if (warnings.isEmpty() && unknown.isEmpty()) {
                NoteProjectionStatus.CURRENT
            } else {
                NoteProjectionStatus.PARTIAL
            }
            return NoteMarkdownWritebackResult(
                document = updated,
                projection = NoteStructuredProjection(
                    // IDs follow the persisted structured order. The parser
                    // may visit sections in a different order (tags can be
                    // rendered before a later user-note), but writeback must
                    // never reorder the domain blocks.
                    recognizedBlockIds = updated.blocks.map { it.id },
                    unknownMarkdown = unknown.toString(),
                ),
                status = status,
                warnings = warnings.toList(),
            )
        }

        private fun applySummaries(blocks: List<NoteBlock>, section: Section?): List<NoteBlock> {
            if (section == null) return blocks
            val unsupported = unsupportedLineIndexes(section.lines)
            section.lines.filterIndexed { index, _ -> index in unsupported }.forEach {
                addUnknown(it)
                warn("摘要区块包含仅 Markdown 可编辑的节点")
            }
            val text = contentText(section.lines.filterIndexed { index, _ -> index !in unsupported })
            if (text.isBlank()) {
                warn("摘要为空，保留原摘要")
                return blocks
            }
            val existing = blocks.filter { it.type == NoteBlockType.SUMMARY }
            val target = existing.firstOrNull()
            if (target == null) {
                val created = NoteBlock(
                    id = stableId("summary", 0, text),
                    type = NoteBlockType.SUMMARY,
                    origin = NoteBlockOrigin.USER,
                    text = text,
                )
                return blocks + created
            }
            recognized += target.id
            return blocks.map { if (it.id == target.id) it.copy(text = text) else it }
        }

        private fun applyKeyPoints(blocks: List<NoteBlock>, section: Section?): List<NoteBlock> {
            if (section == null) return blocks
            val values = mutableListOf<String>()
            section.lines.forEach { line ->
                val value = line.text.trim()
                if (value.isBlank()) return@forEach
                if (value.startsWith("- ") && value.substring(2).isNotBlank()) {
                    val text = value.substring(2).trim()
                    if (isUnsupportedLine(text)) addUnknown(line) else values += text
                } else {
                    addUnknown(line)
                    warn("关键要点区块包含无法映射的行")
                }
            }
            val existing = blocks.filter { it.type == NoteBlockType.KEY_POINT }
            if (values.isEmpty()) {
                if (existing.isNotEmpty()) warn("关键要点为空，保留原要点")
                else warn("关键要点区块为空")
            }
            val used = existing.take(values.size).mapIndexed { index, block ->
                recognized += block.id
                block.id to values[index]
            }.toMap()
            val result = blocks.map { block ->
                used[block.id]?.let { block.copy(text = it) } ?: block
            }.toMutableList()
            values.drop(existing.size).forEachIndexed { index, value ->
                result += NoteBlock(
                    id = stableId("key-point", existing.size + index, value),
                    type = NoteBlockType.KEY_POINT,
                    origin = NoteBlockOrigin.USER,
                    text = value,
                )
            }
            if (values.size < existing.size && values.isNotEmpty()) {
                warn("关键要点数量减少，未删除未映射的原要点")
            }
            return result
        }

        private fun applyChapters(blocks: List<NoteBlock>, section: Section?): List<NoteBlock> {
            if (section == null) return blocks
            val chapters = parseChapters(section.lines)
            val existing = blocks.filter { it.type == NoteBlockType.CHAPTER }
            val result = blocks.toMutableList()
            chapters.forEachIndexed { index, chapter ->
                val target = existing.getOrNull(index)
                if (target == null) {
                    if (chapter.assetPaths.isNotEmpty()) {
                        warn("新章节的图片引用属于来源字段，未写入结构化资产")
                    }
                    if (chapter.points.isNotEmpty()) {
                        warn("新章节的时间点属于来源字段，未写入结构化时间点")
                    }
                    result += NoteBlock(
                        id = stableId("chapter", index, "${chapter.startMs}|${chapter.endMs}|${chapter.title}"),
                        type = NoteBlockType.CHAPTER,
                        origin = NoteBlockOrigin.USER,
                        title = chapter.title,
                        text = chapter.description,
                        startMs = chapter.startMs,
                        endMs = chapter.endMs,
                        sourceRef = NoteSourceRef(
                            url = original.source.canonicalUrl,
                            startMs = chapter.startMs,
                            endMs = chapter.endMs,
                            timingAccuracy = original.source.timingAccuracy,
                        ),
                    )
                } else {
                    recognized += target.id
                    val expectedAssets = target.assetRefs.mapNotNull { assetId ->
                        original.assets.firstOrNull { it.assetId == assetId }?.relativePath
                    }
                    if (chapter.assetPaths != expectedAssets) {
                        warn("章节图片引用属于来源字段，已保留原值")
                    }
                    val expectedPoints = target.points.map { point ->
                        PointToken(point.timestampMs, timestampUrl(original.source.canonicalUrl, point.timestampMs), point.text)
                    }
                    if (chapter.points != expectedPoints) {
                        warn("章节时间点属于来源字段，已保留原值")
                    }
                    val expectedStart = target.startMs
                    val expectedEnd = target.endMs
                    val start = if (chapter.startMs == expectedStart && chapter.endMs == expectedEnd) {
                        chapter.startMs
                    } else {
                        warn("章节时间戳属于来源字段，已保留原值")
                        expectedStart ?: chapter.startMs
                    }
                    val end = expectedEnd ?: chapter.endMs
                    result.replaceAll { block ->
                        if (block.id == target.id) block.copy(
                            title = chapter.title,
                            text = chapter.description,
                            startMs = start,
                            endMs = end,
                        ) else block
                    }
                }
            }
            if (chapters.size < existing.size && chapters.isNotEmpty()) {
                warn("故事线章节数量减少，未删除未映射的原章节")
            }
            return result
        }

        private fun applyUserNotes(blocks: List<NoteBlock>, section: Section?): List<NoteBlock> {
            if (section == null) return blocks
            val unsupported = unsupportedLineIndexes(section.lines)
            section.lines.filterIndexed { index, _ -> index in unsupported }.forEach {
                addUnknown(it)
                warn("我的想法区块包含仅 Markdown 可编辑的节点")
            }
            val text = contentText(section.lines.filterIndexed { index, _ -> index !in unsupported })
            if (text.isBlank()) {
                warn("我的想法为空，保留原内容")
                return blocks
            }
            val target = blocks.firstOrNull { it.type == NoteBlockType.USER_NOTE }
            if (target == null) {
                return blocks + NoteBlock(
                    id = stableId("user-note", 0, text),
                    type = NoteBlockType.USER_NOTE,
                    origin = NoteBlockOrigin.USER,
                    text = text,
                )
            }
            recognized += target.id
            return blocks.map { if (it.id == target.id) it.copy(text = text) else it }
        }

        private data class TagsResult(val blocks: List<NoteBlock>, val tags: List<String>)

        private fun applyTags(blocks: List<NoteBlock>, section: Section?): TagsResult {
            if (section == null) return TagsResult(blocks, original.tags)
            val tags = mutableListOf<String>()
            section.lines.forEach { line ->
                val value = line.text.trim()
                if (value.isBlank()) return@forEach
                if (value.startsWith("- ") && value.substring(2).trim().isNotBlank()) {
                    tags += value.substring(2).trim()
                } else {
                    addUnknown(line)
                    warn("标签区块包含无法映射的行")
                }
            }
            val normalized = tags.distinct()
            if (normalized.size != tags.size) warn("标签重复，已保留一份")
            val existing = blocks.firstOrNull { it.type == NoteBlockType.TAG_LIST }
            if (normalized.isEmpty()) {
                // Clearing tags is a safe, explicit operation; TAG_LIST is
                // removed because the domain type forbids an empty tag block.
                existing?.let { recognized += it.id }
                return TagsResult(blocks.filterNot { it.type == NoteBlockType.TAG_LIST }, emptyList())
            }
            if (existing == null) {
                return TagsResult(
                    blocks + NoteBlock(
                        id = stableId("tags", 0, normalized.joinToString("|")),
                        type = NoteBlockType.TAG_LIST,
                        origin = NoteBlockOrigin.USER,
                        tags = normalized,
                    ),
                    normalized,
                )
            }
            recognized += existing.id
            return TagsResult(
                blocks.map { if (it.id == existing.id) it.copy(tags = normalized) else it },
                normalized,
            )
        }

        private fun parseChapters(lines: List<Line>): List<Chapter> {
            val chapters = mutableListOf<Chapter>()
            var current: MutableChapter? = null
            var fenced = false
            fun finish() {
                current?.let {
                    chapters += Chapter(
                        it.startMs,
                        it.endMs,
                        it.title,
                        contentText(it.description),
                        it.assetPaths.toList(),
                        it.points.toList(),
                    )
                }
                current = null
            }
            lines.forEach { line ->
                val value = line.text.trimEnd()
                if (value.trimStart().startsWith("```")) {
                    addUnknown(line)
                    warn("章节说明包含仅 Markdown 可编辑的节点")
                    fenced = !fenced
                    return@forEach
                }
                if (fenced) {
                    addUnknown(line)
                    return@forEach
                }
                val match = CHAPTER_PATTERN.matchEntire(value)
                when {
                    match != null -> {
                        finish()
                        val start = parseTimestamp(match.groupValues[1])
                        val end = parseTimestamp(match.groupValues[2])
                        val title = match.groupValues[3].trim()
                        if (start == null || end == null || end < start || title.isBlank()) {
                            addUnknown(line)
                            warn("章节标题或时间戳无法安全解析")
                        } else {
                            current = MutableChapter(start, end, title, mutableListOf())
                        }
                    }
                    current == null -> {
                        if (value.isNotBlank()) {
                            addUnknown(line)
                            warn("故事线包含未归属章节的内容")
                        }
                    }
                    value.startsWith("![](") && value.endsWith(")") -> {
                        // Asset references are protected. Validate the shape,
                        // but never rewrite the existing asset association.
                        val path = value.removePrefix("![](").removeSuffix(")")
                        if (path.isBlank() || path.contains("..")) {
                            addUnknown(line)
                            warn("章节图片引用无法安全映射")
                        }
                        current!!.assetPaths += path
                    }
                    value.matches(POINT_PATTERN) -> {
                        val pointMatch = POINT_PATTERN.matchEntire(value)
                        val timestamp = pointMatch?.groupValues?.getOrNull(1)?.let(::parseTimestamp)
                        val url = pointMatch?.groupValues?.getOrNull(2).orEmpty()
                        val text = pointMatch?.groupValues?.getOrNull(3).orEmpty()
                        if (timestamp == null || text.isBlank()) {
                            addUnknown(line)
                            warn("章节时间点无法安全解析")
                        } else {
                            current!!.points += PointToken(timestamp, url, text)
                        }
                    }
                    value.startsWith("- ") -> {
                        addUnknown(line)
                        warn("故事线包含无法映射的列表项")
                    }
                    else -> {
                        if (isUnsupportedLine(value)) {
                            addUnknown(line)
                            warn("章节说明包含仅 Markdown 可编辑的节点")
                        }
                        current!!.description += line
                    }
                }
            }
            finish()
            return chapters
        }

        private fun contentText(lines: List<Line>): String {
            if (lines.isEmpty()) return ""
            val values = lines.map { it.text }
            var start = 0
            var end = values.size
            while (start < end && values[start].isBlank()) start += 1
            while (end > start && values[end - 1].isBlank()) end -= 1
            return values.subList(start, end).joinToString("\n")
        }

        private fun isUnsupportedLine(value: String): Boolean =
            value.contains("```") || TABLE_PATTERN.matches(value) || HTML_PATTERN.matches(value)

        private fun unsupportedLineIndexes(lines: List<Line>): Set<Int> {
            val indexes = linkedSetOf<Int>()
            var fenced = false
            lines.forEachIndexed { index, line ->
                val value = line.text.trim()
                if (fenced || value.startsWith("```")) {
                    indexes += index
                    if (value.startsWith("```")) fenced = !fenced
                } else if (TABLE_PATTERN.matches(value) || HTML_PATTERN.matches(value)) {
                    indexes += index
                }
            }
            return indexes
        }

        private fun addUnknown(line: Line) {
            unknown.append(line.raw)
        }

        private fun addUnknownHeading(section: Section) {
            val heading = section.nameLine
            if (heading != null) unknown.append(heading.raw)
        }

        private fun warn(message: String) {
            warnings += message
        }

        private fun validateFrontMatter(lines: List<Line>, bodyStart: Int) {
            if (bodyStart <= 1 || lines.firstOrNull()?.text?.trim() != "---") return
            val allowed = setOf("title", "author", "source", "timing", "content_revision", "updated", "tags")
            lines.subList(1, bodyStart - 1).forEach { line ->
                val value = line.text.trim()
                if (value.isBlank()) return@forEach
                val key = value.substringBefore(':', missingDelimiterValue = "").trim()
                if (key.isBlank() || key !in allowed) {
                    addUnknown(line)
                    warn("发现无法映射的 front matter 字段")
                    return@forEach
                }
                if (key == "source" && !value.substringAfter(':').trim().equals(original.source.canonicalUrl)) {
                    addUnknown(line)
                    warn("来源 URL 属于受保护字段，已保留原值")
                }
                if (key == "timing" && !value.substringAfter(':').trim().equals(original.source.timingAccuracy.name.lowercase())) {
                    addUnknown(line)
                    warn("时间精度属于受保护字段，已保留原值")
                }
            }
        }

        private fun validateTranscript(section: Section) {
            val expected = original.sourceTranscript
            var index = 0
            section.lines.forEach { line ->
                val value = line.text.trim()
                if (value.isBlank() || value.startsWith("> 云端转写句子切分")) return@forEach
                val match = TRANSCRIPT_LINE_PATTERN.matchEntire(value)
                if (match == null) {
                    addUnknown(line)
                    warn("转录全文包含无法映射的内容，已保留来源转录")
                    return@forEach
                }
                val timestamp = parseTimestamp(match.groupValues[1])
                val text = match.groupValues[3]
                val expectedSegment = expected.getOrNull(index)
                val expectedUrl = expectedSegment?.let { timestampUrl(original.source.canonicalUrl, it.startMs) }
                if (timestamp == null || expectedSegment == null ||
                    timestamp != expectedSegment.startMs ||
                    match.groupValues[2] != expectedUrl || text != expectedSegment.text
                ) {
                    addUnknown(line)
                    warn("转录全文属于来源字段，已保留原值")
                }
                index += 1
            }
            if (index != expected.size) warn("转录全文段落数量不一致，已保留来源转录")
        }

        private fun timestampUrl(canonicalUrl: String, startMs: Long): String {
            val separator = if (canonicalUrl.contains('?')) "&" else "?"
            return "$canonicalUrl${separator}t=${(startMs / 1_000L).coerceAtLeast(0L)}"
        }

        private data class Section(val name: String, val lines: MutableList<Line>, val nameLine: Line? = null)
        private data class Chapter(
            val startMs: Long,
            val endMs: Long,
            val title: String,
            val description: String,
            val assetPaths: List<String>,
            val points: List<PointToken>,
        )
        private data class MutableChapter(
            val startMs: Long,
            val endMs: Long,
            val title: String,
            val description: MutableList<Line>,
            val assetPaths: MutableList<String> = mutableListOf(),
            val points: MutableList<PointToken> = mutableListOf(),
        )
        private data class PointToken(val timestampMs: Long, val url: String, val text: String)
    }

    private data class Line(val text: String, val raw: String)

    private val H1_PATTERN = Regex("^#[ \\t].*")
    private val H2_PATTERN = Regex("^##(?!#)[ \\t].*")
    private val CHAPTER_PATTERN = Regex(
        "^###[ \\t]+([0-9]{1,2}(?::[0-9]{2}){1,2})[ \\t]*[–-][ \\t]*" +
            "([0-9]{1,2}(?::[0-9]{2}){1,2})[ \\t]+(.+)$",
    )
    private val POINT_PATTERN = Regex("^-[ \\t]+\\[([0-9:]+)]\\(([^)]*)\\)[ \\t]+(.+)$")
    private val TRANSCRIPT_LINE_PATTERN = Regex("^\\[([0-9:]+)]\\(([^)]*)\\)[ \\t]+(.+)$")
    private val TABLE_PATTERN = Regex("^\\s*\\|.*\\|\\s*$")
    private val HTML_PATTERN = Regex("^\\s*</?[A-Za-z][^>]*>.*$")

    private fun frontMatterBodyStart(lines: List<Line>): Int {
        if (lines.isEmpty() || lines[0].text.trim() != "---") return 0
        val closing = lines.drop(1).indexOfFirst { it.text.trim() == "---" }
        return if (closing < 0) {
            0
        } else {
            closing + 2
        }
    }

    private fun splitLines(value: String): List<Line> {
        if (value.isEmpty()) return emptyList()
        val result = mutableListOf<Line>()
        var start = 0
        var index = 0
        while (index < value.length) {
            if (value[index] == '\n') {
                val end = index + 1
                val raw = value.substring(start, end)
                result += Line(raw.removeSuffix("\n").removeSuffix("\r"), raw)
                start = end
            }
            index += 1
        }
        if (start < value.length) result += Line(value.substring(start), value.substring(start))
        return result
    }

    private fun parseTimestamp(value: String): Long? {
        val parts = value.split(':')
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.toLongOrNull() ?: return null }
        if (numbers.drop(1).any { it !in 0..59 }) return null
        val seconds = when (parts.size) {
            2 -> numbers[0] * 60L + numbers[1]
            else -> numbers[0] * 3_600L + numbers[1] * 60L + numbers[2]
        }
        return seconds.takeIf { it >= 0L && it <= Long.MAX_VALUE / 1_000L }?.times(1_000L)
    }

    private fun stableId(prefix: String, index: Int, value: String): String {
        val digest = NoteContent.markdownFingerprint("$prefix|$index|$value")
        return "$prefix-${digest.take(16)}"
    }

    private fun safeMessage(error: Throwable): String = error.message ?: error::class.java.simpleName
}
