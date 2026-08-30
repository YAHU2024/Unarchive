package com.unarchive.android.card

/**
 * The exact Markdown source that an external operation is allowed to consume.
 *
 * A migrated/edited v3 note is authoritative.  Cards which have not entered
 * the v3 editor yet continue to use their already-formal v2 Markdown and
 * structured revision so the migration remains reversible and reachable.
 */
data class FormalMarkdownSnapshot(
    val card: KnowledgeCard,
    val markdownRevision: Long,
    val source: FormalMarkdownSource,
)

enum class FormalMarkdownSource { MARKDOWN_V3, LEGACY_V2 }

object FormalMarkdownSnapshotResolver {
    fun resolve(
        card: KnowledgeCard,
        content: NoteContent?,
        legacyMarkdownRevision: Long = 0L,
    ): FormalMarkdownSnapshot {
        val v3 = content?.takeIf {
            it.cardId == card.cardId &&
                it.cardVersion == card.cardVersion &&
                it.markdown.isNotBlank()
        }
        if (v3 != null) {
            return FormalMarkdownSnapshot(
                card = card.withFormalContent(v3),
                markdownRevision = v3.markdownRevision,
                source = FormalMarkdownSource.MARKDOWN_V3,
            )
        }
        return FormalMarkdownSnapshot(
            card = card,
            markdownRevision = legacyMarkdownRevision.coerceAtLeast(0L),
            source = FormalMarkdownSource.LEGACY_V2,
        )
    }

    private fun KnowledgeCard.withFormalContent(content: NoteContent): KnowledgeCard {
        val metadata = content.structuredMetadata
        return copy(
            canonicalUrl = metadata.source.canonicalUrl,
            title = metadata.title,
            ownerName = metadata.source.ownerName,
            videoDurationSeconds = metadata.source.durationMs / 1_000L,
            timingAccuracy = metadata.source.timingAccuracy,
            transcript = metadata.sourceTranscript,
            tags = metadata.tags,
            assets = metadata.assets,
            relations = metadata.relations,
            updatedAtEpochMs = maxOf(updatedAtEpochMs, content.updatedAtEpochMs),
            markdown = content.markdown,
        )
    }
}
