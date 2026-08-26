package com.unarchive.android

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.unarchive.android.ui.state.DestinationEvent
import com.unarchive.android.ui.state.DestinationFolderOption
import com.unarchive.android.ui.state.DestinationKnowledgeBaseOption
import com.unarchive.android.ui.state.DestinationTargetLoadState
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

    @Test
    fun destinationUsesCascadingSelectorsWithoutSyncingOrRenderingInternalIds() {
        val card = card()
        val events = mutableListOf<DestinationEvent>()
        val initialDestination = DestinationUiState(
            card = card,
            localSaved = true,
            imaConfigured = true,
            imaTargetSelected = false,
            targetRefreshEnabled = true,
            knowledgeBaseLoadState = DestinationTargetLoadState.LOADED,
            knowledgeBaseOptions = listOf(
                DestinationKnowledgeBaseOption("kb-secret", "课程库"),
            ),
        )
        composeRule.setContent {
            var destination by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(initialDestination)
            }
            UnarchiveTheme {
                UnarchiveNavigationHost(
                    state = UnarchiveUiState(
                        create = CreateUiState("", false, "", false, 0),
                        notes = NotesUiState(1, 0, listOf(document().title), listOf(card), listOf(document())),
                        graph = GraphUiState(1),
                        me = MeUiState(),
                        destinations = destination,
                    ),
                    onCreateEvent = {},
                    onNotesEvent = {},
                    onDestinationEvent = { event ->
                        events += event
                        when (event) {
                            is DestinationEvent.SelectKnowledgeBase -> destination = destination.copy(
                                imaTargetSelected = true,
                                imaSyncEnabled = true,
                                currentTargetLabel = "课程库 / 根目录",
                                selectedKnowledgeBaseName = "课程库",
                                selectedFolderPath = "根目录",
                                folderLoadState = DestinationTargetLoadState.LOADED,
                                folderOptions = listOf(
                                    DestinationFolderOption(
                                        "folder-secret",
                                        "第一章",
                                        "课程 / 第一章",
                                        1,
                                    ),
                                ),
                            )
                            is DestinationEvent.SelectFolder -> destination = destination.copy(
                                selectedFolderPath = "课程 / 第一章",
                                currentTargetLabel = "课程库 / 课程 / 第一章",
                            )
                            else -> Unit
                        }
                    },
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
        composeRule.onNodeWithTag("destination-knowledge-base").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("destination-knowledge-base-option-0").performClick()
        composeRule.onNodeWithTag("destination-folder").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("destination-folder-option-1").performClick()
        composeRule.onNodeWithText("课程 / 第一章").assertIsDisplayed()
        composeRule.onNodeWithTag("destination-sync-ima").assertIsEnabled()
        composeRule.onNodeWithText("kb-secret").assertDoesNotExist()
        composeRule.onNodeWithText("folder-secret").assertDoesNotExist()
        assert(events.none { it is DestinationEvent.SyncIma })
    }

    @Test
    fun destinationShowsTargetLoadFailuresWithoutEnablingSync() {
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
                imaTargetSelected = true,
                knowledgeBaseLoadState = DestinationTargetLoadState.FAILED,
                folderLoadState = DestinationTargetLoadState.FAILED,
                selectedKnowledgeBaseName = "课程库",
                selectedFolderPath = "根目录",
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
        composeRule.onNodeWithText("知识库加载失败，请重试。").assertIsDisplayed()
        composeRule.onNodeWithText("文件夹加载失败，可重试或选择根目录。").assertIsDisplayed()
        composeRule.onNodeWithTag("destination-sync-ima").assertIsNotEnabled()
    }

    @Test
    fun destinationActionsRemainReachableAtDoubleFontScale() {
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
                imaTargetSelected = true,
                imaSyncEnabled = true,
                knowledgeBaseLoadState = DestinationTargetLoadState.LOADED,
                folderLoadState = DestinationTargetLoadState.EMPTY,
                selectedKnowledgeBaseName = "课程资料知识库",
                selectedFolderPath = "根目录",
                currentTargetLabel = "课程资料知识库 / 根目录",
                knowledgeBaseOptions = listOf(DestinationKnowledgeBaseOption("kb-secret", "课程资料知识库")),
            ),
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
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
        }

        composeRule.onNodeWithTag("bottom-nav-notes").performClick()
        composeRule.onNodeWithText("分享与去向").performClick()
        composeRule.onNodeWithTag("destination-sync-ima").performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("管理凭据").performScrollTo().assertIsDisplayed()
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
