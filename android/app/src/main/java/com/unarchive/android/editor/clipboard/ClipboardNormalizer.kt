package com.unarchive.android.editor.clipboard

import com.unarchive.android.editor.NoteLinkPolicy
import com.unarchive.android.editor.session.DocumentProjection
import com.unarchive.android.editor.session.DocumentSelection
import com.unarchive.android.editor.session.InlineMark
import java.security.MessageDigest

data class ClipboardPayload(val plainText: String, val htmlText: String?, val markdown: String)

/** A bounded-work whitelist parser. Tags are never executed and images are never fetched. */
object ClipboardNormalizer {
    fun normalize(plainText: String?, htmlText: String?, plainOnly: Boolean = false): ClipboardPayload {
        val plain = plainText.orEmpty().replace("\uFFFC", "")
        if (plainOnly || htmlText.isNullOrBlank()) return ClipboardPayload(plain, null, escapeMarkdown(plain))
        if (htmlText.length > 2 * 1024 * 1024 && plainText != null) return ClipboardPayload(plain, null, escapeMarkdown(plain))
        val writer = HtmlWhitelistWriter()
        writer.parse(htmlText)
        val markdown = writer.markdown().replace("\uFFFC", "")
        val text = writer.plainText().replace("\uFFFC", "")
        return ClipboardPayload(text, safeHtml(text), markdown)
    }

    fun selection(source: String, projection: DocumentProjection, selection: DocumentSelection): ClipboardPayload {
        val a = selection.start.coerceIn(0, projection.text.length)
        val b = selection.end.coerceIn(a, projection.text.length)
        val blocks = projection.nodes.filter { it.end > a && it.start < b }
        var from = projection.sourceOffset(a)
        var to = projection.sourceOffset(b, b > a)
        blocks.forEach { block -> block.node.marks.forEach { mark ->
            if (a <= block.start + mark.start && b >= block.start + mark.end) {
                from = minOf(from, block.node.start + mark.sourceStart)
                to = maxOf(to, block.node.start + mark.sourceEnd)
            }
        } }
        val plain = StringBuilder()
        val html = StringBuilder()
        var cursor = a
        blocks.forEach { block ->
            val start = maxOf(a, block.start)
            val end = minOf(b, block.end)
            if (cursor < start) {
                val gap = projection.text.substring(cursor, start)
                plain.append(gap); html.append(escapeHtml(gap).replace("\n", "<br>"))
            }
            val atomic = block.node.atomic
            if (atomic != null) {
                val atomText = if (atomic.kind == "fold") com.unarchive.android.editor.session.DocumentSession(block.node.raw).projection.text else atomic.text
                plain.append(atomText)
                if (atomic.kind == "table") {
                    html.append("<table>")
                    atomic.rows.forEach { row -> html.append("<tr>"); row.forEach { html.append("<td>").append(escapeHtml(it)).append("</td>") }; html.append("</tr>") }
                    html.append("</table>")
                } else html.append(escapeHtml(atomText).replace("\n", "<br>"))
            } else {
                for (offset in start until end) {
                    val atom = block.node.inlineAtoms.firstOrNull { offset == block.start + it.start }
                    plain.append(atom?.node?.text ?: projection.text[offset].toString())
                }
                appendBlockHtml(html, projection, block, start, end)
            }
            cursor = end
        }
        if (cursor < b) { val tail = projection.text.substring(cursor, b); plain.append(tail); html.append(escapeHtml(tail)) }
        val sourceFragment = StringBuilder()
        var sourceCursor = from
        blocks.forEach { block ->
            val start = maxOf(a, block.start); val end = minOf(b, block.end)
            val whole = start == block.start && end == block.end
            val sourceStart = if (whole) block.node.start else projection.sourceOffset(start)
            val sourceEnd = if (whole) block.node.end else projection.sourceOffset(end, end > start)
            if (sourceCursor < sourceStart && sourceFragment.isNotEmpty()) sourceFragment.append(source.substring(sourceCursor, sourceStart))
            if (whole || block.node.atomic != null) sourceFragment.append(block.node.raw)
            else {
                val partialStarts = block.node.marks.filter { start >= block.start + it.start && start < block.start + it.end }.sortedBy { it.start }
                val partialEnds = block.node.marks.filter { end > block.start + it.start && end <= block.start + it.end }.sortedByDescending { it.start }
                partialStarts.forEach { sourceFragment.append(block.node.raw.substring(it.sourceStart, it.contentStart)) }
                sourceFragment.append(source.substring(sourceStart, sourceEnd))
                partialEnds.forEach { sourceFragment.append(block.node.raw.substring(it.contentEnd, it.sourceEnd)) }
            }
            sourceCursor = sourceEnd
        }
        return ClipboardPayload(plain.toString().replace("\uFFFC", ""), html.toString(), sourceFragment.toString().replace("\uFFFC", ""))
    }

