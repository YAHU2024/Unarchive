package com.unarchive.android

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardRelation
import com.unarchive.android.card.CardRelationType
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.GraphRelationItem
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
import com.unarchive.android.card.RelationDirection
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.GraphUiState
import com.unarchive.android.ui.state.MeUiState
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.UnarchiveUiState
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Rule
import org.junit.Test

class GraphRelationUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun graphShowsRelationListAndTextAlternative() {
        val first = document("one", "第一篇笔记")
        val second = document("two", "第二篇笔记")
        val sourceRelation = CardRelation(
            relationId = "source-one",
            type = CardRelationType.SOURCE_VIDEO,
            targetId = first.source.canonicalUrl,
            label = "来源视频",
            createdAtEpochMs = 1_000L,
            sourceCardId = first.cardId.value,
        )
        val userRelation = CardRelation(
            relationId = "user-link-one-two",
            type = CardRelationType.USER_LINK,
            targetId = second.cardId.value,
            label = "延伸阅读",
            createdAtEpochMs = 3_000L,
            sourceCardId = first.cardId.value,
            description = "同一主题",
        )
        val state = UnarchiveUiState(
            create = CreateUiState("", false, "", false, 0),
            notes = NotesUiState(
                noteCount = 2,
                materialCount = 0,
                noteTitles = listOf(first.title, second.title),
                noteDocuments = listOf(first, second),
            ),
            graph = GraphUiState(
                noteCount = 2,
                relationCount = 2,
                noteDocuments = listOf(first, second),
                selectedCardId = first.cardId.value,
                selectedTitle = first.title,
                outgoing = listOf(
                    GraphRelationItem(
                        relation = sourceRelation,
                        sourceCardId = first.cardId.value,
                        sourceTitle = first.title,
                        targetTitle = "B 站来源视频",
                        direction = RelationDirection.OUTGOING,
                    ),
                    GraphRelationItem(
                        relation = userRelation,
                        sourceCardId = first.cardId.value,
                        sourceTitle = first.title,
                        targetCardId = second.cardId.value,
                        targetTitle = second.title,
                        direction = RelationDirection.OUTGOING,
                    ),
                ),
            ),
            me = MeUiState(),
        )
        composeRule.setContent {
            UnarchiveTheme {
                UnarchiveNavigationHost(
                    state = state,
                    onCreateEvent = {},
                    onNotesEvent = {},
                    onMeEvent = {},
                    legacyTestContent = { _, _ -> Text("legacy test") },
                    legacyResultsContent = { _ -> Text("legacy results") },
                    legacyLogContent = { _ -> Text("legacy log") },
                    legacySettingsContent = { _ -> Text("legacy settings") },
                )
            }
        }

        composeRule.onNodeWithTag("bottom-nav-graph").performClick()
        composeRule.onNodeWithText("关系列表").assertIsDisplayed()
        composeRule.onNodeWithText("来源视频").assertIsDisplayed()
        composeRule.onNodeWithText("用户关联").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "当前笔记局部图谱：第一篇笔记。连接到：B 站来源视频、第二篇笔记。",
        ).assertIsDisplayed()
    }

    private fun document(videoId: String, title: String) = NoteDocument(
        cardId = KnowledgeCardId("bilibili", "BV$videoId"),
        title = title,
        source = NoteSource(
            canonicalUrl = "https://www.bilibili.com/video/BV$videoId",
            ownerName = "UP 主",
            durationMs = 10_000L,
            timingAccuracy = TranscriptTimingAccuracy.EXACT,
        ),
        sourceTranscript = emptyList(),
        blocks = listOf(
            NoteBlock(
                id = "summary-$videoId",
                type = NoteBlockType.SUMMARY,
                origin = NoteBlockOrigin.AI,
                text = "摘要",
            ),
        ),
        generation = NoteGeneration(
            cardVersion = "version-$videoId",
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
}
