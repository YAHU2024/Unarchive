package com.unarchive.android

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.card.SingleCardRecoveryState
import com.unarchive.android.ui.components.SaveStateIndicator
import com.unarchive.android.ui.components.StatusBannerTone
import com.unarchive.android.ui.components.TaskProgressCard
import com.unarchive.android.ui.create.CreateScreen
import com.unarchive.android.ui.state.CreateEvent
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.RecoveryUiItem
import com.unarchive.android.ui.theme.LocalMotionPolicy
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MotionStateUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun stageRetargetingKeepsLatestTextProgressAndCancelBeforeAnimationEnds() {
        var stage by mutableStateOf("下载音频")
        var cancels = 0
        rule.setContent {
            UnarchiveTheme(motionPolicy = MotionPolicy()) {
                TaskProgressCard(
                    title = "正在处理视频", stageLabel = stage, progress = 0.42f,
                    stageModifier = Modifier.testTag("motion-stage"),
                    showCancel = true, onCancel = { cancels++ },
                )
            }
        }
        val top = rule.onNodeWithTag("motion-stage").fetchSemanticsNode().boundsInRoot.top
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { stage = "转写中" }
        rule.mainClock.advanceTimeBy(48)
        rule.onNodeWithText("转写中").assertIsDisplayed()
        // Drawing may move; the semantic layout used by focus stays stable.
        assertEquals(top, rule.onNodeWithTag("motion-stage").fetchSemanticsNode().boundsInRoot.top, 0.1f)
        rule.runOnUiThread { stage = "AI 整理中" }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("AI 整理中").assertIsDisplayed()
        rule.onNodeWithText("转写中").assertDoesNotExist()
        rule.onNodeWithText("取消处理").performClick()
        assertEquals(1, cancels)
        rule.mainClock.advanceTimeBy(2000)
        rule.onNodeWithText("AI 整理中").assertIsDisplayed()
        assertEquals(top, rule.onNodeWithTag("motion-stage").fetchSemanticsNode().boundsInRoot.top, 0.1f)
        assertEquals(1, cancels)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun reducedMotionUpdatesStageAndSaveFailureWithoutMovingOrHidingState() {
        var stage by mutableStateOf("转写中")
        var saved by mutableStateOf(false)
        rule.setContent {
            UnarchiveTheme(liquidGlassEnabled = false, motionPolicy = MotionPolicy.Reduced) {
                Column {
                    TaskProgressCard(
                        title = "处理状态", stageLabel = stage,
                        stageModifier = Modifier.testTag("motion-stage"),
                    )
                    SaveStateIndicator(
                        label = if (saved) "本机已保存" else "保存失败，草稿已保留",
                        tone = if (saved) StatusBannerTone.SUCCESS else StatusBannerTone.ERROR,
                        detail = if (saved) "尚未同步" else "请重试本地保存",
                    )
                }
            }
        }
        val top = rule.onNodeWithTag("motion-stage").fetchSemanticsNode().boundsInRoot.top
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { stage = "保存知识卡片"; saved = true }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("本机已保存").assertIsDisplayed()
        rule.onNodeWithText("尚未同步").assertIsDisplayed()
        assertEquals(top, rule.onNodeWithTag("motion-stage").fetchSemanticsNode().boundsInRoot.top, 0.1f)
        rule.runOnUiThread { saved = false }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("保存失败，草稿已保留").assertIsDisplayed()
        rule.onNodeWithText("请重试本地保存").assertIsDisplayed()
        rule.onNodeWithText("本机已保存").assertDoesNotExist()
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun saveFeedbackInterruptedByFailureShowsLatestStaticState() {
        var label by mutableStateOf("保存中")
        rule.setContent {
            UnarchiveTheme(motionPolicy = MotionPolicy()) {
                SaveStateIndicator(label, tone = StatusBannerTone.NEUTRAL)
            }
        }
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { label = "本机已保存" }
        rule.mainClock.advanceTimeBy(48)
        rule.onNodeWithText("本机已保存").assertIsDisplayed()
        rule.runOnUiThread { label = "同步失败，本机内容仍在" }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("同步失败，本机内容仍在").assertIsDisplayed()
        rule.onNodeWithText("本机已保存").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(2000)
        rule.onNodeWithText("同步失败，本机内容仍在").assertIsDisplayed()
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun restoredStageDuringAnimationUsesCurrentStaticStateWithoutReplaying() {
        var stage by mutableStateOf("转写中")
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            UnarchiveTheme(motionPolicy = MotionPolicy()) {
                TaskProgressCard(
                    title = "处理状态", stageLabel = stage,
                    stageModifier = Modifier.testTag("motion-stage"),
                )
            }
        }
        val top = rule.onNodeWithTag("motion-stage").fetchSemanticsNode().boundsInRoot.top
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { stage = "AI 整理中" }
        rule.mainClock.advanceTimeBy(48)
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("AI 整理中").assertIsDisplayed()
        assertEquals(top, rule.onNodeWithTag("motion-stage").fetchSemanticsNode().boundsInRoot.top, 0.1f)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun recoverySheetBackDismissesWithoutStartingRecoveryAndCanReopen() {
        verifyRecoverySheet(MotionPolicy(), useBack = true)
    }

    @Test
    fun reducedMotionSheetHasImmediateFocusCancelAndExplicitConfirmation() {
        verifyRecoverySheet(MotionPolicy.Reduced, useBack = false)
    }

    @Test
    fun recoveryBecomingUnavailableWhileSheetIsOpenDisablesConfirmation() {
        var state by mutableStateOf(recoveryState())
        val events = mutableListOf<CreateEvent>()
        rule.setContent { RecoveryFixture(state, MotionPolicy.Reduced, events::add) }
        openRecovery()
        rule.runOnUiThread { state = state.copy(isProcessing = true) }
        rule.onNodeWithTag("create-recovery-confirm").assertIsNotEnabled()
        rule.onNodeWithTag("create-recovery-dismiss").performClick()
        assertTrue(events.isEmpty())
    }

    @Test
    fun restoredRecoveryConfirmationRemainsExplicitAndDismissible() {
        val events = mutableListOf<CreateEvent>()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { RecoveryFixture(recoveryState(), MotionPolicy.Reduced, events::add) }
        openRecovery()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("glass-bottom-sheet").assertIsDisplayed()
        assertTrue(events.isEmpty())
        rule.onNodeWithTag("create-recovery-dismiss").performClick()
        rule.onNodeWithTag("glass-bottom-sheet").assertDoesNotExist()
        assertTrue(events.isEmpty())
    }

    @Test
    fun productionMotionPolicyMatchesCurrentSystemSettingWithoutChangingIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val setting = Settings.Global.ANIMATOR_DURATION_SCALE
        val original = Settings.Global.getString(resolver, setting)
        val expected = MotionPolicy.fromAnimatorDurationScale(Settings.Global.getFloat(resolver, setting, 1f))
        rule.setContent {
            UnarchiveTheme {
                Text(LocalMotionPolicy.current.mode.name, Modifier.testTag("system-motion"))
            }
        }
        rule.onNodeWithText(expected.mode.name).assertIsDisplayed()
        assertEquals(original, Settings.Global.getString(resolver, setting))
    }

    private fun verifyRecoverySheet(policy: MotionPolicy, useBack: Boolean) {
        val events = mutableListOf<CreateEvent>()
        rule.setContent { RecoveryFixture(recoveryState(), policy, events::add) }
        rule.mainClock.autoAdvance = false
        openRecovery()
        rule.onNodeWithText("继续生成这篇笔记？").assertIsDisplayed().assertIsFocused()
        assertTrue(events.isEmpty())
        if (useBack) Espresso.pressBack() else rule.onNodeWithTag("create-recovery-dismiss").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("glass-bottom-sheet").assertDoesNotExist()
        assertTrue(events.isEmpty())
        rule.mainClock.autoAdvance = true
        openRecovery()
        rule.onNodeWithTag("create-recovery-confirm").performClick()
        rule.onNodeWithTag("glass-bottom-sheet").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf(CreateEvent.ResumeSingleCard("motion-recovery")), events) }
    }

    private fun openRecovery() {
        rule.onNodeWithTag("create-recovery-resume-motion-recovery").performScrollTo().performClick()
        if (!rule.mainClock.autoAdvance) rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("glass-bottom-sheet").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun RecoveryFixture(state: CreateUiState, policy: MotionPolicy, onEvent: (CreateEvent) -> Unit) {
        UnarchiveTheme(liquidGlassEnabled = false, motionPolicy = policy) {
            CreateScreen(state, onEvent, {}, {}, onOpenDeveloperTest = {})
        }
    }

    private fun recoveryState() = CreateUiState(
        "", false, "", false, 0,
        recoveries = listOf(RecoveryUiItem(
            operationId = "motion-recovery", cardId = "bilibili:motion", cardVersion = "base",
            title = "可恢复笔记", stageLabel = "AI 分析",
            state = SingleCardRecoveryState.RECOVERABLE, canResume = true,
        )),
    )
}
