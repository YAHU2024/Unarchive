package com.unarchive.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.unarchive.android.ui.settings.LiquidGlassSettingsSection
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiquidGlassSettingsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startsEnabledAndExplainsOpaqueFallbackAfterTurningItOff() {
        var enabled by mutableStateOf(true)

        composeRule.setContent {
            UnarchiveTheme {
                LiquidGlassSettingsSection(
                    enabled = enabled,
                    onEnabledChange = { enabled = it },
                    isBusy = false,
                )
            }
        }

        composeRule.onNodeWithTag("liquid-glass-section").assertIsDisplayed()
        composeRule.onNodeWithTag("liquid-glass-switch").assertIsOn().performClick()
        composeRule.onNodeWithTag("liquid-glass-switch").assertIsOff()
        composeRule.onNodeWithText("已关闭，玻璃元素降级为不透明背景，适合性能较低的设备。")
            .assertIsDisplayed()
        composeRule.runOnIdle { assertFalse(enabled) }
    }

    @Test
    fun disablesTheSwitchWhileSettingsAreBusy() {
        var changed = false

        composeRule.setContent {
            UnarchiveTheme {
                LiquidGlassSettingsSection(
                    enabled = true,
                    onEnabledChange = { changed = true },
                    isBusy = true,
                )
            }
        }

        composeRule.onNodeWithTag("liquid-glass-switch")
            .assertIsOn()
            .assertIsNotEnabled()
        composeRule.runOnIdle { assertTrue(!changed) }
    }
}
