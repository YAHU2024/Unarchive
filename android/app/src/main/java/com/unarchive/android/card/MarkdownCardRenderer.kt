package com.unarchive.android.card

import com.unarchive.android.result.StoredVideoResult
import com.unarchive.android.result.asTimestamp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Renders a [StoredVideoResult] into an Obsidian-friendly Markdown knowledge card.
 *
 * First version renders only the base layer: YAML frontmatter + title + the full
 * transcript with clickable Bilibili `?t=` timestamp links. Summary and story line
 * are added later by the LLM enhancement layer.
 */
object MarkdownCardRenderer {
    private const val MAX_FILE_NAME_LENGTH = 80
    private val ILLEGAL_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|]")

    fun render(
        result: StoredVideoResult,
        analysis: CardAnalysis? = null,
        screenshots: List<String>? = null,
        screenshotPaths: List<String>? = null,
    ): String = buildString {
        appendLine("---")
        appendLine("title: ${yamlString(result.title)}")
        appendLine("author: ${yamlString(result.ownerName)}")
        appendLine("source: ${result.canonicalUrl}")
        appendLine("transcribed: ${formatDate(result.updatedAtEpochMs)}")
        appendLine("timing: ${result.timingAccuracy.name.lowercase()}")
        appendLine("---")
        appendLine()
        appendLine("# ${result.title}")
        appendLine()
        if (analysis != null) {
            appendLine("## 摘要")
            appendLine(analysis.summary)
            appendLine()
            if (analysis.chapters.isNotEmpty()) {
                appendLine("## 故事线")
                analysis.chapters.forEachIndexed { index, chapter ->
                    appendLine("### ${chapter.startMs.asTimestamp()}–${chapter.endMs.asTimestamp()} ${chapter.title}")
                    screenshotPaths?.getOrNull(index)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { appendLine("![]($it)") }
                        ?: screenshots?.getOrNull(index)
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { appendLine("![](data:image/jpeg;base64,$it)") }
                    chapter.points.forEach { point ->
                        appendLine("- ${timestampLink(result.canonicalUrl, point.timestampMs)} ${point.text}")
                    }
                }
                appendLine()
            }
            if (analysis.keyPoints.isNotEmpty()) {
                appendLine("## 关键要点")
                analysis.keyPoints.forEach { appendLine("- $it") }
                appendLine()
            }
        }
        appendLine("## 转录全文")
        if (result.timingAccuracy == com.unarchive.android.asr.TranscriptTimingAccuracy.ESTIMATED) {
            appendLine("> 云端转写句子切分，时间戳为估算值。")
            appendLine()
        }
        result.segments.forEach { segment ->
            appendLine("${timestampLink(result.canonicalUrl, segment.startMs)} ${segment.text}")
        }
    }.trimEnd()

    /** Returns a safe `.md` file name derived from the video title. */
    fun fileName(result: StoredVideoResult): String = fileName(result.title)

    fun fileName(title: String): String = "${safeFileName(title)}.md"

    data class EmbeddedAssetsResult(
        val markdown: String,
        val requestedCount: Int,
        val embeddedCount: Int,
        val embeddedBytes: Long,
        val missingCount: Int,
        val missingPaths: List<String> = emptyList(),
        /** The share/sync variant replaces unavailable images with their alt text. */
        val markdownWithoutMissingAssets: String = markdown,
        /** References include repeats; asset counts and bytes remain deduplicated. */
        val referenceCount: Int = requestedCount,
    )

    /** Converts local relative asset references into portable data URLs for one-file sharing. */
    fun embedAssets(
        markdown: String,
        assets: List<CardAsset>,
        readAsset: (CardAsset) -> ByteArray?,
    ): String = embedAssetsWithStats(markdown, assets, readAsset).markdown

    fun embedAssetsWithStats(
        markdown: String,
        assets: List<CardAsset>,
        readAsset: (CardAsset) -> ByteArray?,
    ): EmbeddedAssetsResult = embedAssetsWithStats(markdown, assets, PortableAssetBudget(), readAsset)

    fun embedAssetsWithStats(
        markdown: String,
        assets: List<CardAsset>,
        budget: PortableAssetBudget,
        readAsset: (CardAsset) -> ByteArray?,
    ): EmbeddedAssetsResult {
        val projection = PortableImageReferences.parse(markdown)
        val references = projection.images.filter { it.localPath != null }
        val paths = references.mapNotNull { it.localPath }.distinct()
        val manifest = assets.groupBy(CardAsset::relativePath)
        val payloads = linkedMapOf<String, String>()
        val missing = linkedSetOf<String>()
        var embeddedBytes = 0L
        val validated = linkedMapOf<String, Pair<CardAsset, ByteArray>>()
        // Preflight manifest sizes before reading any bytes or building Base64.
        val owned = paths.mapNotNull { path ->
            val asset = manifest[path]?.distinct()?.singleOrNull()
            if (!PortableImageReferences.isManagedPath(path) || asset == null ||
                asset.mimeType !in setOf("image/png", "image/jpeg") || asset.byteCount <= 0L ||
                !asset.sha256.matches(Regex("[0-9a-f]{64}"))
            ) { missing += path; null } else path to asset
        }
        var plannedBytes = 0L
        owned.forEach { (_, asset) ->
            if (asset.byteCount > budget.maxImageBytes || asset.byteCount > budget.maxTotalBytes - plannedBytes) {
                throw PortableAssetBudgetExceededException()
            }
            plannedBytes += asset.byteCount
        }
        owned.forEach { (path, asset) ->
            val bytes = runCatching { readAsset(asset) }.getOrNull()
            if (bytes == null) { missing += path; return@forEach }
            if (bytes.size.toLong() > budget.maxImageBytes || bytes.size.toLong() > budget.maxTotalBytes - embeddedBytes) {
                throw PortableAssetBudgetExceededException()
            }
            val dimensions = PortableImageReferences.dimensions(bytes, asset.mimeType)
            if (bytes.size.toLong() != asset.byteCount || KnowledgeCard.sha256(bytes) != asset.sha256 ||
                dimensions == null || dimensions.first.toLong() * dimensions.second > budget.maxImagePixels
            ) { missing += path; return@forEach }
            embeddedBytes += bytes.size
            validated[path] = asset to bytes
        }
        // The whole byte budget is accepted before creating any Base64 payload.
        val pending = validated.entries.iterator()
        while (pending.hasNext()) {
            val entry = pending.next()
            payloads[entry.key] = "data:${entry.value.first.mimeType};base64,${java.util.Base64.getEncoder().encodeToString(entry.value.second)}"
            pending.remove()
        }
        val rendered = projection.render(payloads, removeMissing = false)
        val portable = if (missing.isEmpty()) rendered else projection.render(payloads, removeMissing = true)
        return EmbeddedAssetsResult(
            markdown = rendered,
            requestedCount = paths.size,
            embeddedCount = payloads.size,
            embeddedBytes = embeddedBytes,
            missingCount = missing.size,
            missingPaths = missing.toList(),
            markdownWithoutMissingAssets = portable,
            referenceCount = references.size,
        )
    }

    private fun timestampLink(canonicalUrl: String, startMs: Long): String =
        "[${startMs.asTimestamp()}](${timestampUrl(canonicalUrl, startMs)})"

    private fun timestampUrl(canonicalUrl: String, startMs: Long): String {
        val seconds = (startMs / 1_000).coerceAtLeast(0)
        val separator = if (canonicalUrl.contains('?')) "&" else "?"
        return "$canonicalUrl${separator}t=$seconds"
    }

    private fun yamlString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun formatDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun safeFileName(title: String): String {
        val sanitized = title
            .replace(ILLEGAL_FILE_NAME_CHARS, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_FILE_NAME_LENGTH)
        return sanitized.ifBlank { "untitled" }
    }
}

