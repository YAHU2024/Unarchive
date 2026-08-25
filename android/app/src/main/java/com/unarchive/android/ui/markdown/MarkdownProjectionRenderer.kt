package com.unarchive.android.ui.markdown

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.unarchive.android.card.CardAsset
import com.unarchive.android.card.CardAssetKind
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardRepository
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.io.File
import java.net.URI

/**
 * Domain-owned boundary around the third-party Markdown reader.
 *
 * NoteDocument/KnowledgeCard code must depend on this composable, never on the
 * renderer API directly. The renderer is a read-only Markdown projection.
 */
@Composable
internal fun MarkdownProjectionRenderer(
    markdown: String,
    modifier: Modifier = Modifier,
    assetResolver: MarkdownAssetResolver = NoOpMarkdownAssetResolver,
    onAllowedLink: (String) -> Unit = {},
    onBlockedLink: (String) -> Unit = {},
) {
    val imageTransformer = remember(assetResolver) {
        ResolverImageTransformer(assetResolver)
    }
    val projectedMarkdown = remember(markdown, assetResolver) {
        projectUnavailableImages(markdown, assetResolver)
    }
    val uriHandler = remember(onAllowedLink, onBlockedLink) {
        SafeMarkdownUriHandler(onAllowedLink, onBlockedLink)
    }

    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Markdown(
            content = projectedMarkdown,
            modifier = modifier.testTag("markdown-projection"),
            imageTransformer = imageTransformer,
        )
    }
}

/** Splits large documents before renderer parsing while preserving fenced blocks. */
internal fun markdownPreviewChunks(
    markdown: String,
    maxChunkChars: Int = MARKDOWN_PREVIEW_CHUNK_CHARS,
): List<String> {
    require(maxChunkChars > 0) { "maxChunkChars must be positive" }
    if (markdown.length <= maxChunkChars) return listOf(markdown)

    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    var inFence = false
    fun flush() {
        if (current.isNotEmpty()) {
            chunks += current.toString()
            current.setLength(0)
        }
    }
    val lines = markdown.split("\n", limit = Int.MAX_VALUE)
    lines.forEachIndexed { index, line ->
        val lineWithBreak = if (index == lines.lastIndex) line else "$line\n"
        val fence = line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")
        val blank = line.isBlank()
        if (!inFence && current.length >= maxChunkChars && blank) {
            flush()
        }
        if (current.length > 0 && current.length + lineWithBreak.length > maxChunkChars && !inFence && blank) {
            flush()
        }
        current.append(lineWithBreak)
        if (fence) inFence = !inFence
        if (!inFence && current.length >= maxChunkChars && blank) {
            flush()
        }
    }
    flush()
    return chunks.ifEmpty { listOf(markdown) }
}

/**
 * The renderer's annotator only sees text nodes, so image failures must be
 * projected before Compose rendering. Parsing with the same Markdown AST
 * library keeps replacements limited to actual image nodes (and out of code
 * fences or ordinary text that merely resembles image syntax).
 */
internal fun projectUnavailableImages(
    markdown: String,
    assetResolver: MarkdownAssetResolver,
): String {
    val root = runCatching {
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
    }.getOrNull() ?: return markdown

    val replacements = buildList {
        root.collectDescendants(MarkdownElementTypes.IMAGE).forEach { image ->
            val link = image.findDescendant(MarkdownElementTypes.LINK_DESTINATION)
                ?.getUnescapedTextInNode(markdown)
            if (link == null || !assetResolver.isAvailable(link)) {
                val alt = image.findDescendant(MarkdownElementTypes.LINK_TEXT)
                    ?.getUnescapedTextInNode(markdown)
                    ?.trim()
                    ?.ifBlank { null }
                    ?: "图片"
                add(
                    ImageReplacement(
                        start = image.startOffset,
                        end = image.endOffset,
                        text = "图片不可用：${sanitizeFallbackAlt(alt)}",
                    ),
                )
            }
        }
    }

    var projected = markdown
    replacements.sortedByDescending { it.start }.forEach { replacement ->
        if (replacement.start in 0..projected.length &&
            replacement.end in replacement.start..projected.length
        ) {
            projected = buildString(projected.length + replacement.text.length) {
                append(projected, 0, replacement.start)
                append(replacement.text)
                append(projected, replacement.end, projected.length)
            }
        }
    }
    return projected
}

