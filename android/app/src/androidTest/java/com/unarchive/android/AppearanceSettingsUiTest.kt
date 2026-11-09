package com.unarchive.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.unarchive.android.ui.settings.AppearanceScreen
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppearanceSettingsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun keepsSystemOptionsAndBackActionWithoutGlassControls() {
        var returned = false
        composeRule.setContent {
            UnarchiveTheme {
                AppearanceScreen(onBack = { returned = true })
            }
        }

        composeRule.onNodeWithText("系统辅助选项").assertIsDisplayed()
        composeRule.onNodeWithText("动态效果").assertIsDisplayed()
        composeRule.onNodeWithText("跟随系统").assertIsDisplayed()
        composeRule.onNodeWithText("透明度与对比度").assertIsDisplayed()
        composeRule.onNodeWithText("系统优先").assertIsDisplayed()
        composeRule.onNodeWithText("液态玻璃").assertDoesNotExist()
        composeRule.onNodeWithTag("liquid-glass-section").assertDoesNotExist()
        composeRule.onNodeWithTag("liquid-glass-switch").assertDoesNotExist()
        composeRule.onNodeWithText("用于导航和短状态层；正文保持稳定阅读面。").assertDoesNotExist()
        composeRule.onNodeWithText("已关闭，玻璃元素降级为不透明背景，适合性能较低的设备。").assertDoesNotExist()
        composeRule.onNodeWithTag("appearance-back").performClick()
        composeRule.runOnIdle { assertTrue(returned) }
    }
}
