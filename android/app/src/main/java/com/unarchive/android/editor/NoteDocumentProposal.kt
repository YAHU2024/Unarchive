package com.unarchive.android.editor

import com.unarchive.android.card.NoteBlock
import com.unarchive.android.card.NoteBlockOrigin
import com.unarchive.android.card.NoteDocument
import java.security.MessageDigest

enum class NoteProposalChangeKind { ADDED, REMOVED, UPDATED }

data class NoteProposalChange(
    val blockId: String,
    val kind: NoteProposalChangeKind,
    val currentPreview: String? = null,
    val proposedPreview: String? = null,
)

enum class NoteProposalStatus { PENDING, APPLIED, REJECTED }

/** An explicit, user-reviewable AI replacement candidate. */
data class NoteDocumentProposal(
    val proposalId: String,
    val baseCardId: String,
    val baseCardVersion: String,
    val baseContentRevision: Long,
    val proposed: NoteDocument,
    val changes: List<NoteProposalChange>,
    val createdAtEpochMs: Long,
    /** Fingerprint of the exact structured content the candidate was based on. */
    val baseContentFingerprint: String,
    val status: NoteProposalStatus = NoteProposalStatus.PENDING,
)

internal object NoteDocumentContentFingerprint {
    fun of(document: NoteDocument): String {
        val stable = document.copy(
            editing = document.editing.copy(
                dirty = false,
                lastSavedAtEpochMs = null,
                lastSaveError = null,
            ),
            publishing = document.publishing.copy(
                markdownProjectionHash = "",
                updatedAtEpochMs = 0L,
            ),
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
        ).toString()
        return MessageDigest.getInstance("SHA-256")
            .digest(stable.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

object NoteDocumentProposalBuilder {
    fun create(
        current: NoteDocument,
        proposed: NoteDocument,
        proposalId: String,
        createdAtEpochMs: Long,
    ): NoteDocumentProposal {
        require(current.cardId == proposed.cardId) { "Proposal card identity mismatch" }
        val currentById = current.blocks.associateBy(NoteBlock::id)
        val proposedById = proposed.blocks.associateBy(NoteBlock::id)
        val changes = (currentById.keys + proposedById.keys).distinct().mapNotNull { id ->
            val before = currentById[id]
            val after = proposedById[id]
            when {
                before == null -> NoteProposalChange(id, NoteProposalChangeKind.ADDED, proposedPreview = preview(after))
                after == null -> NoteProposalChange(id, NoteProposalChangeKind.REMOVED, currentPreview = preview(before))
                blockContent(before) != blockContent(after) -> NoteProposalChange(
                    id,
                    NoteProposalChangeKind.UPDATED,
                    currentPreview = preview(before),
                    proposedPreview = preview(after),
                )
                else -> null
            }
        }
        return NoteDocumentProposal(
            proposalId = proposalId,
            baseCardId = current.cardId.value,
            baseCardVersion = current.generation.cardVersion,
            baseContentRevision = current.editing.contentRevision,
            proposed = proposed,
            changes = changes,
            createdAtEpochMs = createdAtEpochMs,
            baseContentFingerprint = NoteDocumentContentFingerprint.of(current),
        )
    }

    private fun blockContent(block: NoteBlock): String = listOf(
        block.type.name,
        block.origin.name,
        block.text,
        block.title,
        block.startMs?.toString().orEmpty(),
        block.endMs?.toString().orEmpty(),
        block.points.joinToString { "${it.timestampMs}:${it.text}" },
        block.sourceRef?.toString().orEmpty(),
        block.assetRefs.joinToString(),
        block.tags.joinToString(),
    ).joinToString("|")

    private fun preview(block: NoteBlock?): String? = block?.let {
        when (it.type) {
            com.unarchive.android.card.NoteBlockType.CHAPTER -> it.title.ifBlank { it.text }
            com.unarchive.android.card.NoteBlockType.TAG_LIST -> it.tags.joinToString(", ")
            else -> it.text
        }.take(120)
    }
}

object NoteDocumentProposalApplier {
    fun apply(current: NoteDocument, proposal: NoteDocumentProposal): NoteDocument {
        require(proposal.status == NoteProposalStatus.PENDING) { "Proposal is no longer pending" }
        require(current.cardId.value == proposal.baseCardId) { "Proposal card identity mismatch" }
        require(current.generation.cardVersion == proposal.baseCardVersion) {
            "Proposal is based on an older generated card"
        }
        require(current.editing.contentRevision == proposal.baseContentRevision) {
            "Proposal is based on an older user revision"
        }
        require(NoteDocumentContentFingerprint.of(current) == proposal.baseContentFingerprint) {
            "Proposal is based on content that has changed"
        }
        val userBlocks = current.blocks.filter { it.origin == NoteBlockOrigin.USER }
        val userBlockIds = userBlocks.mapTo(mutableSetOf()) { it.id }
        val proposedBlocks = proposal.proposed.blocks.filter {
            it.origin != NoteBlockOrigin.USER &&
                it.type != com.unarchive.android.card.NoteBlockType.TAG_LIST &&
                it.id !in userBlockIds
        }
        val mergedBlocks = proposedBlocks + userBlocks
        val tags = if (current.tags.isNotEmpty()) current.tags else proposal.proposed.tags
        val tagBlock = mergedBlocks.firstOrNull { it.type == com.unarchive.android.card.NoteBlockType.TAG_LIST }
        val normalizedBlocks = if (tags.isEmpty()) {
            mergedBlocks.filterNot { it.type == com.unarchive.android.card.NoteBlockType.TAG_LIST }
        } else if (tagBlock == null) {
            mergedBlocks + NoteBlock(
                id = "user-tags-${current.cardId.value.hashCode().toUInt().toString(16)}",
                type = com.unarchive.android.card.NoteBlockType.TAG_LIST,
                origin = NoteBlockOrigin.USER,
                tags = tags,
            )
        } else {
            mergedBlocks.map { if (it.id == tagBlock.id) it.copy(tags = tags) else it }
        }
        return proposal.proposed.copy(
            blocks = normalizedBlocks,
            tags = tags,
            editing = current.editing.copy(dirty = true, lastSaveError = null),
            publishing = current.publishing,
            createdAtEpochMs = current.createdAtEpochMs,
            updatedAtEpochMs = current.updatedAtEpochMs,
        )
    }
}
