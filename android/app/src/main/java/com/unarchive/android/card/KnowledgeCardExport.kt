package com.unarchive.android.card

import java.io.File

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
    /** [file] must be resolved by the current card-version repository first. */
    fun readManagedAsset(asset: CardAsset, file: File?, budget: PortableAssetBudget = PortableAssetBudget()): ByteArray? {
        if (file == null || !file.isFile || asset.byteCount <= 0L || asset.byteCount > Int.MAX_VALUE || asset.byteCount > budget.maxImageBytes ||
            file.length() != asset.byteCount || file.length() > budget.maxImageBytes
        ) return null
        return runCatching {
            file.inputStream().use { input ->
                val bytes = ByteArray(asset.byteCount.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count <= 0) return@use null
                    offset += count
                }
                // A file which grew while being read is not this immutable asset.
                if (input.read() != -1) null else bytes
            }
        }.getOrNull()
    }

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
        val markdown = assets.markdownWithoutMissingAssets
        return KnowledgeCardExportArtifact(
            fileName = MarkdownCardRenderer.fileName(card.title),
            markdown = markdown,
            markdownBytes = markdown.toByteArray(Charsets.UTF_8).size.toLong(),
            assets = assets,
        )
    }
}