    fun fingerprint(payload: ClipboardPayload): String = MessageDigest.getInstance("SHA-256")
        .digest((payload.plainText + "\u0000" + payload.markdown).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun escapeMarkdown(text: String): String = buildString {
        text.replace("\r\n", "\n").forEach { if (it in "\\`*_{}[]<>#~|") append('\\'); append(it) }
    }
    private fun safeHtml(text: String) = escapeHtml(text).replace("\n", "<br>")
    private fun escapeHtml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun openTag(kind: InlineMark, destination: String?): String = when (kind) {
        InlineMark.BOLD -> "<strong>"; InlineMark.ITALIC -> "<em>"; InlineMark.STRIKE -> "<del>"; InlineMark.CODE -> "<code>"
        InlineMark.LINK -> if (destination != null && NoteLinkPolicy.isAllowed(destination)) "<a href=\"${escapeHtml(destination)}\">" else "<span>"
    }
    private fun closeTag(kind: InlineMark): String = when (kind) {
        InlineMark.BOLD -> "</strong>"; InlineMark.ITALIC -> "</em>"; InlineMark.STRIKE -> "</del>"; InlineMark.CODE -> "</code>"; InlineMark.LINK -> "</a>"
    }

    private fun appendBlockHtml(output: StringBuilder, projection: DocumentProjection,
        block: com.unarchive.android.editor.session.ProjectedNode, start: Int, end: Int) {
        val node = block.node
        if (node.kind == "code_fence" || node.kind == "code_block") {
            output.append("<pre>").append(escapeHtml(projection.text.substring(start, end))).append("</pre>")
            return
        }
        if (node.headingLevel > 0) output.append("<h${node.headingLevel}>")
        val quoteDepth = node.lines.maxOfOrNull { it.quoteDepth } ?: 0
        repeat(quoteDepth) { output.append("<blockquote>") }
        val lines = node.lines.filter { block.start + it.end > start && block.start + it.start < end ||
            it.start == it.end && block.start + it.start in start until end }
        val lists = ArrayDeque<CopyListFrame>()
        fun closeList() { val frame = lists.removeLast(); if (frame.openItem) output.append("</li>"); output.append("</${frame.tag}>") }
        if (lines.any { it.listStyle != null }) {
            lines.forEach { line ->
                val a = maxOf(start, block.start + line.start)
                val b = minOf(end, block.start + line.end)
                val style = line.listStyle
                if (style == null) {
                    while (lists.isNotEmpty()) closeList()
                    output.append("<p>"); appendInlineHtml(output, projection, block, a, b); output.append("</p>")
                } else {
                    val tag = if (style == com.unarchive.android.editor.session.DocumentListStyle.ORDERED) "ol" else "ul"
                    while (lists.isNotEmpty() && lists.last().indent > line.indent) closeList()
                    if (lists.isNotEmpty() && lists.last().indent == line.indent && lists.last().tag != tag) closeList()
                    if (lists.isEmpty() || lists.last().indent < line.indent) {
                        output.append('<').append(tag)
                        if (tag == "ol") output.append(" start=\"").append(line.ordinal).append('"')
                        output.append('>'); lists += CopyListFrame(line.indent, tag)
                    } else if (lists.last().openItem) output.append("</li>")
                    output.append("<li>"); lists.last().openItem = true
                    if (style == com.unarchive.android.editor.session.DocumentListStyle.TASK)
                        output.append("<input type=\"checkbox\" disabled").append(if (line.checked) " checked" else "").append('>')
                    appendInlineHtml(output, projection, block, a, b)
                }
            }
            while (lists.isNotEmpty()) closeList()
        } else appendInlineHtml(output, projection, block, start, end)
        repeat(quoteDepth) { output.append("</blockquote>") }
        if (node.headingLevel > 0) output.append("</h${node.headingLevel}>")
    }

    private fun appendInlineHtml(output: StringBuilder, projection: DocumentProjection,
        block: com.unarchive.android.editor.session.ProjectedNode, start: Int, end: Int) {
        val marks = block.node.marks.filter { block.start + it.end > start && block.start + it.start < end }
        val openings = marks.groupBy { maxOf(start, block.start + it.start) }
        val closings = marks.groupBy { minOf(end, block.start + it.end) }
        for (offset in start until end) {
            closings[offset]?.sortedByDescending { it.start }?.forEach { output.append(closeTag(it.kind)) }
            openings[offset]?.sortedByDescending { it.end }?.forEach { output.append(openTag(it.kind, it.destination)) }
            val char = projection.text[offset]
            val atom = block.node.inlineAtoms.firstOrNull { offset == block.start + it.start }
            output.append(if (atom != null) escapeHtml(atom.node.text) else if (char == '\n') "<br>" else escapeHtml(char.toString()))
        }
        closings[end]?.sortedByDescending { it.start }?.forEach { output.append(closeTag(it.kind)) }
    }
    private data class CopyListFrame(val indent: Int, val tag: String, var openItem: Boolean = false)

    private class HtmlWhitelistWriter {
        private val source = StringBuilder()
        private val plain = StringBuilder()
        private val lists = ArrayDeque<ListFrame>()
        private val inlineClosures = ArrayDeque<Pair<String, String>>()
        private var pre = false
        private var skipped: String? = null
        private var tableDepth = 0
        private var cellCount = 0

        fun parse(html: String) {
            var cursor = 0
            while (cursor < html.length) {
                if (html[cursor] != '<') {
                    val end = html.indexOf('<', cursor).let { if (it < 0) html.length else it }
                    if (skipped == null) text(decodeEntities(html.substring(cursor, end)))
                    cursor = end; continue
                }
                if (html.startsWith("<!--", cursor)) {
                    cursor = html.indexOf("-->", cursor + 4).let { if (it < 0) html.length else it + 3 }; continue
                }
                var end = cursor + 1
                var quote: Char? = null
                while (end < html.length) {
                    val char = html[end]
                    if (quote == null && (char == '\'' || char == '"')) quote = char
                    else if (quote == char) quote = null
                    else if (quote == null && char == '>') break
                    end++
                }
                if (end == html.length) { if (skipped == null) text(html.substring(cursor)); break }
                tag(html.substring(cursor + 1, end))
                cursor = end + 1
            }
            while (inlineClosures.isNotEmpty()) source.append(inlineClosures.removeLast().second)
            if (pre) { source.append("\n```"); pre = false }
        }

        private fun tag(raw: String) {
            val closing = raw.trimStart().startsWith('/')
            val clean = raw.trim().removePrefix("/")
            val name = clean.takeWhile { it.isLetterOrDigit() }.lowercase()
            if (name.isEmpty()) return
            if (name in setOf("script", "style", "iframe", "object", "svg", "noscript")) {
                if (closing && skipped == name) skipped = null else if (!closing && skipped == null) skipped = name
                return
            }
            if (skipped != null) return
            if (tableDepth > 0 && name !in setOf("table", "tr", "td", "th", "br")) return
            if (pre && name !in setOf("pre", "br")) return
            when (name) {
                "br" -> newline(1)
                "p", "div", "section", "article" -> newline(if (tableDepth > 0) 1 else 2)
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    newline(2)
                    if (!closing) source.append("#".repeat(name.last().digitToInt())).append(' ')
                }
                "blockquote" -> { newline(2); if (!closing) source.append("> ") }
                "ul", "ol" -> {
                    newline(1)
                    if (closing) { if (lists.isNotEmpty()) lists.removeLast(); newline(if (lists.isEmpty()) 2 else 1) }
                    else lists += ListFrame(name == "ol", attribute(clean, "start")?.toIntOrNull()?.coerceAtLeast(1) ?: 1)
                }
                "li" -> {
                    newline(1)
                    if (!closing) {
                        val list = lists.lastOrNull()
                        source.append("  ".repeat((lists.size - 1).coerceAtLeast(0)))
                        if (list?.ordered == true) source.append(list.next++).append(". ") else source.append("- ")
                    }
                }
                "pre" -> {
                    if (!closing) { newline(2); source.append("```\n"); pre = true }
                    else { source.append("\n```"); pre = false; newline(2) }
                }
                "b", "strong" -> inline(name, "**", closing)
                "i", "em" -> inline(name, "*", closing)
                "s", "strike", "del" -> inline(name, "~~", closing)
                "code" -> inline(name, "`", closing)
                "a" -> {
                    if (closing) closeInline("a")
                    else {
                        val href = attribute(clean, "href")?.let(::decodeEntities)
                        if (href != null && NoteLinkPolicy.isAllowed(href)) {
                            source.append('['); inlineClosures += "a" to "](${href.replace(" ", "%20").replace("(", "%28").replace(")", "%29")})"
                        }
                    }
                }
                "img" -> if (!closing) attribute(clean, "alt")?.let { text(decodeEntities(it)) }
                "input" -> if (!closing && attribute(clean, "type")?.equals("checkbox", true) == true && lists.isNotEmpty()) {
                    val checked = Regex("(?i)(?:^|\\s)checked(?:\\s|=|$)").containsMatchIn(clean)
                    source.append(if (checked) "[x] " else "[ ] ")
                    plain.append(if (checked) "☑ " else "☐ ")
                }
                "table" -> { newline(2); tableDepth += if (closing) -1 else 1; tableDepth = tableDepth.coerceAtLeast(0); if (closing) newline(2) }
                "tr" -> { newline(1); cellCount = 0 }
                "td", "th" -> if (!closing) { if (cellCount++ > 0) { source.append('\t'); plain.append('\t') } }
            }
        }
        private fun inline(name: String, delimiter: String, closing: Boolean) {
            if (closing) closeInline(name) else { source.append(delimiter); inlineClosures += name to delimiter }
        }
        private fun closeInline(name: String) {
            if (inlineClosures.none { it.first == name }) return
            while (inlineClosures.isNotEmpty()) {
                val last = inlineClosures.removeLast(); source.append(last.second)
                if (last.first == name) break
            }
        }
        private fun text(text: String) {
            if (text.isEmpty()) return
            val value = if (pre) text else text.replace(Regex("[\\t\\r\\n ]+"), " ")
            if (!pre && value.isBlank() && (source.isEmpty() || source.last() == '\n')) return
            source.append(if (pre) value else escapeMarkdown(value)); plain.append(value)
        }
        private fun newline(count: Int) {
            if (source.isNotEmpty()) {
                while (source.isNotEmpty() && source.last() == ' ') source.setLength(source.length - 1)
                val existing = source.takeLastWhile { it == '\n' }.length
                repeat((count - existing).coerceAtLeast(0)) { source.append('\n') }
            }
            if (plain.isNotEmpty()) {
                while (plain.isNotEmpty() && plain.last() == ' ') plain.setLength(plain.length - 1)
                val existing = plain.takeLastWhile { it == '\n' }.length
                repeat((count - existing).coerceAtLeast(0)) { plain.append('\n') }
            }
        }
        fun markdown() = source.toString().trim('\n')
        fun plainText() = plain.toString().trim('\n')
        private data class ListFrame(val ordered: Boolean, var next: Int)
    }

    private fun attribute(tag: String, name: String): String? = Regex("(?i)(?:^|\\s)${name}\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        .find(tag)?.let { match -> match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value }

    private fun decodeEntities(text: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[A-Za-z]+);").replace(text) { match ->
        val entity = match.groupValues[1]
        when (entity) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
            else -> {
                val codePoint = if (entity.startsWith("#x", true)) entity.drop(2).toIntOrNull(16) else if (entity.startsWith('#')) entity.drop(1).toIntOrNull() else null
                if (codePoint != null && Character.isValidCodePoint(codePoint) && codePoint !in 0xD800..0xDFFF && codePoint != 0) String(Character.toChars(codePoint)) else match.value
            }
        }
    }
}
