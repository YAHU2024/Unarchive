package com.unarchive.android.card

import java.security.MessageDigest

/** The relation types that are generated from durable note facts. */
object NoteRelationPolicy {
    fun normalizeSystemRelations(document: NoteDocument): NoteDocument {
        val sourceId = document.cardId.value
        val normalizedTags = document.tags.map(String::trim).filter(String::isNotEmpty).distinct()
        val retained = document.relations
            .map { relation ->
                if (relation.sourceCardId != sourceId) relation.copy(sourceCardId = sourceId) else relation
            }
            .filterNot { relation ->
                relation.type == CardRelationType.TAG &&
                    relation.sourceCardId == sourceId &&
                    relation.targetId.startsWith(TAG_PREFIX) &&
                    relation.targetId.removePrefix(TAG_PREFIX) !in normalizedTags
            }
            .distinctBy { it.type to it.targetId }
            .toMutableList()

        if (retained.none { it.type == CardRelationType.SOURCE_VIDEO && it.targetId == document.source.canonicalUrl }) {
            retained += CardRelation(
                relationId = stableRelationId(sourceId, CardRelationType.SOURCE_VIDEO, document.source.canonicalUrl),
                type = CardRelationType.SOURCE_VIDEO,
                targetId = document.source.canonicalUrl,
                label = "来源视频",
                createdAtEpochMs = document.createdAtEpochMs,
                sourceCardId = sourceId,
                description = "由此视频生成的知识笔记",
            )
        }
        normalizedTags.forEach { tag ->
            val targetId = TAG_PREFIX + tag
            if (retained.none { it.type == CardRelationType.TAG && it.targetId == targetId }) {
                retained += CardRelation(
                    relationId = stableRelationId(sourceId, CardRelationType.TAG, targetId),
                    type = CardRelationType.TAG,
                    targetId = targetId,
                    label = "#$tag",
                    createdAtEpochMs = document.createdAtEpochMs,
                    sourceCardId = sourceId,
                    description = "标签关系",
                )
            }
        }
        return document.copy(relations = retained)
    }

    fun stableRelationId(sourceCardId: String, type: CardRelationType, targetId: String): String =
        "${type.name.lowercase()}-${sha256("$sourceCardId|${type.name}|$targetId").take(20)}"

