package com.unarchive.android.ui.state

import com.unarchive.android.card.CardRelationType
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.KnowledgeSyncRecord
import com.unarchive.android.card.KnowledgeSyncState
import com.unarchive.android.card.NoteBlockType
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.result.StoredVideoResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class NotesFilter { RECENT, NEEDS_ORGANIZING, SAVED }

internal enum class NotesLibraryItemKind { SAVED_NOTE, MATERIAL }

internal data class NotesLibraryItem(
    val kind: NotesLibraryItemKind,
    val title: String,
    val summary: String,
    val ownerName: String,
    val durationSeconds: Long,
    val tags: List<String>,
    val relationCount: Int,
    val updatedAtEpochMs: Long,
    val localStatusLabel: String,
    val destinationSummary: String? = null,
    val thumbnailPath: String? = null,
    val document: NoteDocument? = null,
    val material: StoredVideoResult? = null,
) {
    init {
        require((document == null) != (material == null)) {
            "a Notes item must contain exactly one note or material"
        }
    }

    val stableKey: String
        get() = document?.let {
            "note:${it.cardId.value}:${it.generation.cardVersion}"
        } ?: material!!.let { "material:${it.key.platform}:${it.key.videoId}" }
}

internal fun List<NotesLibraryItem>.forFilter(filter: NotesFilter): List<NotesLibraryItem> = when (filter) {
    NotesFilter.RECENT -> this
    NotesFilter.NEEDS_ORGANIZING -> filter { it.kind == NotesLibraryItemKind.MATERIAL }
    NotesFilter.SAVED -> filter { it.kind == NotesLibraryItemKind.SAVED_NOTE }
}

internal fun buildNotesLibraryItems(
    noteDocuments: List<NoteDocument>,
    noteCards: List<KnowledgeCard>,
    storedResults: List<StoredVideoResult>,
    syncRecords: List<KnowledgeSyncRecord> = emptyList(),
    thumbnailPaths: Map<Pair<KnowledgeCardId, String>, String> = emptyMap(),
): List<NotesLibraryItem> {
    val cardsById = noteCards.associateBy(KnowledgeCard::cardId)
    val cardsByVersion = noteCards.associateBy { it.cardId to it.cardVersion }
    val savedIds = noteDocuments.mapTo(mutableSetOf(), NoteDocument::cardId)

    val notes = noteDocuments.map { document ->
        val exactCard = cardsByVersion[document.cardId to document.generation.cardVersion]
        val card = exactCard ?: cardsById[document.cardId]
        val currentRecords = syncRecords.filter { record ->
            record.key.cardId == document.cardId &&
                record.key.cardVersion == document.generation.cardVersion &&
                record.key.contentRevision == document.editing.contentRevision
        }
        NotesLibraryItem(
            kind = NotesLibraryItemKind.SAVED_NOTE,
            title = document.title,
            summary = document.summaryText(),
            ownerName = document.source.ownerName,
            durationSeconds = document.source.durationMs / 1_000L,
            tags = document.tags,
            relationCount = document.relations.count { it.type == CardRelationType.USER_LINK },
            updatedAtEpochMs = document.updatedAtEpochMs,
            localStatusLabel = when {
                document.editing.lastSaveError != null -> "本地保存失败"
                document.editing.dirty -> "有未保存更改"
                else -> "本地已保存"
            },
            destinationSummary = currentRecords.destinationSummary(),
            thumbnailPath = thumbnailPaths[document.cardId to document.generation.cardVersion]
                ?: card?.let { thumbnailPaths[it.cardId to it.cardVersion] },
            document = document,
        )
    }

    val materials = storedResults
        .filterNot { KnowledgeCardId.from(it.key) in savedIds }
        .map { result ->
            NotesLibraryItem(
                kind = NotesLibraryItemKind.MATERIAL,
                title = result.title,
                summary = result.segments.joinToString(" ") { it.text }.normalizedPreview(),
                ownerName = result.ownerName,
                durationSeconds = result.videoDurationSeconds,
                tags = emptyList(),
                relationCount = 0,
                updatedAtEpochMs = result.updatedAtEpochMs,
                localStatusLabel = "待整理素材",
                material = result,
            )
        }

    return (notes + materials).sortedWith(
        compareByDescending<NotesLibraryItem> { it.updatedAtEpochMs }
            .thenBy { it.title }
            .thenBy { it.stableKey },
    )
}

internal fun formatNotesUpdatedAt(
    epochMs: Long,
    nowEpochMs: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val dateTime = Instant.ofEpochMilli(epochMs.coerceAtLeast(0L)).atZone(zoneId)
    val today = Instant.ofEpochMilli(nowEpochMs.coerceAtLeast(0L)).atZone(zoneId).toLocalDate()
    val time = dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (dateTime.toLocalDate()) {
        today -> "今天 $time"
        today.minusDays(1) -> "昨天 $time"
        else -> if (dateTime.year == today.year) {
            dateTime.format(DateTimeFormatter.ofPattern("M月d日"))
        } else {
            dateTime.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
        }
    }
}

private fun NoteDocument.summaryText(): String =
    blocks.firstOrNull { it.type == NoteBlockType.SUMMARY }?.text
        ?.normalizedPreview()
        ?.takeIf(String::isNotEmpty)
        ?: blocks.firstOrNull { it.type == NoteBlockType.KEY_POINT || it.type == NoteBlockType.USER_NOTE }
            ?.text
            ?.normalizedPreview()
            ?.takeIf(String::isNotEmpty)
        ?: sourceTranscript.joinToString(" ") { it.text }.normalizedPreview()

private fun String.normalizedPreview(): String = trim().replace(Regex("\\s+"), " ")

private fun List<KnowledgeSyncRecord>.destinationSummary(): String {
    if (isEmpty()) return "外部去向 · 未同步"
    val record = maxByOrNull(KnowledgeSyncRecord::updatedAtEpochMs)!!
    val targetType = if (record.key.targetType.equals("ima", ignoreCase = true)) "ima" else "外部去向"
    val targetName = record.targetName.trim().takeIf(String::isNotEmpty)
    val folderName = record.folderName.trim().takeIf(String::isNotEmpty)
        ?: if (record.key.folderId.isBlank()) "根目录" else "文件夹"
    val status = when (record.state) {
        KnowledgeSyncState.NOT_SYNCED -> "未同步"
        KnowledgeSyncState.CREATING,
        KnowledgeSyncState.CREATED,
        KnowledgeSyncState.ASSOCIATING,
        -> "同步中"
        KnowledgeSyncState.SYNCED -> "已同步"
        KnowledgeSyncState.RETRYABLE_FAILURE -> "待重试"
        KnowledgeSyncState.PERMANENT_FAILURE -> "同步失败"
        KnowledgeSyncState.BLOCKED -> "已阻断"
    }
    val target = listOfNotNull(targetType, targetName, folderName).joinToString(" · ")
    val otherCount = size - 1
    return "$target · $status" + if (otherCount > 0) " · 另 $otherCount 个去向" else ""
}