/** Returns image nodes from Markdown without matching image-like text in code. */
internal data class MarkdownImageReference(
    val link: String,
    val alt: String,
)

internal fun markdownImageReferences(markdown: String): List<MarkdownImageReference> {
    val root = runCatching {
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
    }.getOrNull() ?: return emptyList()

    return root.collectDescendants(MarkdownElementTypes.IMAGE).mapNotNull { image ->
        val link = image.findDescendant(MarkdownElementTypes.LINK_DESTINATION)
            ?.getUnescapedTextInNode(markdown)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return@mapNotNull null
        val alt = image.findDescendant(MarkdownElementTypes.LINK_TEXT)
            ?.getUnescapedTextInNode(markdown)
            ?.trim()
            ?.removeSurrounding("[", "]")
            .orEmpty()
        MarkdownImageReference(link, alt)
    }.toList()
}

private data class ImageReplacement(
    val start: Int,
    val end: Int,
    val text: String,
)

private fun sanitizeFallbackAlt(value: String): String = value
    .replace(Regex("[\\u0000-\\u001f\\u007f]+"), " ")
    .replace(Regex("[\\[\\]`()_*#!>\\\\]"), "")
    .replace(Regex("\\s+"), " ")
    .trim()
    .ifBlank { "图片" }

private fun ASTNode.collectDescendants(type: org.intellij.markdown.IElementType): Sequence<ASTNode> =
    sequence {
        if (this@collectDescendants.type == type) {
            yield(this@collectDescendants)
        }
        children.forEach { child ->
            yieldAll(child.collectDescendants(type))
        }
    }

internal interface MarkdownAssetResolver {
    fun isAvailable(link: String): Boolean

    fun resolve(link: String): MarkdownImageAsset?
}

internal data class MarkdownImageAsset(
    val imageData: ImageData,
)

internal object NoOpMarkdownAssetResolver : MarkdownAssetResolver {
    override fun isAvailable(link: String): Boolean = false

    override fun resolve(link: String): MarkdownImageAsset? = null
}

/** Resolves only assets explicitly owned by the current card version. */
internal class CardAssetMarkdownResolver(
    assets: List<CardAsset>,
    private val fileForAsset: (CardAsset) -> File?,
) : MarkdownAssetResolver {
    private val assetsByPath = assets
        .asSequence()
        .filter { it.kind != CardAssetKind.COVER }
        .associateBy(CardAsset::relativePath)

    override fun isAvailable(link: String): Boolean {
        val resolved = resolveFile(link) ?: return false
        return safeImageBounds(resolved.asset, resolved.file) != null
    }

    override fun resolve(link: String): MarkdownImageAsset? {
        val resolved = resolveFile(link) ?: return null
        val bounds = safeImageBounds(resolved.asset, resolved.file) ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.width, bounds.height)
        }
        val bitmap = BitmapFactory.decodeFile(resolved.file.path, options) ?: return null
        return MarkdownImageAsset(
            ImageData(
                painter = BitmapPainter(bitmap.asImageBitmap()),
                contentDescription = resolved.asset.assetId,
            ),
        )
    }

    private fun resolveFile(link: String): ResolvedCardAsset? {
        val asset = assetsByPath[link] ?: return null
        val file = fileForAsset(asset)?.takeIf(File::isFile) ?: return null
        return ResolvedCardAsset(asset, file)
    }

    private data class ResolvedCardAsset(val asset: CardAsset, val file: File)
}

/**
 * Creates the preview resolver for one persisted card version. Keeping the
 * repository lookup here makes it harder for callers to accidentally resolve
 * an asset from another card or from an untrusted filesystem path.
 */
internal fun cardAssetMarkdownResolver(
    card: KnowledgeCard,
    repository: KnowledgeCardRepository,
): CardAssetMarkdownResolver = CardAssetMarkdownResolver(card.assets) { asset ->
    repository.assetFile(card, asset)
}

