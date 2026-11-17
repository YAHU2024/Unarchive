package com.unarchive.android.editor

import com.unarchive.android.card.*

data class FormalOperationRequest(val cardId: KnowledgeCardId, val cardVersion: String, val revision: Long,
    val sourceFingerprint: String, val assetsFingerprint: String)

object NoteExternalActionCoordinator {
    fun request(content: NoteContent) = FormalOperationRequest(content.cardId, content.cardVersion,
        content.markdownRevision, NoteContent.markdownFingerprint(content.markdown), assetsFingerprint(content.structuredMetadata.assets))

    fun resolve(request: FormalOperationRequest, repository: FileNoteContentRepository): NoteContent? =
        repository.findRevision(request.cardId, request.cardVersion, request.revision)?.takeIf {
            NoteContent.markdownFingerprint(it.markdown) == request.sourceFingerprint &&
                assetsFingerprint(it.structuredMetadata.assets) == request.assetsFingerprint
        }

    private fun assetsFingerprint(assets: List<CardAsset>): String = NoteContent.markdownFingerprint(buildString {
        fun field(value: String) { append(value.length); append(':'); append(value) }
        assets.sortedBy { it.assetId }.forEach {
            field(it.assetId); field(it.kind.name); field(it.mimeType); field(it.relativePath)
            field(it.sha256); field(it.byteCount.toString()); field(it.chapterIndex?.toString() ?: "null")
            field(it.timestampMs?.toString() ?: "null")
        }
    })
}
