package com.unarchive.android.card

/**
 * The portable, single-file representation used by system sharing.
 *
 * The local card keeps relative asset paths so it remains readable inside the
 * app. A share artifact embeds every asset that can be read and deliberately
 * removes missing private asset markers from the portable artifact; callers
 * can surface [assets.missingCount] without losing the rest of the note.
 */
data class KnowledgeCardExportArtifact(
    val fileName: String,
    val markdown: String,
    val markdownBytes: Long,
    val assets: MarkdownCardRenderer.EmbeddedAssetsResult,
) {
    /** False when one or more local image paths could not be embedded. */
    val isFullyPortable: Boolean get() = assets.missingCount == 0
}

/** Pure export preparation for a [KnowledgeCard]. */
object KnowledgeCardExport {
    /**
     * Builds a portable Markdown artifact without touching the filesystem or
     * launching an Android Intent. [readAsset] is the only platform boundary.
     */
    fun portable(
        card: KnowledgeCard,
        readAsset: (CardAsset) -> ByteArray? = { null },
    ): KnowledgeCardExportArtifact {
        val assets = MarkdownCardRenderer.embedAssetsWithStats(
            markdown = card.markdown,
            assets = card.assets,
            readAsset = readAsset,
        )
        val markdown = assets.missingPaths.fold(assets.markdown) { current, path ->
            current.replace("![]($path)", "")
        }
        return KnowledgeCardExportArtifact(
            fileName = MarkdownCardRenderer.fileName(card.title),
            markdown = markdown,
            markdownBytes = markdown.toByteArray(Charsets.UTF_8).size.toLong(),
            assets = assets,
        )
    }
}
