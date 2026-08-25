package com.unarchive.android.card

/** The legacy inputs are kept intact when their structured and Markdown views disagree. */
data class LegacyNoteBackup(
    val structuredJson: String?,
    val markdown: String,
)

sealed interface NoteContentMigrationDecision {
    data class Ready(
        val content: NoteContent,
        val backup: LegacyNoteBackup,
    ) : NoteContentMigrationDecision

    data class Conflict(
        val structuredMetadata: NoteDocument,
        val generatedMarkdown: String,
        val backup: LegacyNoteBackup,
    ) : NoteContentMigrationDecision
}

/** Pure, repeatable v1/v2 -> v3 migration. No legacy input is silently discarded. */
object NoteContentMigration {
    fun fromKnowledgeCard(card: KnowledgeCard): NoteContentMigrationDecision =
        fromNoteDocument(
            document = card.toNoteDocument(),
            legacyMarkdown = card.markdown,
            legacyStructuredJson = null,
        )

    fun fromNoteDocument(
        document: NoteDocument,
        legacyMarkdown: String,
        legacyStructuredJson: String? = null,
        projector: (NoteDocument) -> String = NoteMarkdownProjection::render,
    ): NoteContentMigrationDecision {
        val generatedMarkdown = projector(document)
        val backup = LegacyNoteBackup(legacyStructuredJson, legacyMarkdown)
        if (legacyMarkdown != generatedMarkdown) {
            return NoteContentMigrationDecision.Conflict(document, generatedMarkdown, backup)
        }
        return NoteContentMigrationDecision.Ready(
            content = NoteContent(
                cardId = document.cardId,
                cardVersion = document.generation.cardVersion,
                markdown = legacyMarkdown,
                markdownRevision = 0L,
                structuredMetadata = document,
                structuredProjection = NoteStructuredProjection(
                    recognizedBlockIds = document.blocks.map { it.id },
                ),
                projectionStatus = NoteProjectionStatus.CURRENT,
                lastKnownGoodMarkdown = legacyMarkdown,
                createdAtEpochMs = document.createdAtEpochMs,
                updatedAtEpochMs = document.updatedAtEpochMs,
            ),
            backup = backup,
        )
    }

    /** Applies an explicit user choice after a legacy conflict was shown. */
    fun resolveConflict(
        conflict: NoteContentMigrationDecision.Conflict,
        markdown: String,
    ): NoteContentMigrationDecision.Ready = NoteContentMigrationDecision.Ready(
        content = NoteContent(
            cardId = conflict.structuredMetadata.cardId,
            cardVersion = conflict.structuredMetadata.generation.cardVersion,
            markdown = markdown,
            markdownRevision = 0L,
            structuredMetadata = conflict.structuredMetadata,
            structuredProjection = NoteStructuredProjection(
                recognizedBlockIds = conflict.structuredMetadata.blocks.map { it.id },
            ),
            projectionStatus = if (markdown == conflict.generatedMarkdown) {
                NoteProjectionStatus.CURRENT
            } else {
                NoteProjectionStatus.PARTIAL
            },
            lastKnownGoodMarkdown = markdown,
            createdAtEpochMs = conflict.structuredMetadata.createdAtEpochMs,
            updatedAtEpochMs = conflict.structuredMetadata.updatedAtEpochMs,
        ),
        backup = conflict.backup,
    )
}
