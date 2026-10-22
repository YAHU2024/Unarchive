package com.unarchive.android

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.unarchive.android.ui.components.ActionEmphasizedKey
import com.unarchive.android.ui.components.CupertinoIconAction
import com.unarchive.android.ui.components.CupertinoSegmentedControl
import com.unarchive.android.ui.components.SegmentPositionKey
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CupertinoAdapterMotionUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun segmentedSelectionIsImmediateAndSpringRetargetsOnRapidReversal() {
        var selected by mutableIntStateOf(0)
        rule.setContent {
            UnarchiveTheme(motionPolicy = MotionPolicy()) {
                CupertinoSegmentedControl(
                    selectedIndex = selected,
                    items = listOf("最近", "待整理", "已保存"),
                    modifier = Modifier.testTag("segments"),
                    itemModifier = { Modifier.testTag("segment-$it") },
                    onSelected = { selected = it },
                )
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("segment-2").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNodeWithTag("segment-2").assertIsSelected()
        rule.runOnIdle { assertEquals(2, selected) }
        rule.mainClock.advanceTimeBy(64)
        val towardLast = segmentPosition()
        assertTrue("the thumb should be moving toward the last segment", towardLast > 0f && towardLast < 2f)

        rule.onNodeWithTag("segment-0").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNodeWithTag("segment-0").assertIsSelected()
        rule.runOnIdle { assertEquals(0, selected) }
        rule.mainClock.advanceTimeBy(64)
        val retargeted = segmentPosition()
        assertTrue("rapid reversal must retarget without snapping", retargeted > 0f && retargeted < 2f)

        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(0f, segmentPosition(), 0.001f)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun reducedMotionSegmentsSnapToNewSelection() {
        var selected by mutableIntStateOf(0)
        rule.setContent {
            UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
                CupertinoSegmentedControl(
                    selectedIndex = selected,
                    items = listOf("最近", "待整理", "已保存"),
                    modifier = Modifier.testTag("segments"),
                    itemModifier = { Modifier.testTag("reduced-segment-$it") },
                    onSelected = { selected = it },
                )
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("reduced-segment-2").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNodeWithTag("reduced-segment-2").assertIsSelected()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(2f, segmentPosition(), 0f)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun iconActionProvidesBriefPressFeedbackWithoutChangingClickSemantics() {
        var clicks = 0
        rule.setContent {
            UnarchiveTheme(motionPolicy = MotionPolicy()) {
                CupertinoIconAction(
                    onClick = { clicks++ },
                    modifier = Modifier.testTag("feedback-action"),
                    contentDescription = "刷新",
                ) { Text("R") }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("feedback-action").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNodeWithTag("feedback-action")
            .assert(SemanticsMatcher.expectValue(ActionEmphasizedKey, true))
        rule.runOnIdle { assertEquals(1, clicks) }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("feedback-action")
            .assert(SemanticsMatcher.expectValue(ActionEmphasizedKey, false))
        rule.mainClock.autoAdvance = true
    }

    private fun segmentPosition(): Float = rule.onNodeWithTag("segments")
        .fetchSemanticsNode().config[SegmentPositionKey]
}
