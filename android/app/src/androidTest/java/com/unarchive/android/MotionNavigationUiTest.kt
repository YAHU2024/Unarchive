package com.unarchive.android

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.unarchive.android.ui.components.GlassDock
import com.unarchive.android.ui.components.GlassDockItem
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.GraphUiState
import com.unarchive.android.ui.state.MeUiState
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.UnarchiveUiState
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MotionNavigationUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun dockRetargetsSpringWhileSelectionAndActionsAreImmediate() {
        var selected by mutableStateOf(0)
        lateinit var inputModeManager: InputModeManager
        rule.setContent {
            inputModeManager = LocalInputModeManager.current
            DockFixture(selected, MotionPolicy()) { selected = it }
        }
        val first = capsuleLeft()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("tab-1").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("tab-1").assertIsSelected()
        rule.onNodeWithTag("tab-3").performClick()
        rule.mainClock.advanceTimeBy(64)
        rule.onNodeWithTag("tab-3").assertIsSelected()
        rule.runOnUiThread { inputModeManager.requestInputMode(InputMode.Keyboard) }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("tab-3").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("tab-3").assertIsFocused()
        val moving = capsuleLeft()
        rule.mainClock.advanceTimeBy(2000)
        assertTrue(capsuleLeft() > moving)
        assertTrue(moving >= first)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun reducedMotionDockSnapsAndKeepsAllTabsReachableWithoutGlass() {
        var selected by mutableStateOf(0)
        rule.setContent { DockFixture(selected, MotionPolicy.Reduced) { selected = it } }
        rule.mainClock.autoAdvance = false
        for (index in listOf(3, 1, 2, 0)) {
            rule.onNodeWithTag("tab-$index").performClick()
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("tab-$index").assertIsSelected()
            val tabLeft = rule.onNodeWithTag("tab-$index").fetchSemanticsNode().boundsInRoot.left
            assertEquals(tabLeft, capsuleLeft(), 1f)
        }
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun predictiveBackCancelKeepsNestedRouteThenCommitPopsExactlyOnce() {
        verifyPredictiveBack(MotionPolicy())
    }

    @Test
    fun reducedMotionPredictiveBackKeepsStackAndStateReachable() {
        verifyPredictiveBack(MotionPolicy.Reduced)
    }

    @Test
    fun restoredNavigationKeepsStaticDestinationAndBackStack() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { HostFixture(MotionPolicy.Reduced) }
        openToolsAndSettings()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("more-tools-settings").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("bottom-nav-me").assertIsSelected()
    }

    private fun verifyPredictiveBack(policy: MotionPolicy) {
        rule.setContent { HostFixture(policy) }
        openToolsAndSettings()
        val dispatcher = rule.activity.onBackPressedDispatcher
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(backEvent(0f))
            dispatcher.dispatchOnBackProgressed(backEvent(0.55f))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
        // Cancellation must retain the foreground route and its actions.
        rule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(backEvent(0f))
            dispatcher.dispatchOnBackProgressed(backEvent(0.8f))
        }
        rule.waitForIdle()
        rule.runOnUiThread { dispatcher.onBackPressed() }
        rule.onNodeWithTag("motion-settings").assertDoesNotExist()
        rule.onNodeWithTag("more-tools-settings").assertIsDisplayed()
        rule.runOnUiThread { dispatcher.onBackPressed() }
        rule.onNodeWithTag("bottom-nav-me").assertIsSelected()
        rule.onNodeWithTag("bottom-nav-create").performClick()
        rule.onNodeWithTag("create-screen").assertIsDisplayed()
    }

    private fun openToolsAndSettings() {
        rule.onNodeWithTag("bottom-nav-me").performClick()
        rule.onNodeWithTag("me-more-tools").performScrollTo().performClick()
        rule.onNodeWithTag("more-tools-settings").performClick()
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
    }

    private fun backEvent(progress: Float) = BackEventCompat(0f, 300f, progress, BackEventCompat.EDGE_LEFT)
    private fun capsuleLeft() = rule.onNodeWithTag("dock-selection-capsule", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot.left

    @Composable
    private fun DockFixture(selected: Int, policy: MotionPolicy, onSelect: (Int) -> Unit) {
        UnarchiveTheme(liquidGlassEnabled = false, motionPolicy = policy) {
            GlassDock(selectedIndex = selected, itemCount = 4) {
                repeat(4) { index ->
                    GlassDockItem("目的地 $index", Icons.Filled.Edit, selected == index, "tab-$index") {
                        onSelect(index)
                    }
                }
            }
        }
    }

    @Composable
    private fun HostFixture(policy: MotionPolicy) {
        UnarchiveTheme(liquidGlassEnabled = false, motionPolicy = policy) {
            UnarchiveNavigationHost(
                state = UnarchiveUiState(
                    create = CreateUiState("", false, "等待输入", false, 0),
                    notes = NotesUiState(0, 0, emptyList()),
                    graph = GraphUiState(0),
                    me = MeUiState(),
                ),
                onCreateEvent = {}, onNotesEvent = {}, onMeEvent = {},
                legacyTestContent = { _, _ -> Text("测试") },
                legacyLogContent = { _ -> Text("日志") },
                legacySettingsContent = { _ -> Text("设置", Modifier.testTag("motion-settings")) },
            )
        }
    }
}
