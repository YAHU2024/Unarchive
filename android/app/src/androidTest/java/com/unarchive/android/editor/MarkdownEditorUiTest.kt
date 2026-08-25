package com.unarchive.android.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteContent
import com.unarchive.android.card.NoteContentMigration
import com.unarchive.android.card.NoteContentMigrationDecision
import com.unarchive.android.card.NoteDraftState
import com.unarchive.android.ui.markdown.NoOpMarkdownAssetResolver
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Rule
import org.junit.Test

class MarkdownEditorUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersSingleColumnEditorPreviewAndProjectionStatus() {
        var state by mutableStateOf(MarkdownEditorUiState(content()))
        composeRule.setContent {
            UnarchiveTheme {
                MarkdownEditorScreen(
                    state = state,
                    onEvent = { event ->
                        if (event is MarkdownEditorEvent.MarkdownChanged) {
                            state = state.copy(draftMarkdown = event.value, saveState = MarkdownEditorSaveState.DIRTY)
                        }
                    },
                    onBack = {},
                    assetResolver = NoOpMarkdownAssetResolver,
                )
            }
        }

        composeRule.onNodeWithTag("markdown-editor-source").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("保存状态：尚未编辑；投影状态：结构化投影已同步").assertIsDisplayed()
        composeRule.onNodeWithTag("markdown-editor-source").performTextReplacement("# 新标题")
        composeRule.onNodeWithContentDescription("保存状态：有未保存修改，正在保护草稿；投影状态：Markdown 已变更，结构化投影待更新").assertIsDisplayed()
        composeRule.onNodeWithTag("markdown-editor-preview-tab").performClick()
        composeRule.waitUntil(timeoutMillis = 1500L) {
            composeRule.onAllNodesWithText("新标题").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("markdown-editor-preview").assertIsDisplayed()
    }

    @Test
    fun recoveryDialogRequiresAnExplicitChoice() {
        val formal = content()
        var applied = false
        var discarded = false
        composeRule.setContent {
            UnarchiveTheme {
                MarkdownEditorScreen(
                    state = MarkdownEditorUiState(
                        content = formal,
                        recoveryDraft = NoteDraftState(
                            markdown = "# 恢复内容",
                            baseMarkdownRevision = formal.markdownRevision,
                            contentFingerprint = NoteContent.markdownFingerprint("# 恢复内容"),
                            updatedAtEpochMs = 2_000L,
                        ),
                    ),
                    onEvent = { event ->
                        applied = event == MarkdownEditorEvent.ApplyRecoveredDraft
                        discarded = event == MarkdownEditorEvent.DiscardRecoveredDraft
                    },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("发现未提交草稿，尚未覆盖正式正文").assertIsDisplayed()
        composeRule.onNodeWithTag("markdown-editor-recover-draft").performClick()
        assert(applied)
        composeRule.onNodeWithTag("markdown-editor-discard-draft").performClick()
        assert(discarded)
    }

    private fun content(): NoteContent {
        val card = KnowledgeCard(
            cardId = KnowledgeCardId("bilibili", "BV_ui_v3"),
            cardVersion = "v1",
            canonicalUrl = "https://www.bilibili.com/video/BV_ui_v3",
            title = "Markdown UI",
            ownerName = "测试作者",
            videoDurationSeconds = 10,
            timingAccuracy = TranscriptTimingAccuracy.EXACT,
            transcript = emptyList(),
            analysisState = CardStageState.SKIPPED,
            screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
            markdown = "# Markdown UI",
        )
        return when (val migration = NoteContentMigration.fromKnowledgeCard(card)) {
            is NoteContentMigrationDecision.Ready -> migration.content
            is NoteContentMigrationDecision.Conflict ->
                NoteContentMigration.resolveConflict(migration, migration.generatedMarkdown).content
        }
    }
}
