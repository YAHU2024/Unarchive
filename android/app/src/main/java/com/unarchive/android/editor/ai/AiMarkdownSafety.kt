package com.unarchive.android.editor.ai

import com.unarchive.android.card.CardAsset
import com.unarchive.android.editor.MarkdownAiSafetyValidator
import java.net.URI
import java.util.Locale
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** The new editor's link policy; the legacy source allowlist remains unchanged. */
object AiMarkdownSafety {
    fun validate(
        markdown: String,
        assets: List<CardAsset>,
        isManagedAssetAvailable: (CardAsset) -> Boolean,
    ): MarkdownAiSafetyValidator.Result {
        val source = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source as CharSequence)
        val errors = linkedSetOf<String>()
        val definitions = linkedMapOf<String, String>()
        fun ASTNode.text() = source.substring(startOffset, endOffset)
        fun label(value: String) = value.removeSurrounding("[", "]").trim()
            .replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        fun collect(node: ASTNode) {
            if (node.kind() in CODE) return
            if (node.kind() == "LINK_DEFINITION") {
                val key = node.children.firstOrNull { it.kind() == "LINK_LABEL" }?.text()
                val destination = node.children.firstOrNull { it.kind() == "LINK_DESTINATION" }?.text()
                if (key != null && destination != null) definitions.putIfAbsent(label(key), destination)
                return
            }
            node.children.forEach(::collect)
        }
        collect(root)
        fun destination(node: ASTNode): String? = when (node.kind()) {
            "INLINE_LINK" -> node.children.firstOrNull { it.kind() == "LINK_DESTINATION" }?.text()
            "FULL_REFERENCE_LINK", "SHORT_REFERENCE_LINK" -> node.children
                .firstOrNull { it.kind() == "LINK_LABEL" }?.text()?.let { definitions[label(it)] }
            else -> null
        }
        fun validateDestination(raw: String?, image: Boolean) {
            if (raw == null) {
                if (image) errors += "图片引用无法解析"
                return
            }
            val value = raw.trim().removeSurrounding("<", ">")
                .replace(Regex("\\\\([\\\\`*_{}\\[\\]()#+.!<>~-])"), "$1")
            if (image) {
                val path = managedPath(value)
                val matching = path?.let { wanted -> assets.singleOrNull { it.relativePath == wanted } }
                if (matching == null || !runCatching { isManagedAssetAvailable(matching) }.getOrDefault(false)) {
                    errors += "候选包含未受管或缺失的图片"
                }
            } else if (!isAllowedLink(value)) errors += "候选包含不受支持的链接协议"
        }
        fun inspect(node: ASTNode) {
            when (node.kind()) {
                in CODE, "LINK_DEFINITION", "LINK_DESTINATION" -> return
                "HTML_BLOCK", "HTML_TAG", "HTML_BLOCK_CONTENT" -> {
                    errors += "候选包含原始 HTML"
                    return
                }
                "IMAGE" -> {
                    val link = node.children.firstOrNull { it.kind() in LINKS }
                    validateDestination(link?.let(::destination), image = true)
                    // Only inspect the label; a link nested below IMAGE must not become a web link.
                    link?.children?.filter { it.kind() == "LINK_TEXT" }?.forEach(::inspect)
                    return
                }
                in LINKS -> {
                    validateDestination(destination(node), image = false)
                    node.children.filter { it.kind() == "LINK_TEXT" }.forEach(::inspect)
                    return
                }
                "AUTOLINK", "GFM_AUTOLINK", "EMAIL_AUTOLINK" -> {
                    validateDestination(node.text().removeSurrounding("<", ">"), image = false)
                    return
                }
            }
            node.children.forEach(::inspect)
        }
        inspect(root)
        return MarkdownAiSafetyValidator.Result(errors.take(6))
    }

    fun isAllowedLink(value: String): Boolean {
        if (value.startsWith('#')) return value.none { it.isISOControl() }
        if (value.any { it.isISOControl() } || '\\' in value) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    private fun managedPath(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.isAbsolute || uri.rawAuthority != null || uri.query != null || uri.fragment != null) return null
        val path = uri.path ?: return null
        return path.takeIf {
            it.startsWith("assets/") && '\\' !in it && ':' !in it && !it.any(Char::isISOControl) &&
                it.split('/').none { segment -> segment.isBlank() || segment == "." || segment == ".." }
        }
    }

    private fun ASTNode.kind() = type.toString().removePrefix("Markdown:")
    private val CODE = setOf("CODE_FENCE", "CODE_BLOCK", "CODE_SPAN")
    private val LINKS = setOf("INLINE_LINK", "FULL_REFERENCE_LINK", "SHORT_REFERENCE_LINK")
}