/**
 * Resolves only relative assets below the supplied card-assets directory.
 * It never fetches network images and rejects path traversal.
 */
internal class LocalFileMarkdownAssetResolver(
    private val root: File,
) : MarkdownAssetResolver {
    private val canonicalRoot = root.canonicalFile

    override fun isAvailable(link: String): Boolean {
        val file = resolveFile(link) ?: return false
        return safeImageBounds(null, file) != null
    }

    override fun resolve(link: String): MarkdownImageAsset? {
        val file = resolveFile(link) ?: return null
        val bounds = safeImageBounds(null, file) ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.width, bounds.height)
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
        return MarkdownImageAsset(
            ImageData(
                painter = BitmapPainter(bitmap.asImageBitmap()),
                contentDescription = file.name,
            ),
        )
    }

    private fun resolveFile(link: String): File? {
        val parsed = runCatching { URI(link) }.getOrNull()
        val path = when {
            parsed?.scheme == null -> link
            parsed.scheme.equals("file", ignoreCase = true) -> parsed.path ?: return null
            else -> return null
        }
        val candidate = if (File(path).isAbsolute) File(path) else File(canonicalRoot, path)
        val canonical = candidate.canonicalFile
        if (canonical != canonicalRoot &&
            !canonical.path.startsWith(canonicalRoot.path + File.separator)
        ) {
            return null
        }
        return canonical
    }
}

private data class SafeImageBounds(val width: Int, val height: Int)

private fun safeImageBounds(asset: CardAsset?, file: File): SafeImageBounds? {
    if (!file.isFile || file.length() <= 0L || file.length() > MAX_MARKDOWN_IMAGE_BYTES) {
        return null
    }
    if (asset != null && asset.mimeType !in MARKDOWN_IMAGE_MIME_TYPES) {
        return null
    }
    if (asset != null &&
        (asset.byteCount <= 0L ||
            asset.byteCount > MAX_MARKDOWN_IMAGE_BYTES ||
            file.length() > asset.byteCount)
    ) {
        return null
    }
    if (asset == null && file.extension.lowercase() !in MARKDOWN_IMAGE_EXTENSIONS) {
        return null
    }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    val pixels = options.outWidth.toLong() * options.outHeight.toLong()
    return if (pixels <= MAX_MARKDOWN_IMAGE_PIXELS) {
        SafeImageBounds(options.outWidth, options.outHeight)
    } else {
        null
    }
}

private fun sampleSizeFor(width: Int, height: Int): Int {
    var sample = 1
    while ((width.toLong() / sample) * (height.toLong() / sample) > MAX_MARKDOWN_DECODE_PIXELS) {
        sample *= 2
    }
    return sample
}

internal fun isAllowedMarkdownUri(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        host in setOf("bilibili.com", "www.bilibili.com", "b23.tv")
}

internal class SafeMarkdownUriHandler(
    private val onAllowed: (String) -> Unit,
    private val onBlocked: (String) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        if (isAllowedMarkdownUri(uri)) {
            onAllowed(uri)
        } else {
            onBlocked(uri)
        }
    }
}

private class ResolverImageTransformer(
    private val resolver: MarkdownAssetResolver,
) : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? = resolver.resolve(link)?.imageData

    @Composable
    override fun intrinsicSize(
        painter: androidx.compose.ui.graphics.painter.Painter,
    ): androidx.compose.ui.geometry.Size = painter.intrinsicSize
}

private const val MAX_MARKDOWN_IMAGE_BYTES = 16L * 1024L * 1024L
private const val MARKDOWN_PREVIEW_CHUNK_CHARS = 500
private const val MAX_MARKDOWN_IMAGE_PIXELS = 24_000_000L
private const val MAX_MARKDOWN_DECODE_PIXELS = 4_000_000L
private val MARKDOWN_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png")
private val MARKDOWN_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

private fun ASTNode.findDescendant(
    type: org.intellij.markdown.IElementType,
): ASTNode? {
    children.firstOrNull { it.type == type }?.let { return it }
    children.forEach { child ->
        child.findDescendant(type)?.let { return it }
    }
    return null
}
