package com.unarchive.android

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.unarchive.android.ui.state.CreateEvent
import com.unarchive.android.ui.state.CreateProcessingStage
import com.unarchive.android.ui.state.CreateProcessingUiState
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.GraphUiState
import com.unarchive.android.ui.state.MeUiState
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.RecoveryUiItem
import com.unarchive.android.ui.state.UnarchiveUiState
import com.unarchive.android.card.SingleCardRecoveryState
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class D1NavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startsOnCreateAndNavigatesToGraph() {
        composeRule.setContent {
            UnarchiveTheme {
                UnarchiveNavigationHost(
                    state = sampleState(),
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

        composeRule.onNodeWithText("添加 B 站视频").assertIsDisplayed()
        composeRule.onNodeWithText("图谱").performClick()
        composeRule.onNodeWithText("局部关系").assertIsDisplayed()
    }

    @Test
    fun meProvidesMoreToolsEntryAndKeepsLegacyRoutesReachable() {
        composeRule.setContent {
            UnarchiveTheme {
                UnarchiveNavigationHost(
                    state = sampleState(),
                    onCreateEvent = {},
                    onNotesEvent = {},
                    onMeEvent = {},
                    legacyTestContent = { onBack, _ ->
                        LegacyRouteFrame(title = "legacy test", onBack = onBack) {
                            Text("legacy test", modifier = Modifier.testTag("legacy-test-content"))
                        }
                    },
                    legacyResultsContent = { onBack ->
                        LegacyRouteFrame(title = "legacy results", onBack = onBack) {
                            Text("legacy results", modifier = Modifier.testTag("legacy-results-content"))
                        }
                    },
                    legacyLogContent = { onBack ->
                        LegacyRouteFrame(title = "legacy log", onBack = onBack) {
                            Text("legacy log", modifier = Modifier.testTag("legacy-log-content"))
                        }
                    },
                    legacySettingsContent = { onBack ->
                        LegacyRouteFrame(title = "legacy settings", onBack = onBack) {
                            Text("legacy settings", modifier = Modifier.testTag("legacy-settings-content"))
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag("bottom-nav-me").performClick()
        composeRule.onNodeWithTag("me-more-tools").performClick()
        composeRule.onNodeWithText("更多工具").assertIsDisplayed()

        composeRule.onNodeWithTag("more-tools-test").performClick()
        composeRule.onNodeWithTag("legacy-test-content").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()

        composeRule.onNodeWithTag("more-tools-results").performClick()
        composeRule.onNodeWithTag("legacy-results-content").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()

        composeRule.onNodeWithTag("more-tools-logs").performClick()
        composeRule.onNodeWithTag("legacy-log-content").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回").performClick()

        composeRule.onNodeWithTag("more-tools-settings").performClick()
        composeRule.onNodeWithTag("legacy-settings-content").assertIsDisplayed()
    }

    @Test
    fun createPrimaryActionRequestsNoteDraftGeneration() {
        val events = mutableListOf<CreateEvent>()
        val state = sampleState().let { current ->
            current.copy(create = current.create.copy(videoReference = "BV1PS42197aM"))
        }
        composeRule.setContent {
            UnarchiveTheme {
                UnarchiveNavigationHost(
                    state = state,
                    onCreateEvent = events::add,
                    onNotesEvent = {},
                    onMeEvent = {},
                    legacyTestContent = { _, _ -> Text("legacy test") },
                    legacyResultsContent = { _ -> Text("legacy results") },
                    legacyLogContent = { _ -> Text("legacy log") },
                    legacySettingsContent = { _ -> Text("legacy settings") },
                )
            }
        }

        composeRule.onNodeWithText("开始生成笔记草稿").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(CreateEvent.GenerateNoteDraft), events)
        }
    }

    @Test
    fun singleCardRecoveryRequiresConfirmation() {
        val events = mutableListOf<CreateEvent>()
        val state = sampleState().copy(
            create = sampleState().create.copy(
                recoveries = listOf(
                    RecoveryUiItem(
                        operationId = "recovery-1",
                        cardId = "bilibili:BVrecovery",
                        cardVersion = "base-version",
                        title = "可恢复笔记",
                        stageLabel = "AI 分析",
                        state = SingleCardRecoveryState.RECOVERABLE,
                        canResume = true,
                    ),
                ),
            ),
        )
        composeRule.setContent {
            UnarchiveTheme {
                UnarchiveNavigationHost(
                    state = state,
                    onCreateEvent = events::add,
                    onNotesEvent = {},
                    onMeEvent = {},
                    legacyTestContent = { _, _ -> Text("legacy test") },
                    legacyResultsContent = { _ -> Text("legacy results") },
                    legacyLogContent = { _ -> Text("legacy log") },
                    legacySettingsContent = { _ -> Text("legacy settings") },
                )
            }
        }

        composeRule.onNodeWithTag("create-recovery-resume-recovery-1").performClick()
        composeRule.onNodeWithText("继续生成这篇笔记？").assertIsDisplayed()
        composeRule.onAllNodesWithText("继续生成").get(1).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(CreateEvent.ResumeSingleCard("recovery-1")), events)
        }
    }

    @Test
    fun createProcessingStateShowsStableStageProgressAndCheckpoint() {
        val state = sampleState().copy(
            create = sampleState().create.copy(
                isProcessing = true,
                statusMessage = "正在识别音频...",
                processing = CreateProcessingUiState(
                    stage = CreateProcessingStage.TRANSCRIBING,
                    progress = 0.42f,
                    checkpointSegmentCount = 3,
                    isCancellable = true,
                ),
            ),
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

        composeRule.onNodeWithTag("create-processing-stage").assertIsDisplayed()
        composeRule.onNodeWithText("转写中").assertIsDisplayed()
        composeRule.onNodeWithTag("create-progress").assertIsDisplayed()
        composeRule.onNodeWithTag("create-checkpoint").assertIsDisplayed()
        composeRule.onNodeWithText("检查点已保存：3 段文本，可安全中断。").assertIsDisplayed()
    }

    @Test
    fun recoverableCheckpointUsesContinueAction() {
        val state = sampleState().copy(
            create = sampleState().create.copy(
                videoReference = "BV1PS42197aM",
                statusMessage = "视频处理已取消。",
                processing = CreateProcessingUiState(
                    stage = CreateProcessingStage.RECOVERABLE,
                    checkpointSegmentCount = 3,
                ),
            ),
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

        composeRule.onNodeWithText("继续生成笔记草稿").assertIsDisplayed()
    }

    @Test
    fun processingCancelEmitsTypedEvent() {
        val events = mutableListOf<CreateEvent>()
        val state = sampleState().copy(
            create = sampleState().create.copy(
                isProcessing = true,
                statusMessage = "正在识别音频...",
                processing = CreateProcessingUiState(
                    stage = CreateProcessingStage.TRANSCRIBING,
                    progress = 0.42f,
                    isCancellable = true,
                ),
            ),
        )
        composeRule.setContent {
            UnarchiveTheme {
                UnarchiveNavigationHost(
                    state = state,
                    onCreateEvent = events::add,
                    onNotesEvent = {},
                    onMeEvent = {},
                    legacyTestContent = { _, _ -> Text("legacy test") },
                    legacyResultsContent = { _ -> Text("legacy results") },
                    legacyLogContent = { _ -> Text("legacy log") },
                    legacySettingsContent = { _ -> Text("legacy settings") },
                )
            }
        }

        composeRule.onNodeWithTag("create-cancel").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(CreateEvent.CancelProcessing), events)
        }
    }

    @Test
    fun batchRecoveryEmitsTypedEvent() {
        val events = mutableListOf<CreateEvent>()
        val state = sampleState().copy(create = sampleState().create.copy(hasRecovery = true))
        composeRule.setContent {
            UnarchiveTheme {
                UnarchiveNavigationHost(
                    state = state,
                    onCreateEvent = events::add,
                    onNotesEvent = {},
                    onMeEvent = {},
                    legacyTestContent = { _, _ -> Text("legacy test") },
                    legacyResultsContent = { _ -> Text("legacy results") },
                    legacyLogContent = { _ -> Text("legacy log") },
                    legacySettingsContent = { _ -> Text("legacy settings") },
                )
            }
        }

        composeRule.onNodeWithTag("create-batch-recovery").assertIsDisplayed()
        composeRule.onNodeWithTag("create-resume-batch").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf(CreateEvent.ResumeBatch), events)
        }
    }

    private fun sampleState() = UnarchiveUiState(
        create = CreateUiState(
            videoReference = "",
            isProcessing = false,
            statusMessage = "请输入 B站链接或选择本地音频。",
            hasRecovery = false,
            storedResultCount = 0,
        ),
        notes = NotesUiState(noteCount = 0, materialCount = 0, noteTitles = emptyList()),
        graph = GraphUiState(noteCount = 0),
        me = MeUiState(),
    )
}
