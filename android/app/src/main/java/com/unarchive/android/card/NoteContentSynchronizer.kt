package com.unarchive.android.card

/** The explicit choice required before a legacy Markdown/structured conflict is written as v3. */
enum class NoteContentMigrationChoice { KEEP_LEGACY_MARKDOWN, USE_STRUCTURED_PROJECTION }

sealed interface NoteContentMigrationOutcome {
    data class Ready(val content: NoteContent) : NoteContentMigrationOutcome
    data class Conflict(val decision: NoteContentMigrationDecision.Conflict) : NoteContentMigrationOutcome
    data class Failed(val message: String, val legacyMarkdown: String? = null) : NoteContentMigrationOutcome
}

/**
 * Bridges an existing v2 note to the isolated v3 content store. It never
 * mutates the v2 input and leaves conflicting source forms for the UI to show.
 */
class NoteContentSynchronizer(
    private val contentRepository: FileNoteContentRepository,
    private val documentRepository: NoteDocumentRepository,
) {
    fun prepare(cardId: KnowledgeCardId, cardVersion: String): NoteContentMigrationOutcome {
        contentRepository.find(cardId, cardVersion)?.let { return NoteContentMigrationOutcome.Ready(it) }
        val snapshot = documentRepository.migrationSnapshot(cardId, cardVersion)
            ?: return NoteContentMigrationOutcome.Failed("找不到可迁移的旧笔记版本。")
        return persistOrConflict(
            NoteContentMigration.fromNoteDocument(
                document = snapshot.document,
                legacyMarkdown = snapshot.markdown,
                legacyStructuredJson = snapshot.structuredJson,
            ),
        ).withLegacyMarkdown(snapshot.markdown)
    }

    fun resolve(
        conflict: NoteContentMigrationDecision.Conflict,
        choice: NoteContentMigrationChoice,
    ): NoteContentMigrationOutcome {
        val markdown = when (choice) {
            NoteContentMigrationChoice.KEEP_LEGACY_MARKDOWN -> conflict.backup.markdown
            NoteContentMigrationChoice.USE_STRUCTURED_PROJECTION -> conflict.generatedMarkdown
        }
        return persist(NoteContentMigration.resolveConflict(conflict, markdown))
            .withLegacyMarkdown(conflict.backup.markdown)
    }

    private fun persistOrConflict(decision: NoteContentMigrationDecision): NoteContentMigrationOutcome = when (decision) {
        is NoteContentMigrationDecision.Ready -> persist(decision)
        is NoteContentMigrationDecision.Conflict -> NoteContentMigrationOutcome.Conflict(decision)
    }

    private fun persist(decision: NoteContentMigrationDecision.Ready): NoteContentMigrationOutcome {
        val result = contentRepository.saveMigrated(decision.content, decision.backup)
        return if (result.isComplete) {
            NoteContentMigrationOutcome.Ready(result.content)
        } else {
            NoteContentMigrationOutcome.Failed(result.error ?: "迁移提交未完成。")
        }
    }
}

private fun NoteContentMigrationOutcome.withLegacyMarkdown(markdown: String): NoteContentMigrationOutcome =
    if (this is NoteContentMigrationOutcome.Failed) copy(legacyMarkdown = markdown) else this
