package com.unarchive.android.card

import com.unarchive.android.asr.TranscriptTimingAccuracy
import org.junit.Assert.*
import org.junit.Test

class FormalNotePresentationTest {
    @Test fun formalMarkdownSuppliesTitleAndSummaryWhileDraftNeverDoes() {
        val source = "---\ninternal: hidden\n---\n# **新标题**\n\n正式正文 [链接](https://example.com) 😀"
        val formal = content(source).copy(draftState = NoteDraftState("# 私人草稿", 3L,
            NoteContent.markdownFingerprint("# 私人草稿"), 20L))
        val presentation = FormalNotePresentation.from(formal)
        assertEquals("新标题", presentation.title)
        assertEquals("正式正文 链接 😀", presentation.summary)
        assertEquals(3L, presentation.formalRevision)
        assertFalse(presentation.summary.contains("hidden"))
        assertFalse(presentation.summary.contains("草稿"))
    }

    @Test fun emptyFormalBodyUsesDisplayTitleFallbackAndAnEmptyNewSummary() {
        val presentation = FormalNotePresentation.from(content(""))
        assertEquals("metadata title", presentation.title)
        assertEquals("", presentation.summary)
    }

    @Test fun codeHeadingIsNotTheNoteTitleAndPreviewHasABoundedCodePointLength() {
        val presentation = FormalNotePresentation.from(content("```md\n# code title\n```\n\n# actual title\n\n" + "😀".repeat(200)))
        assertEquals("actual title", presentation.title)
        assertTrue(presentation.summary.codePointCount(0, presentation.summary.length) <= 160)
        assertFalse(presentation.summary.endsWith("\uD83D"))
    }

    private fun content(markdown: String): NoteContent {
        val card = KnowledgeCard(
            KnowledgeCardId("bilibili", "BV_PRESENTATION"), "v1", "https://www.bilibili.com/video/BV_PRESENTATION",
            "metadata title", "owner", 1L, TranscriptTimingAccuracy.EXACT, emptyList(),
            analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = "# metadata title\n\nold summary",
        )
        return NoteContent(card.cardId, card.cardVersion, markdown, 3L, card.toNoteDocument(), createdAtEpochMs = 1L, updatedAtEpochMs = 10L)
    }
}
