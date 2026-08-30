package com.unarchive.android.card

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FormalMarkdownSnapshotTest {
    @Test
    fun v3FormalContentReplacesLegacyCardAndUsesMarkdownRevision() {
        val legacy = card(title = "旧标题", markdown = "# 旧正文")
        val metadata = legacy.toNoteDocument().copy(title = "正式标题")
        val content = NoteContent(
            cardId = legacy.cardId,
            cardVersion = legacy.cardVersion,
            markdown = "# 正式正文",
            markdownRevision = 7L,
            structuredMetadata = metadata,
            draftState = NoteDraftState(
                markdown = "# 未提交草稿",
                baseMarkdownRevision = 7L,
                contentFingerprint = NoteContent.markdownFingerprint("# 未提交草稿"),
                updatedAtEpochMs = 3L,
            ),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
        )

        val snapshot = FormalMarkdownSnapshotResolver.resolve(legacy, content, legacyMarkdownRevision = 4L)

        assertEquals(FormalMarkdownSource.MARKDOWN_V3, snapshot.source)
        assertEquals(7L, snapshot.markdownRevision)
        assertEquals("正式标题", snapshot.card.title)
        assertEquals("# 正式正文", snapshot.card.markdown)
        assertEquals(legacy.cardVersion, snapshot.card.cardVersion)
    }

    @Test
    fun missingOrMismatchedV3ContentFallsBackToLegacyFormalCard() {
        val legacy = card()
        val mismatched = NoteContent(
            cardId = KnowledgeCardId("bilibili", "OTHER"),
            cardVersion = legacy.cardVersion,
            markdown = "# other",
            markdownRevision = 9L,
            structuredMetadata = card(videoId = "OTHER").toNoteDocument(),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )

        val snapshot = FormalMarkdownSnapshotResolver.resolve(legacy, mismatched, legacyMarkdownRevision = 3L)

        assertEquals(FormalMarkdownSource.LEGACY_V2, snapshot.source)
        assertEquals(3L, snapshot.markdownRevision)
        assertSame(legacy, snapshot.card)
        assertTrue(snapshot.card.markdown.isNotBlank())
    }

    private fun card(
        videoId: String = "BV_FORMAL",
        title: String = "测试卡片",
        markdown: String = "# 测试卡片",
    ) = KnowledgeCard(
        cardId = KnowledgeCardId("bilibili", videoId),
        cardVersion = "version-1",
        canonicalUrl = "https://www.bilibili.com/video/$videoId",
        title = title,
        ownerName = "作者",
        videoDurationSeconds = 12,
        timingAccuracy = TranscriptTimingAccuracy.EXACT,
        transcript = listOf(TranscriptSegment(0L, 1_000L, "内容")),
        analysisState = CardStageState.SKIPPED,
        screenshotsState = CardStageState.SKIPPED,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        markdown = markdown,
    )
}
