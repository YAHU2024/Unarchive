package com.unarchive.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteBlock
import com.unarchive.android.card.NoteBlockOrigin
import com.unarchive.android.card.NoteBlockType
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteGeneration
import com.unarchive.android.card.NoteGenerationState
import com.unarchive.android.card.NoteEditingState
import com.unarchive.android.card.NotePublishingState
import com.unarchive.android.card.NoteSource
import com.unarchive.android.card.NoteSourceRef
import com.unarchive.android.editor.NoteEditorScreen
import com.unarchive.android.editor.NoteEditorUiState
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NoteEditorUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersStructuredBlocksAndEditingActions() {
        composeRule.setContent {
            UnarchiveTheme {
                NoteEditorScreen(
                    state = NoteEditorUiState(document()),
                    onEvent = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("note-editor-title").assertIsDisplayed()
        composeRule.onNodeWithTag("note-editor-ai-proposal").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("保存状态：尚未编辑").assertIsDisplayed()
        composeRule.onNodeWithTag("note-block-text-summary-1").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithText("AI 草稿，可编辑").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("note-source-chapter-1").performScrollTo().assertExists()
        composeRule.onNodeWithTag("note-add-user_note").performScrollTo().assertExists()
    }

    @Test
    fun longContentRemainsScrollableAndSemanticsSurviveLargeFontScale() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                UnarchiveTheme {
                    NoteEditorScreen(
                        state = NoteEditorUiState(longDocument()),
                        onEvent = {},
                        onBack = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("note-editor-scroll").assertIsDisplayed()
        composeRule.onNodeWithTag("note-block-text-summary-1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("note-editor-ai-proposal").assertIsDisplayed()
        composeRule.onNodeWithTag("note-editor-save").assertIsDisplayed()
        composeRule.onNodeWithText("摘要").assertIsDisplayed()
    }

    private fun document() = NoteDocument(
        cardId = KnowledgeCardId("bilibili", "BV1ui"),
        title = "编辑器 UI 测试",
        source = NoteSource(
            canonicalUrl = "https://www.bilibili.com/video/BV1ui",
            ownerName = "UP 主",
            durationMs = 10_000L,
            timingAccuracy = TranscriptTimingAccuracy.EXACT,
        ),
        sourceTranscript = emptyList(),
        blocks = listOf(
            NoteBlock("summary-1", NoteBlockType.SUMMARY, NoteBlockOrigin.AI, text = "摘要"),
            NoteBlock(
                id = "chapter-1",
                type = NoteBlockType.CHAPTER,
                origin = NoteBlockOrigin.AI,
                title = "章节",
                startMs = 0L,
                endMs = 10_000L,
                sourceRef = NoteSourceRef(
                    url = "https://www.bilibili.com/video/BV1ui",
                    startMs = 0L,
                    endMs = 10_000L,
                ),
            ),
        ),
        generation = NoteGeneration(
            cardVersion = "ui-version",
            state = NoteGenerationState.COMPLETE,
            baseState = CardStageState.SUCCEEDED,
            analysisState = CardStageState.SUCCEEDED,
            screenshotsState = CardStageState.SKIPPED,
        ),
        editing = NoteEditingState(),
        publishing = NotePublishingState(),
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
    )

    private fun longDocument(): NoteDocument = document().copy(
        blocks = document().blocks.map { block ->
            if (block.type == NoteBlockType.SUMMARY) {
                block.copy(text = "长文本段落。".repeat(2_000))
            } else {
                block
            }
        },
    )
}
