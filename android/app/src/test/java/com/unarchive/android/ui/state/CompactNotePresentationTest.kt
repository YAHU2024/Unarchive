package com.unarchive.android.ui.state

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardStageState
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactNotePresentationTest {
    @Test
    fun localFailureAndUnsavedChangesTakePriorityOverMultipleSyncErrors() {
        val document = note()
        val records = listOf(
            record(document, "one", KnowledgeSyncState.PERMANENT_FAILURE),
            record(document, "two", KnowledgeSyncState.RETRYABLE_FAILURE),
        )

        assertEquals(CompactNoteStatus.SYNC_ABNORMAL, document.compactStatus(records))
        assertEquals(
            CompactNoteStatus.UNSAVED,
            document.copy(editing = document.editing.copy(dirty = true)).compactStatus(records),
        )
        assertEquals(
            CompactNoteStatus.SAVE_FAILED,
            document.copy(editing = document.editing.copy(dirty = true, lastSaveError = "save failed"))
                .compactStatus(records),
        )
    }

    @Test
    fun singleAbnormalTargetUsesItsShortStatusAndCannotBeMaskedByNewerSuccess() {
        val document = note()
        val states = mapOf(
            KnowledgeSyncState.RETRYABLE_FAILURE to CompactNoteStatus.SYNC_RETRY,
            KnowledgeSyncState.PERMANENT_FAILURE to CompactNoteStatus.SYNC_FAILED,
            KnowledgeSyncState.BLOCKED to CompactNoteStatus.SYNC_BLOCKED,
        )
        for ((state, expected) in states) {
            val records = listOf(
                record(document, "failed", state, updatedAt = 1L),
                record(document, "active", KnowledgeSyncState.ASSOCIATING, updatedAt = 10L),
                record(document, "succeeded", KnowledgeSyncState.SYNCED, updatedAt = 20L),
            )
            assertEquals(expected, document.compactStatus(records))
            assertEquals(expected, document.compactStatus(records.reversed()))
            assertTrue(expected.isError)
        }
    }

    @Test
    fun twoTargetsWithTheSameFailureStillProduceGenericSyncAbnormal() {
        val document = note()
        val records = listOf(
            record(document, "one", KnowledgeSyncState.RETRYABLE_FAILURE),
            record(document, "two", KnowledgeSyncState.RETRYABLE_FAILURE),
        )

        assertEquals(CompactNoteStatus.SYNC_ABNORMAL, document.compactStatus(records))
        assertEquals("同步异常", document.compactStatus(records).label)
    }

    @Test
    fun duplicateAttemptsUseLatestStateAndCountAsOneTarget() {
        val document = note()
        val records = listOf(
            record(document, "one", KnowledgeSyncState.PERMANENT_FAILURE, updatedAt = 1L),
            record(document, "one", KnowledgeSyncState.RETRYABLE_FAILURE, updatedAt = 2L),
        )
        assertEquals(CompactNoteStatus.SYNC_RETRY, document.compactStatus(records))
        val recovered = records + record(document, "one", KnowledgeSyncState.SYNCED, updatedAt = 3L)
        assertEquals(CompactNoteStatus.SAVED, document.compactStatus(recovered))
        assertEquals(CompactNoteStatus.SAVED, document.compactStatus(recovered.reversed()))
    }

    @Test
    fun equalTimestampDuplicatesAreDeterministicAndPreserveFailure() {
        val document = note()
        val records = listOf(
            record(document, "same", KnowledgeSyncState.PERMANENT_FAILURE),
            record(document, "same", KnowledgeSyncState.SYNCED),
        )
        assertEquals(CompactNoteStatus.SYNC_FAILED, document.compactStatus(records))
        assertEquals(CompactNoteStatus.SYNC_FAILED, document.compactStatus(records.reversed()))
    }

    @Test
    fun foreignNoteVersionAndRevisionNeverAffectCurrentStatus() {
        val document = note()
        val current = record(document, "target", KnowledgeSyncState.SYNCED)
        val stale = current.copy(
            key = current.key.copy(contentRevision = document.editing.contentRevision - 1L),
            state = KnowledgeSyncState.PERMANENT_FAILURE,
        )
        val otherVersion = stale.copy(key = current.key.copy(cardVersion = "other-version"))
        val otherCard = stale.copy(key = current.key.copy(cardId = KnowledgeCardId("bilibili", "BV_other")))
        assertEquals(CompactNoteStatus.SAVED, document.compactStatus(listOf(stale, otherVersion, otherCard, current)))
    }

    @Test
    fun activeTargetsShowSyncingAndNormalTargetsShowOnlyLocalSaved() {
        val document = note()
        for (state in listOf(KnowledgeSyncState.CREATING, KnowledgeSyncState.CREATED, KnowledgeSyncState.ASSOCIATING)) {
            assertEquals(CompactNoteStatus.SYNCING, document.compactStatus(listOf(record(document, "target", state))))
        }
        for (state in listOf(KnowledgeSyncState.NOT_SYNCED, KnowledgeSyncState.SYNCED)) {
            assertEquals(CompactNoteStatus.SAVED, document.compactStatus(listOf(record(document, "target", state))))
        }
        assertEquals(CompactNoteStatus.SAVED, document.compactStatus())
        assertFalse(CompactNoteStatus.SAVED.isError)
    }

    @Test
    fun previewNormalizesWhitespaceAndRetainsExactlyBoundedText() {
        assertEquals("首行 第二行 末尾", " \t首行\r\n 第二行\u00a0\u3000末尾 \n".compactNotePreview())
        assertEquals("", " \r\n\u00a0".compactNotePreview())
        assertEquals("字".repeat(160), "字".repeat(160).compactNotePreview())
        assertEquals("字".repeat(159) + "…", "字".repeat(161).compactNotePreview())
    }

    @Test
    fun previewCountsSupplementaryUnicodeCodePointsWithoutSplittingSurrogatePairs() {
        val original = "😀".repeat(161)
        val preview = original.compactNotePreview()
        assertEquals("😀".repeat(159) + "…", preview)
        assertEquals(160, preview.codePointCount(0, preview.length))
        assertEquals("😀".repeat(161), original)
    }

    @Test
    fun summaryBlocksWinOverNotesAndTranscriptWithoutChangingTheDocument() {
        val document = note().copy(
            blocks = listOf(
                block("user", NoteBlockType.USER_NOTE, "用户备注"),
                block("summary", NoteBlockType.SUMMARY, "完整摘要\n正文"),
            ),
            sourceTranscript = listOf(TranscriptSegment(0L, 1_000L, "原始转写")),
        )
        assertEquals("完整摘要 正文", document.compactSummaryPreview())
        assertEquals("完整摘要\n正文", document.blocks.last().text)
        assertEquals("原始转写", document.sourceTranscript.single().text)
    }

    @Test
    fun fallsBackToFirstUserOrKeyPointThenTranscriptThenPlaceholder() {
        val document = note().copy(
            blocks = listOf(
                block("user", NoteBlockType.USER_NOTE, "用户备注"),
                block("key", NoteBlockType.KEY_POINT, "后面的要点"),
            ),
        )
        assertEquals("用户备注", document.compactSummaryPreview())
        assertEquals(
            "第一段 第二段",
            document.copy(blocks = emptyList(), sourceTranscript = listOf(
                TranscriptSegment(0L, 1_000L, " 第一段\n"),
                TranscriptSegment(1_000L, 2_000L, "\t第二段 "),
            )).compactSummaryPreview(),
        )
        assertEquals("暂无摘要", document.copy(blocks = emptyList(), sourceTranscript = emptyList()).compactSummaryPreview())
    }

    @Test
    fun transcriptFallbackStopsReadingAsSoonAsPreviewIsBounded() {
        var reads = 0
        val transcript = object : AbstractList<TranscriptSegment>() {
            override val size = 100_000
            override fun get(index: Int): TranscriptSegment {
                reads++
                check(index == 0) { "fallback traversed the remaining transcript" }
                return TranscriptSegment(0L, 1_000L, "转".repeat(161))
            }
        }
        val document = note().copy(blocks = emptyList(), sourceTranscript = transcript)

        assertEquals("转".repeat(159) + "…", document.compactSummaryPreview())
        assertEquals(1, reads)
    }

    @Test
    fun libraryProjectionUsesBoundedPreviewAndTypedAggregateStatus() {
        val document = note().copy(blocks = listOf(block("summary", NoteBlockType.SUMMARY, "摘".repeat(2_000))))
        val item = buildNotesLibraryItems(
            noteDocuments = listOf(document), noteCards = emptyList(), storedResults = emptyList(),
            syncRecords = listOf(record(document, "target", KnowledgeSyncState.BLOCKED)),
        ).single()

        assertEquals("摘".repeat(159) + "…", item.summary)
        assertEquals(CompactNoteStatus.SYNC_BLOCKED, item.compactStatus)
        assertEquals(document, item.document)
    }

    private fun block(id: String, type: NoteBlockType, text: String) =
        NoteBlock(id, type, NoteBlockOrigin.USER, text = text)

    private fun note(): NoteDocument = NoteDocument(
        cardId = KnowledgeCardId("bilibili", "BV_compact"),
        title = "紧凑笔记",
        source = NoteSource("https://www.bilibili.com/video/BV_compact", "作者", 344_000L, TranscriptTimingAccuracy.EXACT),
        sourceTranscript = emptyList(),
        blocks = emptyList(),
        generation = NoteGeneration(
            "version-compact", state = NoteGenerationState.COMPLETE,
            baseState = CardStageState.SUCCEEDED, analysisState = CardStageState.SUCCEEDED,
            screenshotsState = CardStageState.SUCCEEDED,
        ),
        editing = NoteEditingState(contentRevision = 2L),
        publishing = NotePublishingState(),
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )

    private fun record(
        document: NoteDocument,
        target: String,
        state: KnowledgeSyncState,
        updatedAt: Long = 1L,
    ) = KnowledgeSyncRecord(
        key = KnowledgeSyncKey(document.cardId, document.generation.cardVersion, "ima", target,
            contentRevision = document.editing.contentRevision),
        state = state,
        updatedAtEpochMs = updatedAt,
    )
}