    const val TAG_PREFIX = "tag:"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

enum class RelationDirection { OUTGOING, INCOMING }

data class GraphRelationItem(
    val relation: CardRelation,
    val sourceCardId: String,
    val sourceTitle: String,
    val targetCardId: String? = null,
    val targetTitle: String,
    val direction: RelationDirection,
) {
    val canDelete: Boolean get() = relation.type != CardRelationType.SOURCE_VIDEO
}

data class NoteRelationSnapshot(
    val outgoing: List<GraphRelationItem>,
    val incoming: List<GraphRelationItem>,
) {
    val all: List<GraphRelationItem> get() = outgoing + incoming
}

data class NoteRelationMutationResult(
    val document: NoteDocument? = null,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = document != null && error == null
}

/** Relation operations over the structured note repository. */
class NoteRelationRepository(
    private val repository: NoteDocumentRepository,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    fun ensureSystemRelations(documents: List<NoteDocument>): List<NoteDocument> {
        documents.forEach { document ->
            val normalized = NoteRelationPolicy.normalizeSystemRelations(document)
            if (normalized.relations != document.relations) {
                repository.save(normalized)
            }
        }
        return repository.list()
    }

    fun snapshot(documents: List<NoteDocument>, cardId: KnowledgeCardId): NoteRelationSnapshot {
        val current = documents.firstOrNull { it.cardId == cardId }
            ?: return NoteRelationSnapshot(emptyList(), emptyList())
        val byId = documents.associateBy { it.cardId.value }
        val outgoing = current.relations.map { relation ->
            relation.toGraphItem(
                source = current,
                target = byId[relation.targetId],
                direction = RelationDirection.OUTGOING,
            )
        }
        val incoming = documents.asSequence()
            .filter { it.cardId != cardId }
            .flatMap { source ->
                source.relations.asSequence()
                    .filter { it.targetId == cardId.value }
                    .map { relation ->
                        relation.toGraphItem(
                            source = source,
                            target = current,
                            direction = RelationDirection.INCOMING,
                        )
                    }
            }
            .toList()
        return NoteRelationSnapshot(outgoing = outgoing, incoming = incoming)
    }

    fun addUserLink(
        sourceCardId: KnowledgeCardId,
        targetCardId: KnowledgeCardId,
        label: String,
        description: String,
    ): NoteRelationMutationResult {
        if (sourceCardId == targetCardId) return NoteRelationMutationResult(error = "不能把笔记关联到自身。")
        val target = repository.find(targetCardId)
            ?: return NoteRelationMutationResult(error = "找不到要关联的笔记。")
        val source = repository.find(sourceCardId)
            ?: return NoteRelationMutationResult(error = "找不到当前笔记。")
        val cleanLabel = label.trim().ifBlank { target.title.trim() }
        val cleanDescription = description.trim()
        val existing = source.relations.firstOrNull {
            it.type == CardRelationType.USER_LINK && it.targetId == targetCardId.value
        }
        val relation = if (existing == null) {
            CardRelation(
                relationId = NoteRelationPolicy.stableRelationId(sourceCardId.value, CardRelationType.USER_LINK, targetCardId.value),
                type = CardRelationType.USER_LINK,
                targetId = targetCardId.value,
                label = cleanLabel,
                createdAtEpochMs = nowEpochMs(),
                sourceCardId = sourceCardId.value,
                description = cleanDescription,
            )
        } else {
            existing.copy(label = cleanLabel, description = cleanDescription, sourceCardId = sourceCardId.value)
        }
        val updated = source.copy(
            relations = source.relations.filterNot { it.relationId == relation.relationId } + relation,
            editing = source.editing.copy(dirty = true),
            updatedAtEpochMs = maxOf(source.updatedAtEpochMs, nowEpochMs()),
        )
        return save(updated)
    }

    fun removeRelation(sourceCardId: KnowledgeCardId, relationId: String): NoteRelationMutationResult {
        val source = repository.find(sourceCardId)
            ?: return NoteRelationMutationResult(error = "找不到当前笔记。")
        val relation = source.relations.firstOrNull { it.relationId == relationId }
            ?: return NoteRelationMutationResult(error = "关系已不存在，列表将自动刷新。")
        if (!relation.canDelete()) return NoteRelationMutationResult(error = "来源视频关系是笔记事实，不能删除。")
        val withoutRelation = source.relations.filterNot { it.relationId == relationId }
        val tagsAfterRemoval = if (relation.type == CardRelationType.TAG) {
            source.tags.filterNot { it == relation.targetId.removePrefix(NoteRelationPolicy.TAG_PREFIX) }
        } else {
            source.tags
        }
        val blocksAfterRemoval = if (relation.type == CardRelationType.TAG) {
            source.blocks.mapNotNull { block ->
                if (block.type != NoteBlockType.TAG_LIST) {
                    block
                } else if (tagsAfterRemoval.isEmpty()) {
                    null
                } else {
                    block.copy(tags = tagsAfterRemoval)
                }
            }
        } else {
            source.blocks
        }
        return save(source.copy(
            relations = withoutRelation,
            tags = tagsAfterRemoval,
            blocks = blocksAfterRemoval,
            editing = source.editing.copy(dirty = true),
            updatedAtEpochMs = maxOf(source.updatedAtEpochMs, nowEpochMs()),
        ))
    }

    private fun save(document: NoteDocument): NoteRelationMutationResult {
        val result = repository.save(NoteRelationPolicy.normalizeSystemRelations(document))
        return if (result.error == null) {
            NoteRelationMutationResult(document = result.document)
        } else {
            NoteRelationMutationResult(
                document = result.document.takeIf { result.structuredPersisted },
                error = result.error,
            )
        }
    }

    private fun CardRelation.toGraphItem(
        source: NoteDocument,
        target: NoteDocument?,
        direction: RelationDirection,
    ): GraphRelationItem = GraphRelationItem(
        relation = this,
        sourceCardId = source.cardId.value,
        sourceTitle = source.title,
        targetCardId = target?.cardId?.value,
        targetTitle = target?.title ?: when {
            type == CardRelationType.TAG -> targetId.removePrefix(NoteRelationPolicy.TAG_PREFIX)
            type == CardRelationType.SOURCE_VIDEO -> "B 站来源视频"
            else -> label.ifBlank { targetId }
        },
        direction = direction,
    )

    private fun CardRelation.canDelete(): Boolean = type != CardRelationType.SOURCE_VIDEO
}