/** Raw, deduplicated image bytes; checked before allocating Base64 representations. */
data class PortableAssetBudget(
    val maxImageBytes: Long = 16L * 1024L * 1024L,
    val maxTotalBytes: Long = 32L * 1024L * 1024L,
    val maxImagePixels: Long = 24_000_000L,
) {
    init { require(maxImageBytes > 0L && maxTotalBytes > 0L && maxImagePixels > 0L) }
}

class PortableAssetBudgetExceededException : IllegalStateException("图片导出超过安全预算，请减少图片后重试；本地正文仍保留")

private data class PortableImageReference(
    val start: Int,
    val end: Int,
    val localPath: String?,
    val alt: String,
    val destinationStart: Int? = null,
    val destinationEnd: Int? = null,
    val referenceLabel: String? = null,
    val title: String? = null,
)

private data class PortableReferenceDefinition(val start: Int, val end: Int, val label: String, val destination: String, val title: String?)
private data class PortableTextEdit(val start: Int, val end: Int, val text: String)

/** Source ranges come from AST image nodes, never a regex that also matches code. */
private class PortableImageReferences(
    private val source: String,
    val images: List<PortableImageReference>,
    private val definitions: List<PortableReferenceDefinition>,
    private val ordinaryReferenceLabels: Set<String>,
) {
    fun render(payloads: Map<String, String>, removeMissing: Boolean): String {
        val edits = mutableListOf<PortableTextEdit>()
        val convertedLabels = mutableSetOf<String>()
        images.forEach { image ->
            val path = image.localPath ?: return@forEach
            val payload = payloads[path]
            if (payload != null) {
                if (image.destinationStart != null && image.destinationEnd != null) {
                    edits += PortableTextEdit(image.destinationStart, image.destinationEnd, payload)
                } else {
                    val title = image.title?.let { " $it" }.orEmpty()
                    edits += PortableTextEdit(image.start, image.end, "![${image.alt}]($payload$title)")
                    image.referenceLabel?.let(convertedLabels::add)
                }
            } else if (removeMissing) {
                edits += PortableTextEdit(image.start, image.end, image.alt)
                image.referenceLabel?.let(convertedLabels::add)
            }
        }
        definitions.filter { it.label in convertedLabels && it.label !in ordinaryReferenceLabels }.forEach { definition ->
            // Once all its image references became inline data URLs, a private
            // local definition serves no portable purpose. Normal links keep it.
            if (images.none { image -> image.referenceLabel == definition.label && image.localPath == null }) {
                edits += PortableTextEdit(definition.start, definition.end, "")
            }
        }
        val result = StringBuilder(source)
        edits.sortedByDescending { it.start }.forEach { result.replace(it.start, it.end, it.text) }
        return result.toString()
    }

    companion object {
        fun parse(source: String): PortableImageReferences {
            if (source.isEmpty()) return PortableImageReferences(source, emptyList(), emptyList(), emptySet())
            val normalized = StringBuilder(source.length)
            val offsets = ArrayList<Int>(source.length + 1)
            var index = 0
            while (index < source.length) {
                offsets += index
                if (source[index] == '\r' && source.getOrNull(index + 1) == '\n') { normalized.append('\n'); index += 2 }
                else normalized.append(source[index++])
            }
            offsets += source.length
            val text = normalized.toString()
            val frontEnd = if (text.startsWith("---\n")) {
                Regex("(?m)^(---|\\.\\.\\.)[ \\t]*$").findAll(text).drop(1).firstOrNull()?.range?.last?.plus(1) ?: text.length
            } else 0
            val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text as CharSequence)
            fun ASTNode.kind() = type.toString().removePrefix("Markdown:")
            fun ASTNode.slice() = text.substring(startOffset, endOffset)
            fun ASTNode.child(kind: String) = children.firstOrNull { it.kind() == kind }
            val all = root.descendants().filter { it.startOffset >= frontEnd }.toList()
            val definitions = all.filter { it.kind() == "LINK_DEFINITION" }.mapNotNull { node ->
                val label = node.child("LINK_LABEL")?.slice()?.let(::label) ?: return@mapNotNull null
                val destination = node.child("LINK_DESTINATION")?.slice()?.let(::destination) ?: return@mapNotNull null
                PortableReferenceDefinition(offsets[node.startOffset], offsets[node.endOffset], label, destination,
                    node.child("LINK_TITLE")?.slice())
            }
            // CommonMark uses the first definition of a normalized label.
            val byLabel = definitions.groupBy { it.label }.mapValues { it.value.first() }
            val ordinaryLabels = all.filter { it.kind() in setOf("FULL_REFERENCE_LINK", "SHORT_REFERENCE_LINK") &&
                it.parent?.kind() != "IMAGE" }.mapNotNull { it.child("LINK_LABEL")?.slice()?.let(::label) }.toSet()
            val images = all.filter { it.kind() == "IMAGE" }.mapNotNull { image ->
                val link = image.children.firstOrNull { it.kind() in setOf("INLINE_LINK", "FULL_REFERENCE_LINK", "SHORT_REFERENCE_LINK") }
                    ?: return@mapNotNull null
                val rawAlt = (link.child("LINK_TEXT") ?: link.child("LINK_LABEL"))?.slice() ?: "[]"
                val alt = rawAlt.removeSurrounding("[", "]")
                val direct = link.child("LINK_DESTINATION")
                val reference = if (direct == null) link.child("LINK_LABEL")?.slice()?.let(::label) else null
                val definition = reference?.let(byLabel::get)
                val target = direct?.slice()?.let(::destination) ?: definition?.destination ?: return@mapNotNull null
                val local = if (isExternal(target)) null else decodePath(target)
                PortableImageReference(offsets[image.startOffset], offsets[image.endOffset], local, alt,
                    direct?.let { offsets[it.startOffset] }, direct?.let { offsets[it.endOffset] }, reference, definition?.title)
            }
            return PortableImageReferences(source, images, definitions, ordinaryLabels)
        }

        fun isManagedPath(path: String): Boolean = path.startsWith("assets/") &&
            !path.contains('\\') && !path.contains(':') && !path.contains('\u0000') &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }

        private fun destination(value: String): String = value.removeSurrounding("<", ">")
            .replace(Regex("\\\\([!\"#$%&'()*+,./:;<=>?@\\[\\]\\\\^_`{|}~-])"), "$1")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        private fun label(value: String): String = value.removeSurrounding("[", "]").trim().replace(Regex("\\s+"), " ")
            .uppercase(Locale.ROOT).lowercase(Locale.ROOT)
        private fun isExternal(value: String): Boolean = value.startsWith("http://", true) || value.startsWith("https://", true) ||
            value.startsWith("data:", true) || value.startsWith("//")
        private fun decodePath(value: String): String = runCatching {
            java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(value)

        fun dimensions(bytes: ByteArray, mime: String): Pair<Int, Int>? {
            fun byte(offset: Int) = bytes[offset].toInt() and 255
            fun uint32(offset: Int): Long = (byte(offset).toLong() shl 24) or (byte(offset + 1).toLong() shl 16) or
                (byte(offset + 2).toLong() shl 8) or byte(offset + 3).toLong()
            if (mime == "image/png") {
                val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
                if (bytes.size < 45 || !bytes.copyOfRange(0, 8).contentEquals(signature)) return null
                var offset = 8
                var width = 0L; var height = 0L; var hasData = false
                while (offset <= bytes.size - 12) {
                    val length = uint32(offset)
                    if (length > bytes.size - offset - 12L) return null
                    val kind = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
                    val end = offset + length.toInt() + 8
                    val crc = java.util.zip.CRC32().apply { update(bytes, offset + 4, length.toInt() + 4) }.value
                    if (crc != uint32(end)) return null
                    when (kind) {
                        "IHDR" -> {
                            if (offset != 8 || length != 13L) return null
                            width = uint32(offset + 8); height = uint32(offset + 12)
                        }
                        "IDAT" -> hasData = true
                        "IEND" -> return if (length == 0L && hasData && width in 1..Int.MAX_VALUE.toLong() && height in 1..Int.MAX_VALUE.toLong())
                            width.toInt() to height.toInt() else null
                    }
                    offset = end + 4
                }
                return null
            }
            if (mime != "image/jpeg" || bytes.size < 4 || byte(0) != 255 || byte(1) != 216 ||
                byte(bytes.size - 2) != 255 || byte(bytes.size - 1) != 217) return null
            var offset = 2
            var dimensions: Pair<Int, Int>? = null
            var hasScan = false
            while (offset < bytes.size - 1) {
                if (byte(offset++) != 255) return null
                while (offset < bytes.size && byte(offset) == 255) offset++
                if (offset >= bytes.size) return null
                val marker = byte(offset++)
                if (marker == 217) return dimensions?.takeIf { hasScan && offset == bytes.size }
                if (marker in 208..215 || marker == 1) continue
                if (marker == 0 || marker == 216) return null
                if (offset + 1 >= bytes.size) return null
                val length = (byte(offset) shl 8) or byte(offset + 1)
                if (length < 2 || offset + length > bytes.size) return null
                if (marker in 192..207 && marker !in setOf(196, 200, 204)) {
                    if (length < 8) return null
                    val height = (byte(offset + 3) shl 8) or byte(offset + 4)
                    val width = (byte(offset + 5) shl 8) or byte(offset + 6)
                    if (width <= 0 || height <= 0) return null
                    dimensions = width to height
                }
                offset += length
                if (marker == 218) {
                    hasScan = true
                    // Entropy-coded bytes use FF00 stuffing; restart markers
                    // stay within the scan, other markers resume segment parsing.
                    while (offset < bytes.size - 1) {
                        if (byte(offset) != 255) { offset++; continue }
                        val next = byte(offset + 1)
                        if (next == 0 || next in 208..215) { offset += 2; continue }
                        break
                    }
                }
            }
            return null
        }
    }
}

private fun ASTNode.descendants(): Sequence<ASTNode> = sequence {
    yield(this@descendants)
    children.forEach { yieldAll(it.descendants()) }
}
