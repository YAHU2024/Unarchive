package com.unarchive.android.editor

import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownAiSourcePromptTest {
    @Test fun actualFormalMarkdownIsIncludedVerbatimInsteadOfLegacyTranscript() {
        val card = KnowledgeCard(KnowledgeCardId("synthetic", "prompt"), "version", "https://example.com", "title", "owner", 0,
            TranscriptTimingAccuracy.EXACT, emptyList(), analysisState = CardStageState.SKIPPED,
            screenshotsState = CardStageState.SKIPPED, createdAtEpochMs = 1, updatedAtEpochMs = 1, markdown = "old")
        val markdown = "# 新正式标题\r\n\r\n用户新输入😀\r\n![alt](assets/x.png)"
        val content = NoteContent(card.cardId, card.cardVersion, markdown, 4L, card.toNoteDocument(), createdAtEpochMs = 1, updatedAtEpochMs = 1)
        assertTrue(MarkdownAiSourcePrompt.user(content).contains("<formal_note>\n$markdown\n</formal_note>"))
        assertEquals("候选完整末尾", MarkdownAiSourcePrompt.result("{\"markdown\":\"候选完整末尾\"}", "test").markdown)
    }
}
