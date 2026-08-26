package com.unarchive.android

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteBlock
import com.unarchive.android.card.NoteBlockOrigin
import com.unarchive.android.card.NoteBlockType
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteEditingState
import com.unarchive.android.card.NoteGeneration
import com.unarchive.android.card.NoteGenerationState
import com.unarchive.android.card.NotePublishingState
import com.unarchive.android.card.NoteSource
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.DestinationUiState
import com.unarchive.android.ui.state.GraphUiState
import com.unarchive.android.ui.state.MeUiState
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.UnarchiveUiState
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Rule
import org.junit.Test

class DestinationAndSecurityUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun notesReachShareAndDestinationSurfaceWithoutShowingInternalIds() {
        val card = card()
        val document = document()
        val state = UnarchiveUiState(
            create = CreateUiState("", false, "", false, 0),
            notes = NotesUiState(1, 0, listOf(document.title), listOf(card), listOf(document)),
            graph = GraphUiState(1),
            me = MeUiState(),
            destinations = DestinationUiState(card = card, localSaved = true),
        )
        composeRule.setContent {
            UnarchiveTheme {
                UnarchiveNavigationHost(
                    state = state,
                    onCreateEvent = {},
                    onNotesEvent = {},
                    onDestinationEvent = {},
                    onMeEvent = {},
                    legacyTestContent = { _, _ -> Text("legacy test") },
                    legacyResultsContent = { _ -> Text("legacy results") },
                    legacyLogContent = { _ -> Text("legacy log") },
                    legacySettingsContent = { _ -> Text("legacy settings") },
                    securityContent = { _ -> Text("security") },
                )
            }
        }

        composeRule.onNodeWithTag("bottom-nav-notes").performClick()
        composeRule.onNodeWithText("分享与去向").performClick()
        composeRule.onNodeWithText("分享与去向").assertIsDisplayed()
        composeRule.onNodeWithText("本地笔记").assertIsDisplayed()
        composeRule.onNodeWithTag("destination-export").assertIsDisplayed()
        composeRule.onNodeWithText("BV_DESTINATION").assertDoesNotExist()
    }

    @Test
    fun destinationWithoutSelectedKnowledgeBaseExplainsAndDisablesSync() {
        val card = card()
        val state = UnarchiveUiState(
            create = CreateUiState("", false, "", false, 0),
            notes = NotesUiState(1, 0, listOf(document().title), listOf(card), listOf(document())),
            graph = GraphUiState(1),
            me = MeUiState(),
            destinations = DestinationUiState(
                card = card,
                localSaved = true,
                imaConfigured = true,
                imaTargetSelected = false,
                currentTargetLabel = "未选择目标",
            ),
        )
        composeRule.setContent {
            UnarchiveTheme {
                UnarchiveNavigationHost(
                    state = state,
                    onCreateEvent = {},
                    onNotesEvent = {},
                    onDestinationEvent = {},
                    onMeEvent = {},
                    legacyTestContent = { _, _ -> Text("legacy test") },
                    legacyResultsContent = { _ -> Text("legacy results") },
                    legacyLogContent = { _ -> Text("legacy log") },
                    legacySettingsContent = { _ -> Text("legacy settings") },
                    securityContent = { _ -> Text("security") },
                )
            }
        }

        composeRule.onNodeWithTag("bottom-nav-notes").performClick()
        composeRule.onNodeWithText("分享与去向").performClick()
        composeRule.onNodeWithTag("destination-target-required").assertIsDisplayed()
        composeRule.onNodeWithTag("destination-sync-ima").assertIsNotEnabled()
    }

    private fun card() = KnowledgeCard(
        cardId = KnowledgeCardId("bilibili", "BV_DESTINATION"),
        cardVersion = "version-destination",
        canonicalUrl = "https://www.bilibili.com/video/BV_DESTINATION",
        title = "目标笔记",
        ownerName = "UP 主",
        videoDurationSeconds = 1,
        timingAccuracy = TranscriptTimingAccuracy.EXACT,
        transcript = listOf(TranscriptSegment(0, 1_000, "内容")),
        baseState = CardStageState.SUCCEEDED,
        analysisState = CardStageState.SKIPPED,
        screenshotsState = CardStageState.SKIPPED,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
        markdown = "# 目标笔记",
    )

    private fun document() = NoteDocument(
        cardId = KnowledgeCardId("bilibili", "BV_DESTINATION"),
        title = "目标笔记",
        source = NoteSource("https://www.bilibili.com/video/BV_DESTINATION", "UP 主", 1_000, TranscriptTimingAccuracy.EXACT),
        sourceTranscript = listOf(TranscriptSegment(0, 1_000, "内容")),
        blocks = listOf(NoteBlock("summary", NoteBlockType.SUMMARY, NoteBlockOrigin.USER, text = "内容")),
        generation = NoteGeneration("version-destination", state = NoteGenerationState.COMPLETE, baseState = CardStageState.SUCCEEDED, analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED),
        editing = NoteEditingState(),
        publishing = NotePublishingState(),
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )
}
