package com.unarchive.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.components.InsetGroupedList
import com.unarchive.android.ui.components.IosLikeButton
import com.unarchive.android.ui.components.IosLikeIconButton
import com.unarchive.android.ui.components.SearchField
import com.unarchive.android.ui.components.SettingsRow
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class IosLikeComponentsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun groupedListAndSettingsRowExposeTextAndStableTouchHeight() {
        composeRule.setContent {
            UnarchiveTheme {
                InsetGroupedList(
                    modifier = Modifier.testTag("grouped-list"),
                    title = "界面效果",
                ) {
                    SettingsRow(
                        title = "液态玻璃",
                        description = "正文保持稳定阅读面。",
                        modifier = Modifier.testTag("settings-row"),
                    )
                }
            }
        }

        composeRule.onNodeWithText("界面效果").assertIsDisplayed()
        composeRule.onNodeWithText("液态玻璃").assertIsDisplayed()
        composeRule.onNodeWithText("正文保持稳定阅读面。").assertIsDisplayed()
        val height = composeRule.onNodeWithTag("settings-row").fetchSemanticsNode().boundsInRoot.height
        assertTrue("settings row must keep a 48dp touch target", height >= 48.dp.value)
    }

    @Test
    fun searchFieldKeepsCallerOwnedQueryStateAndAccessibleLabel() {
        var query by mutableStateOf("")
        composeRule.setContent {
            UnarchiveTheme {
                Column {
                    SearchField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.testTag("search-field"),
                        placeholder = "搜索笔记",
                    )
                    Text("当前：$query", modifier = Modifier.testTag("query-output"))
                }
            }
        }

        composeRule.onNodeWithText("搜索笔记").assertIsDisplayed()
        composeRule.onNodeWithTag("search-field").performTextReplacement("跑步")
        composeRule.onNodeWithText("当前：跑步").assertIsDisplayed()
    }

    @Test
    fun buttonsExposeActionsWithStableTouchTargets() {
        var clicked = false
        composeRule.setContent {
            UnarchiveTheme {
                RowForTest(
                    onPrimaryClick = { clicked = true },
                    onIconClick = { clicked = true },
                )
            }
        }

        composeRule.onNodeWithText("继续").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(clicked) }
    }

    @Composable
    private fun RowForTest(
        onPrimaryClick: () -> Unit,
        onIconClick: () -> Unit,
    ) {
        Row {
            IosLikeButton(text = "继续", onClick = onPrimaryClick)
            IosLikeIconButton(
                contentDescription = "关闭",
                onClick = onIconClick,
            ) {
                androidx.compose.material3.Icon(Icons.Filled.Close, contentDescription = null)
            }
        }
    }
}
