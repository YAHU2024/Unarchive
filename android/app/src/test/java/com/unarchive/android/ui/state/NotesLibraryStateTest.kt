package com.unarchive.android.ui.state

import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardRelation
import com.unarchive.android.card.CardRelationType
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.KnowledgeSyncKey
import com.unarchive.android.card.KnowledgeSyncRecord
import com.unarchive.android.card.KnowledgeSyncState
import com.unarchive.android.card.NoteBlock
import com.unarchive.android.card.NoteBlockOrigin
import com.unarchive.android.card.NoteBlockType
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteEditingState
import com.unarchive.android.card.NoteGeneration
import com.unarchive.android.card.NoteGenerationState
import com.unarchive.android.card.NotePublishingState
import com.unarchive.android.card.NoteSource
import com.unarchive.android.result.StoredVideoResult
import com.unarchive.android.result.VideoResultKey
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesLibraryStateTest {
    @Test
    fun buildsRecentlyUpdatedNotesAndOnlyUnconvertedMaterials() {
        val saved = note("saved", updatedAt = 2_000L, summary = "  精炼\n 摘要  ")
        val convertedResult = result("saved", updatedAt = 4_000L)
        val pendingResult = result("pending", updatedAt = 3_000L)

        val items = buildNotesLibraryItems(
            noteDocuments = listOf(saved),
            noteCards = listOf(card(saved)),
            storedResults = listOf(convertedResult, pendingResult),
        )

        assertEquals(listOf("待整理 pending", "笔记 saved"), items.map { it.title })
        assertEquals("精炼 摘要", items.last().summary)
        assertEquals("外部去向 · 未同步", items.last().destinationSummary)
        assertEquals("转写 pending", items.first().summary)
        assertEquals(1, items.forFilter(NotesFilter.SAVED).size)
        assertEquals(1, items.forFilter(NotesFilter.NEEDS_ORGANIZING).size)
        assertEquals("待整理素材", items.first().localStatusLabel)
        assertNull(items.first().document)
    }

    @Test
    fun mapsCurrentRevisionMetadataWithoutLeakingDestinationIds() {
        val document = note("metadata", updatedAt = 5_000L, summary = "摘要", contentRevision = 2L)
        val current = syncRecord(document, revision = 2L, updatedAt = 8_000L)
        val stale = syncRecord(document, revision = 1L, updatedAt = 9_000L)

        val item = buildNotesLibraryItems(
            noteDocuments = listOf(document),
            noteCards = listOf(card(document)),
            storedResults = emptyList(),
            syncRecords = listOf(current, stale),
            thumbnailPaths = mapOf((document.cardId to document.generation.cardVersion) to "C:/notes/cover.jpg"),
        ).single()

        assertEquals(listOf("跑步", "训练"), item.tags)
        assertEquals(1, item.relationCount)
        assertEquals("C:/notes/cover.jpg", item.thumbnailPath)
        assertEquals("ima · 学习资料 · 根目录 · 已同步", item.destinationSummary)
        assertFalse(item.destinationSummary!!.contains("kb-secret"))
        assertEquals("本地已保存", item.localStatusLabel)
    }

    @Test
    fun formatsRecentUpdateTimesAgainstTheLocalCalendar() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 8, 24, 15, 0, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals(
            "今天 09:05",
            formatNotesUpdatedAt(
                ZonedDateTime.of(2026, 8, 24, 9, 5, 0, 0, zone).toInstant().toEpochMilli(),
                now,
                zone,
            ),
        )
        assertEquals(
            "昨天 23:10",
            formatNotesUpdatedAt(
                ZonedDateTime.of(2026, 8, 23, 23, 10, 0, 0, zone).toInstant().toEpochMilli(),
                now,
                zone,
            ),
        )
        assertEquals(
            "2025年12月31日",
            formatNotesUpdatedAt(
                ZonedDateTime.of(2025, 12, 31, 23, 10, 0, 0, zone).toInstant().toEpochMilli(),
                now,
                zone,
            ),
        )
    }

    private fun note(
        suffix: String,
        updatedAt: Long,
        summary: String,
        contentRevision: Long = 0L,
    ): NoteDocument {
        val cardId = KnowledgeCardId("bilibili", "BV_$suffix")
        val tags = listOf("跑步", "训练")
        return NoteDocument(
            cardId = cardId,
            title = "笔记 $suffix",
            source = NoteSource("https://www.bilibili.com/video/BV_$suffix", "UP 主", 120_000L, TranscriptTimingAccuracy.EXACT),
            sourceTranscript = listOf(TranscriptSegment(0, 1_000, "原始转写")),
            blocks = listOf(
                NoteBlock("summary", NoteBlockType.SUMMARY, NoteBlockOrigin.AI, text = summary),
                NoteBlock("tags", NoteBlockType.TAG_LIST, NoteBlockOrigin.USER, tags = tags),
            ),
            tags = tags,
            relations = listOf(
                CardRelation("source", CardRelationType.SOURCE_VIDEO, cardId.value, "来源", 1L, cardId.value),
                CardRelation("link", CardRelationType.USER_LINK, "bilibili:BV_other", "延伸", 2L, cardId.value),
            ),
            generation = NoteGeneration(
                cardVersion = "version-$suffix",
                state = NoteGenerationState.COMPLETE,
                baseState = CardStageState.SUCCEEDED,
                analysisState = CardStageState.SUCCEEDED,
                screenshotsState = CardStageState.SUCCEEDED,
            ),
            editing = NoteEditingState(contentRevision = contentRevision, lastSavedAtEpochMs = updatedAt),
            publishing = NotePublishingState(updatedAtEpochMs = updatedAt),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = updatedAt,
        )
    }

    private fun card(document: NoteDocument) = KnowledgeCard(
        cardId = document.cardId,
        cardVersion = document.generation.cardVersion,
        canonicalUrl = document.source.canonicalUrl,
        title = document.title,
        ownerName = document.source.ownerName,
        videoDurationSeconds = document.source.durationMs / 1_000L,
        timingAccuracy = document.source.timingAccuracy,
        transcript = document.sourceTranscript,
        tags = document.tags,
        relations = document.relations,
        analysisState = CardStageState.SUCCEEDED,
        screenshotsState = CardStageState.SUCCEEDED,
        createdAtEpochMs = document.createdAtEpochMs,
        updatedAtEpochMs = document.updatedAtEpochMs,
        markdown = "# ${document.title}",
    )

    private fun result(suffix: String, updatedAt: Long) = StoredVideoResult(
        key = VideoResultKey("bilibili", "BV_$suffix"),
        canonicalUrl = "https://www.bilibili.com/video/BV_$suffix",
        title = "待整理 $suffix",
        ownerName = "素材作者",
        videoDurationSeconds = 90L,
        engine = AsrEngineKind.SENSE_VOICE_SHERPA,
        processingDurationMs = 1_000L,
        audioDurationMs = 90_000L,
        segments = listOf(TranscriptSegment(0, 1_000, "转写 $suffix")),
        createdAtEpochMs = 1L,
        updatedAtEpochMs = updatedAt,
    )

    private fun syncRecord(document: NoteDocument, revision: Long, updatedAt: Long) = KnowledgeSyncRecord(
        key = KnowledgeSyncKey(
            cardId = document.cardId,
            cardVersion = document.generation.cardVersion,
            targetType = "ima",
            targetId = "kb-secret",
            contentRevision = revision,
        ),
        state = KnowledgeSyncState.SYNCED,
        updatedAtEpochMs = updatedAt,
        targetName = "学习资料",
    )
}
