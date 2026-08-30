package com.unarchive.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.unarchive.android.asr.SiliconFlowModelCatalog
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SiliconFlowModelSettingsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun addsModelThenAllowsSwitchingToIt() {
        var models by mutableStateOf(listOf(SiliconFlowModelCatalog.DEFAULT_MODEL))
        var selected by mutableStateOf(SiliconFlowModelCatalog.DEFAULT_MODEL)
        var status by mutableStateOf("")

        composeRule.setContent {
            UnarchiveTheme {
                SiliconFlowModelSettings(
                    models = models,
                    selectedModel = selected,
                    status = status,
                    enabled = true,
                    onSelect = {
                        selected = it
                        status = "已切换模型：$it"
                    },
                    onAdd = { model ->
                        val normalized = model.trim()
                        if (normalized.isBlank() || normalized in models) {
                            status = "模型无效或已存在"
                            false
                        } else {
                            models = models + normalized
                            status = "模型已添加"
                            true
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag("siliconflow-model-section").assertIsDisplayed()
        composeRule.onNodeWithTag("siliconflow-model-input").performTextInput("Qwen/ASR-Test")
        composeRule.onNodeWithTag("siliconflow-model-add").performClick()
        composeRule.onNodeWithTag("siliconflow-model-radio-1").assertIsDisplayed()
        composeRule.onNodeWithTag("siliconflow-model-radio-1").performClick()
        composeRule.onNodeWithText("已切换模型：Qwen/ASR-Test").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(listOf(SiliconFlowModelCatalog.DEFAULT_MODEL, "Qwen/ASR-Test"), models)
            assertEquals("Qwen/ASR-Test", selected)
        }
    }
}
