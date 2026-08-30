package com.unarchive.android.graphspike

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class GraphSpikeUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun canvasExposesOverallGraphSemanticsAndListFallback() {
        rule.onNodeWithTag("graph-canvas").assertContentDescriptionContains("图谱画布")
        rule.onNodeWithText("模拟故障").performClick()
        rule.onNodeWithText("Canvas 渲染失败，已自动保留同源列表。").assertExists()
        rule.onNodeWithTag("graph-list").assertContentDescriptionContains("可滚动")
    }

    @Test
    fun listModeIsAvailableAsAnEquivalentOperationSurface() {
        rule.onNodeWithTag("list-tab").performClick()
        rule.onNodeWithTag("graph-list").assertExists()
        rule.onNodeWithText("节点列表（列表是完整操作面）").assertExists()
    }
}
