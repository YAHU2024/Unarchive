package com.unarchive.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.unarchive.android.ui.create.CreateScreen
import com.unarchive.android.ui.state.CreateEvent
import com.unarchive.android.ui.state.CreateProcessingStage
import com.unarchive.android.ui.state.CreateProcessingUiState
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Small state/action gate; fixtures never invoke a provider or mutate user data. */
class CreationFlowUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun processingUsesTheCreateSurfaceAndHidesVideoInput() {
        val state = CreateUiState(
            "BV_example", true, "正在转写", false, 0,
            processing = CreateProcessingUiState(CreateProcessingStage.TRANSCRIBING, 0.4f, 2, true),
        )
        rule.setContent {
            UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
                CreateScreen(state, {}, {}, {}, onOpenDeveloperTest = {})
            }
        }

        rule.onNodeWithTag("create-input-surface").assertIsDisplayed()
        rule.onNodeWithTag("create-processing-stage").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("create-video-reference").fetchSemanticsNodes().size)
    }

    @Test
    fun reducedMotionCancellationKeepsCheckpointAndExplicitContinuationReachable() {
        val events = mutableListOf<CreateEvent>()
        var state by mutableStateOf(CreateUiState(
            "BV_example", true, "正在识别", false, 0,
            processing = CreateProcessingUiState(CreateProcessingStage.TRANSCRIBING, 0.4f, 3, true),
        ))
        rule.setContent {
            UnarchiveTheme(liquidGlassEnabled = false, motionPolicy = MotionPolicy.Reduced) {
                CreateScreen(state, { event ->
                    events += event
                    if (event == CreateEvent.CancelProcessing) {
                        state = state.copy(
                            isProcessing = false,
                            statusMessage = "视频处理已取消",
                            processing = state.processing.copy(stage = CreateProcessingStage.RECOVERABLE, isCancellable = false),
                        )
                    }
                }, {}, {}, onOpenDeveloperTest = {})
            }
        }
        rule.onNodeWithTag("create-processing-stage").assertIsDisplayed()
        rule.onNodeWithTag("create-generate-note").assertDoesNotExist()
        rule.onNodeWithTag("create-cancel").performScrollTo().performClick()
        rule.onNodeWithTag("create-cancel-confirm").assertIsDisplayed()
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.onNodeWithTag("create-cancel-confirm").performClick()
        rule.onNodeWithTag("create-checkpoint").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("create-cancel").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf(CreateEvent.CancelProcessing), events) }
        rule.onNodeWithTag("create-generate-note").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(CreateEvent.CancelProcessing, CreateEvent.GenerateNoteDraft), events) }
    }

    @Test
    fun failedAndCancelledStatesKeepRetainedMaterialsReachableWithoutStartingWork() {
        var state by mutableStateOf(CreateUiState(
            "BV_example", false, "", false, 1,
            processing = CreateProcessingUiState(CreateProcessingStage.FAILED),
        ))
        val events = mutableListOf<CreateEvent>()
        var openedNotes = 0
        rule.setContent {
            UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
                CreateScreen(state, events::add, { openedNotes++ }, {}, onOpenDeveloperTest = {})
            }
        }
        rule.onNodeWithText("处理未完成").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("create-review-results").performScrollTo().performClick()
        rule.runOnIdle {
            state = state.copy(processing = CreateProcessingUiState(CreateProcessingStage.CANCELLED))
        }
        rule.onNodeWithText("处理已取消").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("create-review-results").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(2, openedNotes)
            assertTrue(events.isEmpty())
        }
    }
}
