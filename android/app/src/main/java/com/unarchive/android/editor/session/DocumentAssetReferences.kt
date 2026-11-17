package com.unarchive.android.editor.session

/** Domain metadata only. Main retains CardAsset, byte readers and physical ownership. */
data class DocumentAssetReference(val id: String, val path: String, val retainWithoutReference: Boolean = false) {
    init { require(id.isNotBlank() && path.isNotBlank()) }
}

data class AssetReferenceDelta(
    val add: List<DocumentAssetReference> = emptyList(),
    val removeIds: Set<String> = emptySet(),
) {
    init { require(add.map { it.id }.distinct().size == add.size); require(add.none { it.id in removeIds }) }
}
